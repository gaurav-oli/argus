package com.argus.intelligence;

import com.argus.model.ModelGateway;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Turns a raw {@link BreakingAlert} (headline + metadata, recorded the instant it fires) into
 * something worth reading: one background step, one Gemma call per pending alert, that both checks
 * for duplicates against recently-covered stories and — if it's genuinely new — writes a
 * beginner-friendly summary. Combining the two in one call (rather than a separate dedup pass) means
 * the model sees the "already covered" context exactly when it needs it to judge sameness, and a
 * clear DUPLICATE verdict never needs a summary attempt at all.
 *
 * <p>Fails safe: a model or parse failure never marks an alert a duplicate (that would silently hide
 * a possibly-important story) — it falls back to a deterministic paragraph instead, same posture as
 * {@link NewsCurationService}.
 */
@Service
public class BreakingAlertCurationService {

	private static final Logger log = LoggerFactory.getLogger(BreakingAlertCurationService.class);
	private static final String DUPLICATE_VERDICT = "DUPLICATE";

	private final BreakingAlertRepository alerts;
	private final NewsArticleRepository articles;
	private final ModelGateway gateway;
	private final long lookbackHours;

	public BreakingAlertCurationService(BreakingAlertRepository alerts, NewsArticleRepository articles,
			ModelGateway gateway,
			@Value("${argus.breaking-alerts.lookback-hours:24}") long lookbackHours) {
		this.alerts = alerts;
		this.articles = articles;
		this.gateway = gateway;
		this.lookbackHours = lookbackHours;
	}

	@Scheduled(fixedDelayString = "${argus.breaking-alerts.generate-interval-ms:20000}",
			initialDelayString = "${argus.breaking-alerts.generate-initial-delay-ms:30000}")
	public void scheduledGenerate() {
		try {
			generateNextPending();
		}
		catch (RuntimeException ex) {
			log.warn("Breaking-alert curation failed: {}", ex.getMessage());
		}
	}

	/** Process the single oldest un-curated alert, if any. Not transactional over the model call. */
	public void generateNextPending() {
		BreakingAlert alert = alerts.findFirstBySummaryIsNullAndDuplicateFalseOrderByCreatedAtAsc().orElse(null);
		if (alert == null) {
			return;
		}
		NewsArticle article = alert.getArticleId() == null ? null : articles.findById(alert.getArticleId()).orElse(null);
		List<String> alreadyCovered = alerts
				.findBySummaryIsNotNullAndDuplicateFalseAndCreatedAtAfterOrderByCreatedAtDesc(freshnessCutoff())
				.stream()
				.map(BreakingAlert::getHeadline)
				.limit(20)
				.toList();

		Verdict v = curate(alert, article, alreadyCovered);
		if (v.duplicate()) {
			alert.markDuplicate();
			log.info("Breaking alert {} judged a duplicate of an already-covered story: {}",
					alert.getId(), alert.getHeadline());
		}
		else {
			alert.summarize(v.text(), v.fallback());
			log.info("Generated breaking-alert summary for {} ({}){}", alert.getId(), alert.getHeadline(),
					v.fallback() ? " [fallback]" : "");
		}
		alerts.save(alert);
	}

	private Verdict curate(BreakingAlert alert, NewsArticle article, List<String> alreadyCovered) {
		try {
			String raw = gateway.generate(prompt(alert, article, alreadyCovered));
			String cleaned = clean(raw);
			if (cleaned == null || cleaned.isBlank()) {
				return Verdict.summary(fallback(alert, article), true);
			}
			if (cleaned.equalsIgnoreCase(DUPLICATE_VERDICT)) {
				return Verdict.asDuplicate();
			}
			return Verdict.summary(cleaned, false);
		}
		catch (RuntimeException ex) {
			log.warn("Breaking-alert model call failed ({}) — using deterministic fallback", ex.getMessage());
			return Verdict.summary(fallback(alert, article), true);
		}
	}

	/** Either a DUPLICATE verdict, or a summary paragraph plus whether it's the deterministic fallback. */
	private record Verdict(boolean duplicate, String text, boolean fallback) {
		static Verdict asDuplicate() {
			return new Verdict(true, null, false);
		}

		static Verdict summary(String text, boolean fallback) {
			return new Verdict(false, text, fallback);
		}
	}

	private static String prompt(BreakingAlert alert, NewsArticle article, List<String> alreadyCovered) {
		String snippet = article != null && article.getSummary() != null ? article.getSummary() : "";
		String tickers = alert.getTickers().length > 0 ? String.join(", ", alert.getTickers()) : "none tagged";
		String covered = alreadyCovered.isEmpty() ? "None yet."
				: alreadyCovered.stream().map(h -> "- " + h).reduce((a, b) -> a + "\n" + b).orElse("None yet.");
		return """
				You are Argus, checking today's breaking market alerts for duplicates before writing a summary \
				for a complete beginner investor.

				ALREADY COVERED STORIES (skip if the new headline below is about the SAME underlying event, \
				even if worded differently or from a different source):
				%s

				NEW HEADLINE: %s
				TICKERS: %s
				NEW ARTICLE SNIPPET: %s

				Step 1: If the new headline is about the same underlying event as any story listed above, \
				respond with ONLY the single word: DUPLICATE

				Step 2: Otherwise, explain the news item in plain, everyday language that a curious \
				15-year-old could follow. Keep sentences short and simple, avoid jargon wherever you can.

				Write your answer in TWO parts separated by a line containing only "KEY TERMS:".

				Part 1 — THREE short paragraphs (3-4 sentences each), each on its own line with a blank line \
				between them:
				1. WHAT HAPPENED — plainly describe the event itself.
				2. WHY IT MATTERS — the reasons behind it and who's affected.
				3. MARKET IMPACT — how this could move the stock, the companies/industries involved, or the \
				wider market, and what to watch for next.
				If you must use a financial or technical word, explain it in plain words right there in \
				parentheses.

				Part 2 — after the "KEY TERMS:" line, list 3-5 financial or technical terms a beginner might \
				not know, ONE per line as "Term — a short, simple definition in everyday words." If none, \
				write "None".

				No hype, no disclaimers, no markdown, no preamble. Start directly with either DUPLICATE or \
				the first paragraph.
				""".formatted(covered, alert.getHeadline(), tickers, snippet);
	}

	/** Model output may include stray fences/preamble; keep it clean and bounded. Same 3200-char budget
	 * as the news-card summary (3-paragraph structure + a 5-term glossary). */
	private static String clean(String raw) {
		if (raw == null) {
			return null;
		}
		String s = raw.replace("```", "").strip();
		return s.length() <= 3200 ? s : s.substring(0, 3200).strip();
	}

	private static String fallback(BreakingAlert alert, NewsArticle article) {
		String snippet = article != null && article.getSummary() != null ? article.getSummary().strip() : "";
		String tickers = alert.getTickers().length > 0
				? " Tickers in focus: " + String.join(", ", alert.getTickers()) + "." : "";
		String base = alert.getHeadline() + ".";
		return snippet.isBlank() ? base + tickers : base + " " + snippet + tickers;
	}

	private Instant freshnessCutoff() {
		return Instant.now().minus(Duration.ofHours(lookbackHours));
	}
}
