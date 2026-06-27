package com.argus.portfolio;

import com.argus.model.ModelGateway;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * LLM-assisted brokerage-statement parser (Story 3.1 follow-up — the deferred "Mini-only LLM
 * parsing"). Where the deterministic {@link StatementParser} only handles a fixed column layout,
 * this extracts the PDF text and asks the model to return structured holdings — robust to real
 * multi-account, multi-period bank statements (e.g. National Bank).
 *
 * <p><b>Privacy:</b> the statement text (tickers, share counts, book values, account numbers) is
 * sent to the model. Routed through {@link ModelGateway#escalate} (Claude Haiku) for accuracy on
 * this hard extraction; unlike the chat path this is not sanitized, so the holdings leave the box.
 */
@Component
public class LlmStatementParser {

	private static final Logger log = LoggerFactory.getLogger(LlmStatementParser.class);

	private static final String PROMPT = """
			You are a precise brokerage-statement parser. Below is the extracted text of a PDF that may
			contain MULTIPLE accounts and MULTIPLE statement periods (dates).

			Extract the CURRENT holdings AND the cash balances as JSON. Rules:
			- If the same account appears for more than one statement date, use ONLY the most recent date.
			- Include ALL accounts and account types (Cash, TFSA, RRSP, RESP, etc.).
			- "holdings": real security positions only. EXCLUDE tax/GST lines, FX-rate lines, activity and
			  transaction history, dividends, and any "Total" rows.
			- "cash": the uninvested CASH / money-market / sweep balance for each account that holds cash
			  (the account's cash, not invested in securities). One entry per account; skip $0 / no-cash
			  accounts. Use the cash amount in that account's currency.
			- "ticker" must be the exchange symbol (e.g. NVDA, TSLA, VFV, XQQ), NOT the company name. The
			  symbol may be glued to the end of the company name in the text.
			- "shares" is the quantity held.
			- "bookValue" is the TOTAL book/cost value (the "Book Value" column), not the per-unit price.
			- "currency" is the account's currency: "CAD" or "USD".
			- "account" is a short label for the account, combining the account number and type when
			  available, e.g. "687WK3-B USD Cash", "RRSP (USD)", "TFSA", "Family RESP". Use the SAME label
			  for a holding and the cash in the same account.
			- If the same security is held in more than one account, return it once per account.

			Return ONLY a JSON object, no prose, no markdown fences:
			{"holdings":[{"ticker":"NVDA","companyName":"NVIDIA CORP","shares":401,"bookValue":50100.46,"currency":"USD","account":"687WK3-B USD Cash"}],
			 "cash":[{"account":"687WK3-B USD Cash","currency":"USD","amount":12345.67}]}

			STATEMENT TEXT:
			""";

	private final ModelGateway model;
	private final ObjectMapper json = JsonMapper.builder().build();

	public LlmStatementParser(ModelGateway model) {
		this.model = model;
	}

	/** Parse holdings + cash from a statement PDF via the model. Throws if no usable JSON is returned. */
	public StatementParser.ParseResult parse(byte[] pdfBytes) {
		String text = extractText(pdfBytes);
		String raw = model.escalate(PROMPT + text);
		LlmStatement parsed = readResponse(raw);
		List<ParsedHolding> holdings = parsed.holdings().stream()
				.filter(h -> h.ticker() != null && !h.ticker().isBlank() && h.shares() != null)
				.map(LlmStatementParser::toHolding)
				.toList();
		List<ParsedCash> cash = parsed.cash().stream()
				.filter(c -> c.account() != null && c.amount() != null && c.amount().signum() > 0)
				.map(LlmStatementParser::toCash)
				.toList();
		log.info("LLM statement parse: {} holdings, {} cash balance(s) extracted", holdings.size(), cash.size());
		return new StatementParser.ParseResult(holdings, cash,
				"Parsed with AI assistance — review the holdings below before confirming.");
	}

	private static ParsedHolding toHolding(LlmHolding h) {
		String ccy = h.currency() == null ? "CAD" : h.currency().trim().toUpperCase();
		if (!ccy.equals("CAD") && !ccy.equals("USD")) {
			ccy = "CAD";
		}
		return new ParsedHolding(h.ticker().trim().toUpperCase(),
				h.companyName() == null ? null : h.companyName().trim(), h.shares(), h.bookValue(), ccy, null,
				h.account() == null ? null : h.account().trim(), false, List.of());
	}

	private static ParsedCash toCash(LlmCash c) {
		String ccy = c.currency() == null ? "CAD" : c.currency().trim().toUpperCase();
		if (!ccy.equals("CAD") && !ccy.equals("USD")) {
			ccy = "CAD";
		}
		return new ParsedCash(c.account().trim(), ccy, c.amount());
	}

	/**
	 * Deserialize the model response. Prefers the {@code {"holdings":[...],"cash":[...]}} object, but
	 * tolerates a bare holdings array (older behavior / a model that ignored the object instruction).
	 */
	private LlmStatement readResponse(String response) {
		int objStart = response.indexOf('{');
		int objEnd = response.lastIndexOf('}');
		if (objStart >= 0 && objEnd > objStart && response.substring(objStart, objEnd + 1).contains("holdings")) {
			return json.readValue(response.substring(objStart, objEnd + 1), LlmStatement.class);
		}
		int start = response.indexOf('[');
		int end = response.lastIndexOf(']');
		if (start < 0 || end <= start) {
			throw new IllegalStateException("Model returned no usable JSON");
		}
		List<LlmHolding> holdings = json.readValue(response.substring(start, end + 1),
				new TypeReference<List<LlmHolding>>() {
				});
		return new LlmStatement(holdings, List.of());
	}

	private static String extractText(byte[] pdfBytes) {
		try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
			return new PDFTextStripper().getText(doc);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not read PDF", ex);
		}
	}

	/** Loose mirror of the model's response object. */
	private record LlmStatement(List<LlmHolding> holdings, List<LlmCash> cash) {
		LlmStatement {
			holdings = holdings == null ? List.of() : holdings;
			cash = cash == null ? List.of() : cash;
		}
	}

	/** Loose mirror of the model's JSON objects. */
	private record LlmHolding(String ticker, String companyName, BigDecimal shares, BigDecimal bookValue,
			String currency, String account) {
	}

	private record LlmCash(String account, String currency, BigDecimal amount) {
	}
}
