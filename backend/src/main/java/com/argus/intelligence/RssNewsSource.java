package com.argus.intelligence;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * RSS source for Agent 1 (Story 4.1, FR-8) — a free, keyless feed reader over a configured list of
 * finance RSS URLs ({@code argus.news.rss.feeds}). Headlines plus description; relevance to holdings
 * is resolved downstream by the tagger. Active only when at least one feed is configured. Per-feed
 * failures are isolated so one bad feed never breaks the others or the cycle.
 */
@Component
@ConditionalOnProperty(name = "argus.news.rss.feeds[0]")
public class RssNewsSource implements NewsSource {

	static final String NAME = "rss";

	private static final Logger log = LoggerFactory.getLogger(RssNewsSource.class);

	private final List<String> feeds;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private final DocumentBuilderFactory dbf = hardenedFactory();

	public RssNewsSource(NewsIngestionProperties props) {
		this.feeds = props.rss().feeds();
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public List<RawArticle> fetch(Collection<String> heldTickers) {
		List<RawArticle> out = new ArrayList<>();
		for (String feed : feeds) {
			out.addAll(fetchFeed(feed));
		}
		return out;
	}

	private List<RawArticle> fetchFeed(String feedUrl) {
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(feedUrl))
					.timeout(Duration.ofSeconds(10)).GET().build();
			HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
			if (res.statusCode() != 200) {
				log.warn("RSS feed {} returned HTTP {}", feedUrl, res.statusCode());
				return List.of();
			}
			DocumentBuilder builder = dbf.newDocumentBuilder();
			NodeList items = builder.parse(new ByteArrayInputStream(res.body()))
					.getElementsByTagName("item");
			List<RawArticle> out = new ArrayList<>();
			for (int i = 0; i < items.getLength(); i++) {
				if (items.item(i) instanceof Element item) {
					RawArticle a = toArticle(item);
					if (a != null) {
						out.add(a);
					}
				}
			}
			return out;
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return List.of();
		} catch (Exception ex) {
			log.warn("RSS feed {} failed: {}", feedUrl, ex.getMessage());
			return List.of();
		}
	}

	private RawArticle toArticle(Element item) {
		String headline = text(item, "title");
		String link = text(item, "link");
		if (headline.isEmpty()) {
			return null;
		}
		String guid = text(item, "guid");
		String externalId = !guid.isEmpty() ? guid : (!link.isEmpty() ? link : headline);
		String summary = text(item, "description");
		Instant published = parsePubDate(text(item, "pubDate"));
		return new RawArticle(NAME, externalId, link.isEmpty() ? null : link, headline,
				summary.isEmpty() ? null : summary, published, List.of());
	}

	private static String text(Element parent, String tag) {
		NodeList nodes = parent.getElementsByTagName(tag);
		for (int i = 0; i < nodes.getLength(); i++) {
			Node n = nodes.item(i);
			// Only direct children of <item> (skip e.g. a nested <link> in <source>).
			if (n.getParentNode() == parent) {
				String content = n.getTextContent();
				return content == null ? "" : content.trim();
			}
		}
		return "";
	}

	private static Instant parsePubDate(String raw) {
		if (raw.isEmpty()) {
			return Instant.now();
		}
		try {
			return ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
		} catch (RuntimeException ex) {
			return Instant.now();
		}
	}

	private static DocumentBuilderFactory hardenedFactory() {
		DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
		try {
			// Disable external entity resolution (XXE) — we parse untrusted feed XML.
			f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			f.setFeature("http://xml.org/sax/features/external-general-entities", false);
			f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			f.setXIncludeAware(false);
			f.setExpandEntityReferences(false);
		} catch (Exception ex) {
			// A parser that can't be hardened is better left at defaults than failing to construct.
			LoggerFactory.getLogger(RssNewsSource.class).warn("XML hardening unavailable: {}", ex.getMessage());
		}
		f.setNamespaceAware(true);
		return f;
	}
}
