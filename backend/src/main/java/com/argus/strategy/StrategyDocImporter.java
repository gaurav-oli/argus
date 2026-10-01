package com.argus.strategy;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Imports the published return-predictor literature into {@link AcademicStrategy} rows.
 *
 * <p><b>Why not scrape SSRN.</b> SSRN answers automated requests with HTTP 403 and its {@code robots.txt}
 * disallows AI crawlers outright, and its paper pages are abstracts over copyrighted PDFs. Chen &amp;
 * Zimmermann's <i>Open Source Cross-Sectional Asset Pricing</i> project has already done the extraction
 * properly and published it under GPL-2: one CSV carrying, for each of ~331 signals, the original paper, the
 * exact signal definition, the published t-stat and return, the portfolio construction rules, and — the part no
 * scraper could produce — the authors' own independent replication grade, including which signals are
 * <b>placebos</b> that were published but do not actually predict returns.
 *
 * <p>The file is fetched at runtime (never vendored, so no GPL-licensed content enters this repo) and parsed
 * into the corpus. Import is idempotent: the corpus is the source of truth for the published fields, while
 * Argus's own verdicts ({@code status}, {@code implementation}) survive a re-import untouched.
 */
@Service
public class StrategyDocImporter {

	private static final Logger log = LoggerFactory.getLogger(StrategyDocImporter.class);

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final AcademicStrategyRepository repository;
	private final SignalLibrary signals;
	private final String docUrl;
	private final boolean enabled;

	public StrategyDocImporter(AcademicStrategyRepository repository, SignalLibrary signals,
			@Value("${argus.strategy.doc-url:https://raw.githubusercontent.com/OpenSourceAP/CrossSection/master/SignalDoc.csv}")
			String docUrl, @Value("${argus.strategy.enabled:true}") boolean enabled) {
		this.repository = repository;
		this.signals = signals;
		this.docUrl = docUrl;
		this.enabled = enabled;
	}

	/** Monthly refresh — the corpus grows slowly (a handful of signals a year). */
	@Scheduled(cron = "${argus.strategy.import-cron:0 0 4 1 * *}", zone = "America/Toronto")
	public void scheduled() {
		if (!enabled) {
			return;
		}
		try {
			importOnce();
		}
		catch (RuntimeException ex) {
			log.warn("Agent 15: strategy corpus import failed: {}", ex.getMessage());
		}
	}

	/** Fill the corpus on a fresh deploy so the library is never empty. */
	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		if (!enabled) {
			return;
		}
		Thread.startVirtualThread(() -> {
			try {
				if (repository.count() == 0) {
					int n = importOnce();
					log.info("Agent 15: imported {} published strategies on first boot", n);
				}
			}
			catch (RuntimeException ex) {
				log.warn("Agent 15: first-boot strategy import failed: {}", ex.getMessage());
			}
		});
	}

	/**
	 * Fetch, parse and upsert the corpus; returns how many rows were written. Zero (with a warning) when the
	 * source is unreachable — a failed import must leave the existing corpus intact, never wipe it.
	 */
	public int importOnce() {
		Optional<String> body = fetch();
		if (body.isEmpty()) {
			log.warn("Agent 15: strategy documentation unavailable at {} — keeping the corpus as-is", docUrl);
			return 0;
		}
		List<Row> rows;
		try {
			rows = parse(body.get());
		}
		catch (RuntimeException ex) {
			log.warn("Agent 15: could not parse the strategy documentation: {}", ex.getMessage());
			return 0;
		}
		int written = 0;
		for (Row r : rows) {
			AcademicStrategy s = repository.findById(r.acronym()).orElseGet(() -> new AcademicStrategy(r.acronym(), r.name()));
			s.describe(r.name(), r.authors(), r.year(), r.journal(), r.kind(), r.dataCategory(), r.economicCategory(),
					r.definition(), r.tStat(), r.ret(), r.sign(), r.lsQuantile(), r.rebalanceMonths(), r.stockWeight(),
					r.sampleStart(), r.sampleEnd(), r.replicationGrade(), r.cites());
			// Only a signal we have actually implemented becomes a candidate for validation.
			signals.implementationFor(r.acronym()).ifPresent(s::implementedAs);
			repository.save(s);
			written++;
		}
		log.info("Agent 15: strategy corpus imported — {} signals ({} predictors, {} placebos), {} computable here",
				written, repository.countByKind(AcademicStrategy.Kind.PREDICTOR.name()),
				repository.countByKind(AcademicStrategy.Kind.PLACEBO.name()), repository.findByComputableTrue().size());
		return written;
	}

	private Optional<String> fetch() {
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(docUrl))
					.timeout(Duration.ofSeconds(30))
					.header("User-Agent", "Argus/1.0 (personal research; contact via repository)")
					.GET().build();
			HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
			return res.statusCode() == 200 && res.body() != null && !res.body().isBlank()
					? Optional.of(res.body()) : Optional.empty();
		}
		catch (IOException ex) {
			return Optional.empty();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		}
	}

	/** One parsed corpus row. Package-visible so the parser can be tested without the network. */
	record Row(String acronym, String name, String authors, Integer year, String journal, AcademicStrategy.Kind kind,
			String dataCategory, String economicCategory, String definition, BigDecimal tStat, BigDecimal ret,
			BigDecimal sign, BigDecimal lsQuantile, BigDecimal rebalanceMonths, String stockWeight, Integer sampleStart,
			Integer sampleEnd, String replicationGrade, Integer cites) {
	}

	/** Parse the documentation CSV. Rows without an acronym are skipped rather than failing the import. */
	static List<Row> parse(String csv) {
		List<List<String>> table = Csv.parse(new StringReader(csv));
		if (table.isEmpty()) {
			return List.of();
		}
		List<String> header = table.get(0);
		Map<String, Integer> col = new java.util.HashMap<>();
		for (int i = 0; i < header.size(); i++) {
			col.put(header.get(i).trim(), i);
		}
		List<Row> out = new ArrayList<>();
		for (int i = 1; i < table.size(); i++) {
			List<String> r = table.get(i);
			String acronym = get(r, col, "Acronym");
			if (acronym == null || acronym.isBlank()) {
				continue;
			}
			String longDesc = get(r, col, "LongDescription");
			out.add(new Row(acronym.trim(), longDesc == null || longDesc.isBlank() ? acronym.trim() : longDesc.trim(),
					get(r, col, "Authors"), integer(get(r, col, "Year")), get(r, col, "Journal"),
					kind(get(r, col, "Cat.Signal")), get(r, col, "Cat.Data"), get(r, col, "Cat.Economic"),
					get(r, col, "Detailed Definition"), decimal(get(r, col, "T-Stat")), decimal(get(r, col, "Return")),
					decimal(get(r, col, "Sign")), decimal(get(r, col, "LS Quantile")), decimal(get(r, col, "Portfolio Period")),
					get(r, col, "Stock Weight"), integer(get(r, col, "SampleStartYear")), integer(get(r, col, "SampleEndYear")),
					get(r, col, "Predictability in OP"), integer(get(r, col, "GScholarCites202509"))));
		}
		return out;
	}

	private static AcademicStrategy.Kind kind(String raw) {
		if (raw == null) {
			return AcademicStrategy.Kind.PREDICTOR;
		}
		return switch (raw.trim().toLowerCase(Locale.ROOT)) {
			case "placebo" -> AcademicStrategy.Kind.PLACEBO;
			case "drop" -> AcademicStrategy.Kind.DROP;
			default -> AcademicStrategy.Kind.PREDICTOR;
		};
	}

	private static String get(List<String> row, Map<String, Integer> col, String name) {
		Integer i = col.get(name);
		if (i == null || i >= row.size()) {
			return null;
		}
		String v = row.get(i);
		return v == null || v.isBlank() ? null : v;
	}

	private static Integer integer(String s) {
		BigDecimal d = decimal(s);
		return d == null ? null : d.intValue();
	}

	private static BigDecimal decimal(String s) {
		if (s == null || s.isBlank()) {
			return null;
		}
		try {
			return new BigDecimal(s.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}
}
