package com.argus.intelligence;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * GDELT source for Agent 1 (Story 4.1, FR-8): free, keyless world + markets news. Headlines only (no
 * summary); relevance to holdings is resolved downstream by the tagger. Disable with
 * {@code argus.news.gdelt.enabled=false}. Failures yield an empty list so a GDELT outage never breaks the cycle.
 *
 * <p>Reads GDELT's 15-minute GKG files, not the Doc search API. From 2026-10-10 the Doc API refused ~3 of 4
 * calls from this IP (HTTP 429), and the calls that got through returned the same 75 articles frozen at
 * 2026-10-10 06:45 — four days with nothing new while the files stayed current. The files have no rate limit.
 *
 * <p>A file holds ~1,000 articles. Each new one costs a small-model sentiment pass downstream, so only
 * headlines matching the market/world topics are kept, syndicated copies are collapsed, market topics
 * rank ahead of world ones, and each file is capped at {@code argus.news.gdelt.per-file}.
 */
@Component
@ConditionalOnProperty(name = "argus.news.gdelt.enabled", havingValue = "true", matchIfMissing = true)
public class GdeltNewsSource implements NewsSource {

	static final String NAME = "gdelt";

	private static final Logger log = LoggerFactory.getLogger(GdeltNewsSource.class);
	private static final String BASE = "https://data.gdeltproject.org/gdeltv2/";
	// File stamps and GKG DATE: e.g. 20261015020000, UTC, always on a 15-minute boundary.
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
	private static final Duration SLOT = Duration.ofMinutes(15);
	/** A restart or a slow cycle catches up at most this many missed files — older news is stale anyway. */
	static final int MAX_CATCH_UP = 4;

	// GKG 2.1 columns (tab-separated).
	private static final int COL_DATE = 1;
	private static final int COL_URL = 4;
	private static final int COL_EXTRAS = 26;
	private static final Pattern TITLE = Pattern.compile("<PAGE_TITLE>(.*?)</PAGE_TITLE>");
	private static final Pattern GKG_URL = Pattern.compile("(\\d{14})\\.gkg\\.csv\\.zip");

	// The terms the old Doc API query asked for, split so markets outrank geopolitics when a file has more
	// matches than the cap. Not "shares": as a verb it pulled in "TikToker shares how...".
	static final Pattern MARKET = Pattern.compile("\\b(stock market|stocks?|earnings|inflation|central bank"
			+ "|fed|rate hikes?|rate cuts?|recession|tariffs?|opec|treasury yields?|bond yields?)\\b",
			Pattern.CASE_INSENSITIVE);
	static final Pattern WORLD = Pattern.compile("\\b(war|sanctions?|ceasefire)\\b", Pattern.CASE_INSENSITIVE);
	/** "war" also matches history and culture pieces. */
	static final Pattern NOT_NEWS = Pattern.compile("\\b(civil war|world war|cold war|war of 1812)\\b",
			Pattern.CASE_INSENSITIVE);

	private final int perFile;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
			.followRedirects(HttpClient.Redirect.NORMAL).build();
	/** Stamp of the last file read; null until the first one, so a boot reads only the latest. */
	private volatile String lastStamp;

	public GdeltNewsSource(NewsIngestionProperties props) {
		this.perFile = props.gdelt().perFile();
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public List<RawArticle> fetch(Collection<String> heldTickers) {
		try {
			String latest = latestStamp();
			if (latest == null || latest.equals(lastStamp)) {
				return List.of();
			}
			List<RawArticle> out = new ArrayList<>();
			for (String stamp : stampsToRead(lastStamp, latest, MAX_CATCH_UP)) {
				out.addAll(readFile(stamp));
			}
			lastStamp = latest;
			return out;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return List.of();
		}
		catch (RuntimeException | IOException ex) {
			log.warn("GDELT fetch failed: {}", ex.getMessage());
			return List.of();
		}
	}

	/** The newest GKG file's stamp from lastupdate.txt, or null when it can't be read. */
	private String latestStamp() throws IOException, InterruptedException {
		HttpResponse<String> res = http.send(get(BASE + "lastupdate.txt"), HttpResponse.BodyHandlers.ofString());
		if (res.statusCode() != 200) {
			log.warn("GDELT lastupdate.txt returned HTTP {}", res.statusCode());
			return null;
		}
		Matcher m = GKG_URL.matcher(res.body());
		return m.find() ? m.group(1) : null;
	}

	private List<RawArticle> readFile(String stamp) throws IOException, InterruptedException {
		HttpResponse<InputStream> res = http.send(get(BASE + stamp + ".gkg.csv.zip"),
				HttpResponse.BodyHandlers.ofInputStream());
		try (InputStream body = res.body()) {
			if (res.statusCode() != 200) {
				// GDELT occasionally skips a slot; the next one still arrives.
				log.debug("GDELT file {} returned HTTP {}", stamp, res.statusCode());
				return List.of();
			}
			try (ZipInputStream zip = new ZipInputStream(body)) {
				if (zip.getNextEntry() == null) {
					return List.of();
				}
				return pick(new BufferedReader(new InputStreamReader(zip, StandardCharsets.UTF_8)), perFile);
			}
		}
	}

	/**
	 * The file stamps to read, oldest first: every 15-minute slot after {@code last} up to {@code latest},
	 * at most {@code max} of the newest. Just {@code latest} when there is no {@code last} (first run).
	 */
	static List<String> stampsToRead(String last, String latest, int max) {
		LocalDateTime end = LocalDateTime.parse(latest, STAMP);
		LocalDateTime start = last == null ? end : LocalDateTime.parse(last, STAMP).plus(SLOT);
		LocalDateTime floor = end.minus(SLOT.multipliedBy(max - 1L));
		if (start.isBefore(floor)) {
			start = floor;
		}
		List<String> out = new ArrayList<>();
		for (LocalDateTime t = start; !t.isAfter(end); t = t.plus(SLOT)) {
			out.add(t.format(STAMP));
		}
		return out;
	}

	/**
	 * Up to {@code limit} articles from one GKG file: headlines matching the market or world topics, one per
	 * distinct headline (wire stories run on dozens of local sites), market ones first, then file order.
	 */
	static List<RawArticle> pick(BufferedReader gkg, int limit) throws IOException {
		record Candidate(int rank, int order, RawArticle article) {
		}
		List<Candidate> candidates = new ArrayList<>();
		Set<String> seenHeadlines = new HashSet<>();
		String line;
		while ((line = gkg.readLine()) != null) {
			String[] cols = line.split("\t", -1);
			if (cols.length <= COL_EXTRAS) {
				continue;
			}
			Matcher t = TITLE.matcher(cols[COL_EXTRAS]);
			String url = cols[COL_URL].trim();
			Instant published = parseStamp(cols[COL_DATE]);
			if (!t.find() || url.isEmpty() || published == null) {
				continue; // drop undated/untitled items rather than stamping them "now" (would skew the window)
			}
			String headline = unescape(t.group(1)).trim();
			int rank = MARKET.matcher(headline).find() ? 0 : WORLD.matcher(headline).find() ? 1 : -1;
			if (rank < 0 || NOT_NEWS.matcher(headline).find()
					|| !seenHeadlines.add(headline.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim())) {
				continue;
			}
			candidates.add(new Candidate(rank, candidates.size(),
					new RawArticle(NAME, url, url, headline, null, published, List.of())));
		}
		return candidates.stream()
				.sorted(Comparator.comparingInt(Candidate::rank).thenComparingInt(Candidate::order))
				.limit(limit)
				.map(Candidate::article)
				.toList();
	}

	private static String unescape(String s) {
		return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
				.replace("&lt;", "<").replace("&gt;", ">");
	}

	private static HttpRequest get(String url) {
		return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build();
	}

	/** Parse a GKG DATE, or {@code null} if it's missing/malformed (caller drops the item). */
	private static Instant parseStamp(String raw) {
		try {
			return LocalDateTime.parse(raw, STAMP).toInstant(ZoneOffset.UTC);
		}
		catch (RuntimeException ex) {
			return null;
		}
	}
}
