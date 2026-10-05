package com.argus.portfolio;

import com.argus.model.ModelGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One-shot LLM-assisted brokerage-statement parser (Story 3.1 follow-up). Where the deterministic
 * {@link StatementParser} only handles a fixed column layout, this extracts the PDF text and asks the
 * model to return structured holdings — robust to real multi-account, multi-period bank statements
 * (e.g. National Bank). Still available as an explicit manual option; the automatic path
 * ({@link AdaptiveStatementParser}) tries local Gemma with a self-verification loop first and only
 * escalates to Haiku (what this class calls) as a last resort.
 *
 * <p><b>Privacy:</b> the statement text (tickers, share counts, book values, account numbers) is
 * sent to the model. Routed through {@link ModelGateway#escalate} (Claude Haiku) for accuracy on
 * this hard extraction; unlike the chat path this is not sanitized, so the holdings leave the box.
 */
@Component
public class LlmStatementParser {

	private static final Logger log = LoggerFactory.getLogger(LlmStatementParser.class);

	private final ModelGateway model;

	public LlmStatementParser(ModelGateway model) {
		this.model = model;
	}

	/** Parse holdings + cash from a statement PDF via the model. Throws if no usable JSON is returned. */
	public StatementParser.ParseResult parse(byte[] pdfBytes) {
		String text = StatementExtraction.extractText(pdfBytes);
		String raw = model.escalate(StatementExtraction.PROMPT + text);
		StatementExtraction.Statement parsed = StatementExtraction.readResponse(raw);
		StatementParser.ParseResult result = StatementExtraction.toParseResult(parsed,
				"Parsed with AI assistance — review the holdings below before confirming.");
		log.info("LLM statement parse: {} holdings, {} cash balance(s), {} account(s) extracted",
				result.holdings().size(), result.cash().size(), result.accounts().size());
		return result;
	}
}
