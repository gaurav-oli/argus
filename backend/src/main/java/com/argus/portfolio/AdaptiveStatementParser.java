package com.argus.portfolio;

import com.argus.model.ModelGateway;
import com.argus.model.ModelTier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The automatic, "never fails" statement parser: tries the local model (Gemma) first, re-checking
 * its own work against {@link ImportConfidence} and re-prompting it to find anything missed, up to
 * {@value #MAX_LOCAL_ATTEMPTS} times, before falling back to Haiku ({@link LlmStatementParser}) as a
 * last resort. One rule handles every reason a statement might be hard — a bank never seen before, a
 * familiar bank that quietly changed its layout, or just an unusually messy PDF — because the gate
 * doesn't ask "do we have a schema for this bank", it asks "does this specific result look complete".
 *
 * <p>Always returns a usable result: if confidence is never reached, the most complete attempt made
 * (local or Haiku) is returned rather than nothing, flagged with why it's still uncertain so a human
 * can look at it — "never fails" means never silent and never empty, not never wrong.
 */
@Component
public class AdaptiveStatementParser {

	private static final Logger log = LoggerFactory.getLogger(AdaptiveStatementParser.class);
	static final int MAX_LOCAL_ATTEMPTS = 3;

	private static final String VERIFY_PREAMBLE = """
			You are re-checking your own earlier extraction of this brokerage statement for anything
			missed. Your previous extraction (JSON) is below, followed by the same extraction rules as
			before, followed by the full statement text again. Carefully compare your previous JSON
			against the full text — look specifically for a holding, cash balance, or account you
			missed entirely, or an entire section you may have skipped (e.g. a second currency side, a
			joint account, another page). If you find anything missing, return the COMPLETE corrected
			JSON: everything you already had correctly, PLUS whatever was missing. If your original
			extraction was already complete, return the exact same JSON again, unchanged.

			YOUR PREVIOUS EXTRACTION:
			%s

			""";

	private final ModelGateway model;
	private final LlmStatementParser haikuFallback;

	public AdaptiveStatementParser(ModelGateway model, LlmStatementParser haikuFallback) {
		this.model = model;
		this.haikuFallback = haikuFallback;
	}

	/** Outcome of an adaptive parse: the best result found, whether it actually passed the confidence
	 * gate, and (when it didn't) why — so the caller can decide whether a human still needs to look. */
	public record Outcome(StatementParser.ParseResult result, boolean confident, String uncertainty) {
	}

	public Outcome parse(byte[] pdfBytes) {
		String text = StatementExtraction.extractText(pdfBytes);
		StatementParser.ParseResult best = null;
		StatementExtraction.Statement lastStatement = null;

		for (int attempt = 1; attempt <= MAX_LOCAL_ATTEMPTS; attempt++) {
			try {
				String prompt = attempt == 1 ? StatementExtraction.PROMPT + text
						: VERIFY_PREAMBLE.formatted(StatementExtraction.toJson(lastStatement)) + StatementExtraction.PROMPT + text;
				String raw = model.generate(prompt, ModelTier.BIG); // local Gemma, not Haiku
				lastStatement = StatementExtraction.readResponse(raw);
				StatementParser.ParseResult result = StatementExtraction.toParseResult(lastStatement, null);
				ImportConfidence.Verdict verdict = ImportConfidence.assess(result, text);
				best = betterOf(best, result);
				if (verdict.confident()) {
					log.info("Adaptive statement parse: confident after {} local attempt(s)", attempt);
					return new Outcome(withNote(result), true, null);
				}
				log.info("Adaptive statement parse: attempt {} not yet confident ({}) — {}",
						attempt, verdict.reason(), attempt < MAX_LOCAL_ATTEMPTS ? "re-checking" : "giving Haiku a try");
			}
			catch (RuntimeException ex) {
				log.warn("Adaptive statement parse: local attempt {} failed: {}", attempt, ex.getMessage());
			}
		}

		// Local Gemma couldn't reach confidence in MAX_LOCAL_ATTEMPTS tries — one more try via Haiku.
		try {
			StatementParser.ParseResult haikuResult = haikuFallback.parse(pdfBytes);
			ImportConfidence.Verdict verdict = ImportConfidence.assess(haikuResult, text);
			if (verdict.confident()) {
				log.info("Adaptive statement parse: confident after escalating to Haiku");
				return new Outcome(haikuResult, true, null);
			}
			StatementParser.ParseResult overall = betterOf(best, haikuResult);
			log.warn("Adaptive statement parse: still not fully confident after Haiku too ({}) — "
					+ "returning the most complete attempt for manual review", verdict.reason());
			return new Outcome(withReviewNote(overall, verdict.reason()), false, verdict.reason());
		}
		catch (RuntimeException ex) {
			log.warn("Adaptive statement parse: Haiku fallback also failed: {}", ex.getMessage());
			if (best != null) {
				return new Outcome(withReviewNote(best, "the AI parser hit an error partway through"),
						false, "the AI parser hit an error partway through");
			}
			throw ex; // nothing at all was ever extracted — let the caller's own failure handling take it
		}
	}

	/** More line items extracted = more complete, the simple proxy a verification pass naturally
	 * improves on (each pass is instructed to return everything-so-far PLUS whatever was missing). */
	private static StatementParser.ParseResult betterOf(StatementParser.ParseResult a, StatementParser.ParseResult b) {
		if (a == null) {
			return b;
		}
		int sizeA = a.holdings().size() + a.cash().size();
		int sizeB = b.holdings().size() + b.cash().size();
		return sizeB >= sizeA ? b : a;
	}

	private static StatementParser.ParseResult withNote(StatementParser.ParseResult r) {
		return new StatementParser.ParseResult(r.holdings(), r.cash(), r.accounts(),
				"Parsed automatically — checked against itself for anything missed.");
	}

	private static StatementParser.ParseResult withReviewNote(StatementParser.ParseResult r, String reason) {
		String note = "Parsed automatically, but " + (reason == null ? "couldn't be fully verified" : reason)
				+ " — please double-check the holdings below.";
		return new StatementParser.ParseResult(r.holdings(), r.cash(), r.accounts(), note);
	}
}
