package com.argus.calendar;

import com.argus.calendar.CalendarSource.RawEvent;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Fed press-release RSS source for Agent 7 (Story 5.1, FR-21) — a free, keyless feed for FOMC/macro
 * events. Items mentioning the FOMC are mapped to {@link CalendarEventType#FED} events dated by their
 * publication date. Active unless {@code argus.calendar.fed-enabled=false}. Failures yield an empty
 * list so a Fed-site outage never breaks the daily run.
 */
@Component
@ConditionalOnProperty(name = "argus.calendar.fed-enabled", havingValue = "true", matchIfMissing = true)
public class FedCalendarSource implements CalendarSource {

	static final String NAME = "fed-rss";

	private static final Logger log = LoggerFactory.getLogger(FedCalendarSource.class);

	private final String feedUrl;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private final DocumentBuilderFactory dbf = hardenedFactory();

	public FedCalendarSource(CalendarProperties props) {
		this.feedUrl = props.fedRssUrl();
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public List<RawEvent> fetch(Collection<String> heldTickers) {
		try {
			HttpResponse<byte[]> res = http.send(
					HttpRequest.newBuilder(URI.create(feedUrl)).timeout(Duration.ofSeconds(10)).GET().build(),
					HttpResponse.BodyHandlers.ofByteArray());
			if (res.statusCode() != 200) {
				log.warn("Fed RSS returned HTTP {}", res.statusCode());
				return List.of();
			}
			NodeList items = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(res.body()))
					.getElementsByTagName("item");
			List<RawEvent> out = new ArrayList<>();
			for (int i = 0; i < items.getLength(); i++) {
				if (items.item(i) instanceof Element item) {
					RawEvent e = toEvent(item);
					if (e != null) {
						out.add(e);
					}
				}
			}
			return out;
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return List.of();
		} catch (Exception ex) {
			log.warn("Fed RSS fetch failed: {}", ex.getMessage());
			return List.of();
		}
	}

	private RawEvent toEvent(Element item) {
		String title = text(item, "title");
		// Only FOMC-related releases are calendar events; the feed carries all Fed press releases.
		if (title.isEmpty() || !title.toUpperCase().contains("FOMC")
				&& !title.toUpperCase().contains("FEDERAL OPEN MARKET")) {
			return null;
		}
		String link = text(item, "link");
		String guid = text(item, "guid");
		LocalDate date = parseDate(text(item, "pubDate"));
		if (date == null) {
			return null;
		}
		String externalId = !guid.isEmpty() ? guid : (!link.isEmpty() ? link : title);
		return new RawEvent(CalendarEventType.FED, null, title, date, NAME, externalId);
	}

	private static String text(Element parent, String tag) {
		NodeList nodes = parent.getElementsByTagName(tag);
		for (int i = 0; i < nodes.getLength(); i++) {
			Node n = nodes.item(i);
			if (n.getParentNode() == parent) {
				String content = n.getTextContent();
				return content == null ? "" : content.trim();
			}
		}
		return "";
	}

	private static LocalDate parseDate(String raw) {
		if (raw.isEmpty()) {
			return null;
		}
		try {
			return ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate();
		} catch (RuntimeException ex) {
			return null;
		}
	}

	private static DocumentBuilderFactory hardenedFactory() {
		DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
		try {
			f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			f.setFeature("http://xml.org/sax/features/external-general-entities", false);
			f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			f.setXIncludeAware(false);
			f.setExpandEntityReferences(false);
		} catch (Exception ex) {
			LoggerFactory.getLogger(FedCalendarSource.class).warn("XML hardening unavailable: {}", ex.getMessage());
		}
		f.setNamespaceAware(true);
		return f;
	}
}
