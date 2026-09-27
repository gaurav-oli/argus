package com.argus.filings;

import com.argus.intelligence.KnownUniverse;
import com.argus.model.LenientJsonParser;
import com.argus.model.ModelGateway;
import com.argus.model.ModelTier;
import com.argus.sec.EdgarClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Agent 14 — the filings and earnings reader. Argus had no idea what a company <em>said</em>: fundamentals were numbers only,
 * and nothing read an earnings release or a 10-Q. This reads them from SEC EDGAR (free): the newest earnings release (an 8-K with
 * Item 2.02 — its press-release exhibit carries results and guidance) and the newest 10-Q/10-K (MD&amp;A and risk factors).
 *
 * <p>The division of labour is the same as everywhere in Argus: <b>the local model extracts, code verifies and scores</b>. The
 * model returns a structured extraction; {@link FactVerifier} discards any figure that is not literally in the filing and refuses a
 * "going concern" flag the filing does not support; {@link FilingScorer} computes the score from the surviving fields. Each filing
 * is digested once (by accession number), so a run costs model time only when a company has actually filed something new.
 */
@Service
public class FilingDigestService {

	private static final Logger log = LoggerFactory.getLogger(FilingDigestService.class);
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Duration EARNINGS_LOOKBACK = Duration.ofDays(120);
	private static final Duration PERIODIC_LOOKBACK = Duration.ofDays(150);
	private static final int PRESS_RELEASE_CHARS = 12_000;
	private static final int MDNA_CHARS = 9_000;
	private static final int RISK_CHARS = 3_000;

	private final EdgarClient edgar;
	private final FilingDigestRepository digests;
	private final ModelGateway gateway;
	private final KnownUniverse universe;

	public FilingDigestService(EdgarClient edgar, FilingDigestRepository digests, ModelGateway gateway, KnownUniverse universe) {
		this.edgar = edgar;
		this.digests = digests;
		this.gateway = gateway;
		this.universe = universe;
	}

	/** A filing found on EDGAR, before digesting. */
	record Ref(String form, LocalDate filedAt, String accession, String primaryDoc, String items) {
		boolean isEarningsRelease() {
			return "8-K".equals(form) && items != null && items.contains("2.02");
		}
	}

	// ---- scheduled / on-demand refresh ----

	/** Every 6 hours: one cheap submissions call per ticker; the model only runs for filings not yet digested. */
	@Scheduled(cron = "${argus.filings.cron:0 20 */6 * * *}")
	public void refreshUniverse() {
		int created = 0;
		for (String t : universe.knownTickers()) {
			try {
				created += refresh(t).size();
			}
			catch (RuntimeException ex) {
				log.warn("Filings refresh for {} failed: {}", t, ex.getMessage());
			}
		}
		log.info("Filings reader: {} new filing(s) digested", created);
	}

	/** Digest any not-yet-seen earnings release and periodic report for {@code ticker}. Returns the new digests. */
	public List<FilingDigest> refresh(String rawTicker) {
		String ticker = rawTicker.trim().toUpperCase(Locale.ROOT);
		String cik = edgar.cikOf(ticker);
		if (cik == null) {
			return List.of(); // ETFs, funds and foreign listings have no EDGAR filer
		}
		Optional<String> body = edgar.fetchText("https://data.sec.gov/submissions/CIK" + cik + ".json");
		if (body.isEmpty()) {
			return List.of();
		}
		List<Ref> refs = refs(body.get(), LocalDate.now());
		List<FilingDigest> created = new ArrayList<>();
		for (Ref ref : newestPerKind(refs)) {
			if (digests.existsByAccession(ref.accession())) {
				continue;
			}
			try {
				digest(ticker, cik, ref).ifPresent(d -> {
					digests.save(d);
					created.add(d);
				});
			}
			catch (RuntimeException ex) {
				log.warn("Digest of {} {} for {} failed: {}", ref.form(), ref.accession(), ticker, ex.getMessage());
			}
		}
		return created;
	}

	// ---- reading EDGAR ----

	/** Recent 8-K earnings releases and 10-Q/10-K reports from a submissions payload. Package-visible for tests. */
	static List<Ref> refs(String submissionsJson, LocalDate today) {
		List<Ref> out = new ArrayList<>();
		JsonNode recent = JSON.readTree(submissionsJson).path("filings").path("recent");
		JsonNode forms = recent.path("form"), dates = recent.path("filingDate"), acc = recent.path("accessionNumber");
		JsonNode docs = recent.path("primaryDocument"), items = recent.path("items");
		for (int i = 0; i < forms.size(); i++) {
			String form = forms.path(i).asString("");
			LocalDate filed;
			try {
				filed = LocalDate.parse(dates.path(i).asString(""));
			}
			catch (RuntimeException ex) {
				continue;
			}
			Ref ref = new Ref(form, filed, acc.path(i).asString(""), docs.path(i).asString(""), items.path(i).asString(""));
			long age = java.time.temporal.ChronoUnit.DAYS.between(filed, today);
			if (ref.isEarningsRelease() && age <= EARNINGS_LOOKBACK.toDays()) out.add(ref);
			else if (("10-Q".equals(form) || "10-K".equals(form)) && age <= PERIODIC_LOOKBACK.toDays()) out.add(ref);
		}
		return out;
	}

	/** The newest earnings release and the newest periodic report only — older ones add cost, not information. */
	static List<Ref> newestPerKind(List<Ref> refs) {
		Ref earnings = refs.stream().filter(Ref::isEarningsRelease).findFirst().orElse(null);
		Ref periodic = refs.stream().filter(r -> !r.isEarningsRelease()).findFirst().orElse(null);
		List<Ref> out = new ArrayList<>();
		if (earnings != null) out.add(earnings);
		if (periodic != null) out.add(periodic);
		return out;
	}

	private Optional<FilingDigest> digest(String ticker, String cik, Ref ref) {
		String base = "https://www.sec.gov/Archives/edgar/data/" + Long.parseLong(cik) + "/" + ref.accession().replace("-", "");
		String source;
		FilingDigest.Kind kind;
		if (ref.isEarningsRelease()) {
			kind = FilingDigest.Kind.EARNINGS_RELEASE;
			source = earningsText(base, ref);
		}
		else {
			kind = "10-K".equals(ref.form()) ? FilingDigest.Kind.ANNUAL_REPORT : FilingDigest.Kind.QUARTERLY_REPORT;
			source = periodicText(base, ref);
		}
		if (source.isBlank()) {
			log.debug("No readable text for {} {}", ticker, ref.accession());
			return Optional.empty();
		}
		String prompt = prompt(ticker, ref.form(), kind, source);
		Optional<JsonNode> parsed = LenientJsonParser.parseObject(gateway.generate(prompt, ModelTier.BIG), log);
		if (parsed.isEmpty()) {
			parsed = LenientJsonParser.parseObject(gateway.generate(prompt + "\nYour previous reply was not valid JSON. Reply with ONLY the JSON object.",
					ModelTier.BIG), log);
		}
		return parsed.map(p -> build(ticker, ref, kind, source, p));
	}

	private String earningsText(String base, Ref ref) {
		Optional<String> index = edgar.fetchText(base + "/index.json");
		String exhibit = index.map(idx -> pickExhibit(idx, ref.primaryDoc())).orElse(null);
		String doc = exhibit != null ? exhibit : ref.primaryDoc();
		return edgar.fetchText(base + "/" + doc).map(h -> FilingExtractor.pressRelease(HtmlText.toText(h), PRESS_RELEASE_CHARS)).orElse("");
	}

	private String periodicText(String base, Ref ref) {
		return edgar.fetchText(base + "/" + ref.primaryDoc()).map(h -> {
			String text = HtmlText.toText(h);
			String mdna = FilingExtractor.mdna(text, MDNA_CHARS);
			String risks = FilingExtractor.riskFactors(text, RISK_CHARS);
			return (mdna.isEmpty() ? "" : "MD&A:\n" + mdna) + (risks.isEmpty() ? "" : "\n\nRISK FACTORS:\n" + risks);
		}).orElse("");
	}

	/** The press-release exhibit of an 8-K: the largest HTML document that is not the cover-page primary document. */
	static String pickExhibit(String indexJson, String primaryDoc) {
		String best = null;
		long bestSize = -1;
		for (JsonNode f : JSON.readTree(indexJson).path("directory").path("item")) {
			String name = f.path("name").asString("");
			String lower = name.toLowerCase(Locale.ROOT);
			if (!(lower.endsWith(".htm") || lower.endsWith(".html")) || name.equals(primaryDoc) || lower.contains("index") || lower.startsWith("r")
					&& lower.matches("r\\d+\\.htm.*")) {
				continue;
			}
			long size = 0;
			try {
				size = Long.parseLong(f.path("size").asString("0").trim());
			}
			catch (NumberFormatException ex) {
				// size is not always present
			}
			boolean looksLikeExhibit = lower.contains("99") || lower.contains("pr") || lower.contains("ex");
			long score = size + (looksLikeExhibit ? 1_000_000_000L : 0);
			if (score > bestSize) {
				bestSize = score;
				best = name;
			}
		}
		return best;
	}

	// ---- the model extracts ----

	static String prompt(String ticker, String form, FilingDigest.Kind kind, String source) {
		String what = kind == FilingDigest.Kind.EARNINGS_RELEASE ? "an earnings press release (Form 8-K, Item 2.02)"
				: "the MD&A and risk-factor sections of a " + form;
		return """
				You are extracting facts from %s for %s. Use ONLY the text below. Report only what the text states — do not infer,
				estimate or compute anything, and do not use anything you remember about the company. If something is not stated,
				use null, an empty list or "NONE". Every number you report must appear in the text exactly.

				TEXT:
				%s

				Respond with ONLY a JSON object:
				{"summary":"2-3 sentences of what the filing says","guidance":"RAISED|MAINTAINED|LOWERED|NONE",
				 "guidanceDetail":"the outlook/guidance figures exactly as stated, or empty","tone":"POSITIVE|NEUTRAL|NEGATIVE",
				 "keyMetrics":[{"label":"Revenue","value":"$96.2 billion","change":"up 106%% from a year ago"}],
				 "positives":["..."],"concerns":["..."],"newRisks":["a risk this filing newly raises"],"goingConcern":false}
				"guidance" is about the company's own forward outlook versus its prior outlook (NONE if the text gives no outlook).
				KEEP IT SHORT (your answer has a hard length limit): summary at most 2 sentences; at most 6 keyMetrics (the headline
				figures only, values under 12 words); at most 3 items each in positives, concerns and newRisks, each under 15 words.
				""".formatted(what, ticker, source);
	}

	// ---- code verifies and scores ----

	static FilingDigest build(String ticker, Ref ref, FilingDigest.Kind kind, String source, JsonNode p) {
		FactVerifier verifier = new FactVerifier(source);
		int verified = 0, dropped = 0;

		List<java.util.Map<String, String>> keptMetrics = new ArrayList<>();
		for (JsonNode m : p.path("keyMetrics")) {
			String value = m.path("value").asString(""), change = m.path("change").asString("");
			if (verifier.supportsMetric(value, change)) {
				keptMetrics.add(java.util.Map.of("label", m.path("label").asString(""), "value", value, "change", change));
				verified++;
			}
			else {
				dropped++;
			}
		}

		String guidance = p.path("guidance").asString("NONE").trim().toUpperCase(Locale.ROOT);
		if (!List.of("RAISED", "MAINTAINED", "LOWERED", "NONE").contains(guidance)) guidance = "NONE";
		String detail = p.path("guidanceDetail").asString("").trim();
		if (!detail.isEmpty()) {
			if (verifier.supports(detail)) {
				verified++;
			}
			else {
				dropped++;
				detail = ""; // the figures the model quoted are not in the filing
			}
		}
		if (!"NONE".equals(guidance) && detail.isEmpty() && !verifier.hasGuidanceLanguage()) {
			guidance = "NONE"; // a guidance call with neither verified figures nor any guidance wording is not trusted
		}

		boolean goingConcern = p.path("goingConcern").asBoolean(false) && verifier.confirmsGoingConcern();
		String tone = p.path("tone").asString("NEUTRAL").trim().toUpperCase(Locale.ROOT);
		if (!List.of("POSITIVE", "NEUTRAL", "NEGATIVE").contains(tone)) tone = "NEUTRAL";

		int positives = size(p.path("positives")), concerns = size(p.path("concerns")), newRisks = size(p.path("newRisks"));
		double score = FilingScorer.score(new FilingScorer.Fields(guidance, tone, goingConcern, positives, concerns, newRisks,
				kind == FilingDigest.Kind.EARNINGS_RELEASE));

		java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
		payload.put("summary", p.path("summary").asString(""));
		payload.put("guidance", guidance);
		payload.put("guidanceDetail", detail);
		payload.put("tone", tone);
		payload.put("keyMetrics", keptMetrics);
		payload.put("positives", strings(p.path("positives")));
		payload.put("concerns", strings(p.path("concerns")));
		payload.put("newRisks", strings(p.path("newRisks")));
		payload.put("goingConcern", goingConcern);
		return new FilingDigest(ticker, ref.accession(), ref.form(), kind, ref.filedAt(), clip(p.path("summary").asString("")), guidance, detail, tone, score,
				JSON.writeValueAsString(payload), verified, dropped, source.length(), "local model (extraction) + code (verification, scoring)");
	}

	private static int size(JsonNode arr) {
		return arr != null && arr.isArray() ? arr.size() : 0;
	}

	private static List<String> strings(JsonNode arr) {
		List<String> out = new ArrayList<>();
		if (arr != null && arr.isArray()) arr.forEach(n -> {
			String s = n.asString("").trim();
			if (!s.isEmpty()) out.add(s);
		});
		return out;
	}

	private static String clip(String s) {
		return s.length() <= 1500 ? s : s.substring(0, 1499) + "…";
	}

	// ---- reading back (no network, no model) ----

	/** The ticker's recent filings, scored and summarised, from stored digests only. Empty when none are recent. */
	public Optional<FilingView> view(String rawTicker) {
		String ticker = rawTicker.trim().toUpperCase(Locale.ROOT);
		List<FilingDigest> recent = digests.findTop12ByTickerOrderByFiledAtDesc(ticker);
		if (recent.isEmpty()) {
			return Optional.empty();
		}
		LocalDate today = LocalDate.now();
		List<FilingScorer.Aged> aged = new ArrayList<>();
		FilingDigest newestEarnings = null, newestPeriodic = null;
		for (FilingDigest d : recent) {
			boolean earnings = d.getKind() == FilingDigest.Kind.EARNINGS_RELEASE;
			if (earnings && newestEarnings == null) newestEarnings = d;
			if (!earnings && newestPeriodic == null) newestPeriodic = d;
		}
		for (FilingDigest d : new FilingDigest[] {newestEarnings, newestPeriodic}) {
			if (d != null) {
				aged.add(new FilingScorer.Aged(d.getScore(), d.getKind() == FilingDigest.Kind.EARNINGS_RELEASE,
						java.time.temporal.ChronoUnit.DAYS.between(d.getFiledAt(), today)));
			}
		}
		FilingDigest lead = newestEarnings != null ? newestEarnings : newestPeriodic;
		List<FilingView.Item> items = recent.stream().map(d -> new FilingView.Item(d.getForm(), d.getKind().name(), d.getFiledAt(), d.getSummary(),
				d.getGuidance(), d.getGuidanceDetail(), d.getTone(), d.getScore(), d.getVerifiedFacts(), d.getDroppedFacts(), d.getAccession())).toList();
		return Optional.of(new FilingView(ticker, FilingScorer.combined(aged), newestEarnings == null ? null : newestEarnings.getGuidance(), lead.getTone(),
				newestEarnings == null ? null : newestEarnings.getFiledAt(), lead.getSummary(),
				java.time.temporal.ChronoUnit.DAYS.between(lead.getFiledAt(), today), items));
	}

	/** Every ticker's newest digest, for the Intelligence overview. */
	public List<FilingDigest> latestPerTicker() {
		return digests.latestPerTicker();
	}

	public List<FilingDigest> history(String rawTicker) {
		return digests.findTop12ByTickerOrderByFiledAtDesc(rawTicker.trim().toUpperCase(Locale.ROOT));
	}
}
