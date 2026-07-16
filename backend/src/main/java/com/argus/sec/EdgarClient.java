package com.argus.sec;

import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * SEC EDGAR access for Agent 4 (free; only requires a descriptive User-Agent). Resolves ticker→CIK
 * from the public company-tickers map, lists a ticker's recent insider Form 4 filings, and parses
 * each Form 4 XML into a summarized transaction. Best-effort; any failure yields empty/null rather
 * than throwing, so one bad filing never aborts a cycle.
 */
@Component
public class EdgarClient {

	private static final Logger log = LoggerFactory.getLogger(EdgarClient.class);
	private static final ObjectMapper JSON = JsonMapper.builder().build();
	private static final int MAX_FORM4_PER_TICKER = 8;

	private final String userAgent;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	private final Map<String, String> cikByTicker = new ConcurrentHashMap<>();

	public EdgarClient(
			@Value("${argus.sec.user-agent:Argus/1.0 (gaurav.oli@brainridgeconsulting.com)}") String userAgent) {
		this.userAgent = userAgent;
	}

	/** Parse the ticker's insider Form 4s filed on/after {@code since}. Empty on any failure. */
	public List<RawSecFiling> insiderFilings(String ticker, LocalDate since) {
		try {
			String cik = cikFor(ticker);
			if (cik == null) {
				return List.of();
			}
			return recentForm4s(ticker, cik, since);
		}
		catch (RuntimeException | InterruptedException ex) {
			log.debug("EDGAR fetch failed for {}: {}", ticker, ex.getMessage());
			if (ex instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			return List.of();
		}
	}

	private String cikFor(String ticker) throws InterruptedException {
		if (cikByTicker.isEmpty()) {
			loadTickerMap();
		}
		return cikByTicker.get(ticker.toUpperCase());
	}

	private void loadTickerMap() throws InterruptedException {
		JsonNode root = JSON.readTree(get("https://www.sec.gov/files/company_tickers.json"));
		for (JsonNode n : root) {
			String t = n.path("ticker").asString();
			long cik = n.path("cik_str").asLong();
			if (t != null && !t.isBlank()) {
				cikByTicker.put(t.toUpperCase(), String.format("%010d", cik));
			}
		}
		log.info("Loaded {} ticker→CIK mappings from EDGAR", cikByTicker.size());
	}

	private List<RawSecFiling> recentForm4s(String ticker, String cik, LocalDate since)
			throws InterruptedException {
		JsonNode recent = JSON.readTree(get("https://data.sec.gov/submissions/CIK" + cik + ".json"))
				.path("filings").path("recent");
		JsonNode forms = recent.path("form");
		JsonNode dates = recent.path("filingDate");
		JsonNode accessions = recent.path("accessionNumber");
		JsonNode primaryDocs = recent.path("primaryDocument");

		List<RawSecFiling> out = new ArrayList<>();
		for (int i = 0; i < forms.size() && out.size() < MAX_FORM4_PER_TICKER; i++) {
			if (!"4".equals(forms.path(i).asString())) {
				continue;
			}
			LocalDate filed = LocalDate.parse(dates.path(i).asString());
			if (filed.isBefore(since)) {
				break; // recent[] is newest-first; once older than the window, stop
			}
			String accession = accessions.path(i).asString();
			RawSecFiling parsed = parseForm4(ticker, cik, accession, primaryDocs.path(i).asString(), filed);
			if (parsed != null) {
				out.add(parsed);
			}
		}
		return out;
	}

	private RawSecFiling parseForm4(String ticker, String cik, String accession, String primaryDoc,
			LocalDate filed) throws InterruptedException {
		String accNoDashes = accession.replace("-", "");
		// primaryDocument may be the XSL-styled view (xslF345X0?/file.xml); the raw XML is the basename.
		String rawDoc = primaryDoc.substring(primaryDoc.lastIndexOf('/') + 1);
		String base = "https://www.sec.gov/Archives/edgar/data/" + Long.parseLong(cik) + "/" + accNoDashes;
		String url = base + "/" + rawDoc;
		String xml;
		try {
			xml = get(url);
		}
		catch (RuntimeException ex) {
			return null;
		}
		try {
			Document doc = parseXmlSafely(xml);
			String owner = text(doc, "rptOwnerName");
			String title = relationship(doc);

			NodeList txns = doc.getElementsByTagName("nonDerivativeTransaction");
			BigDecimal totalShares = BigDecimal.ZERO;
			BigDecimal totalValue = BigDecimal.ZERO;
			boolean buy = false;
			boolean sell = false;
			boolean grant = false;
			for (int i = 0; i < txns.getLength(); i++) {
				Element t = (Element) txns.item(i);
				String code = childValue(t, "transactionCode");
				BigDecimal shares = number(childValue(t, "transactionShares"));
				BigDecimal price = number(childValue(t, "transactionPricePerShare"));
				if (shares != null) {
					totalShares = totalShares.add(shares);
					if (price != null) {
						totalValue = totalValue.add(shares.multiply(price));
					}
				}
				switch (code == null ? "" : code) {
					case "P" -> buy = true;
					case "S", "F" -> sell = true;
					case "A", "M", "G" -> grant = true;
					default -> { /* other codes don't drive direction */ }
				}
			}
			String type = buy ? "BUY" : sell ? "SELL" : grant ? "GRANT" : "OTHER";
			return new RawSecFiling(ticker, cik, accession, "4", filed,
					"https://www.sec.gov/Archives/edgar/data/" + Long.parseLong(cik) + "/" + accNoDashes,
					owner, title, type, totalShares, totalValue.signum() == 0 ? null : totalValue);
		}
		catch (RuntimeException ex) {
			log.debug("Form 4 parse failed for {} {}: {}", ticker, accession, ex.getMessage());
			return null;
		}
	}

	private static String relationship(Document doc) {
		String officerTitle = text(doc, "officerTitle");
		if (officerTitle != null && !officerTitle.isBlank()) {
			return officerTitle;
		}
		if ("1".equals(text(doc, "isDirector"))) {
			return "Director";
		}
		if ("1".equals(text(doc, "isTenPercentOwner"))) {
			return "10% owner";
		}
		return "Insider";
	}

	/** First descendant element with this tag, its text content (trimmed) or null. */
	private static String text(Document doc, String tag) {
		NodeList nodes = doc.getElementsByTagName(tag);
		return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().strip();
	}

	/** A transaction's nested {@code <tag><value>...</value></tag>} text. */
	private static String childValue(Element txn, String tag) {
		NodeList nodes = txn.getElementsByTagName(tag);
		if (nodes.getLength() == 0) {
			return null;
		}
		Node value = ((Element) nodes.item(0)).getElementsByTagName("value").item(0);
		return value == null ? nodes.item(0).getTextContent().strip() : value.getTextContent().strip();
	}

	private static BigDecimal number(String s) {
		try {
			return s == null || s.isBlank() ? null : new BigDecimal(s);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static Document parseXmlSafely(String xml) {
		try {
			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
			dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			dbf.setExpandEntityReferences(false);
			return dbf.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
		}
		catch (Exception ex) {
			throw new IllegalStateException("XML parse failed", ex);
		}
	}

	private String get(String url) throws InterruptedException {
		try {
			// No Accept-Encoding: java.net.http doesn't auto-decompress, so we want a plain body.
			HttpResponse<String> res = http.send(HttpRequest.newBuilder().uri(URI.create(url))
					.timeout(Duration.ofSeconds(12)).header("User-Agent", userAgent).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() != 200) {
				throw new IllegalStateException("HTTP " + res.statusCode() + " for " + url);
			}
			return res.body();
		}
		catch (java.io.IOException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
