package com.argus.portfolio;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The extraction prompt and response parsing shared by every LLM-assisted statement parser —
 * {@link LlmStatementParser} (one-shot, Haiku) and {@link AdaptiveStatementParser} (multi-pass,
 * local Gemma, with a self-verification loop). Kept in one place so the two parsers can never drift
 * on what "holdings"/"cash"/"accounts" JSON actually means; only HOW they call the model differs.
 */
final class StatementExtraction {

	static final String PROMPT = """
			You are a precise brokerage-statement parser. Below is the extracted text of a PDF that may
			contain MULTIPLE accounts and MULTIPLE statement periods (dates).

			Extract the CURRENT holdings AND the cash balances as JSON. Rules:
			- If the same account appears for more than one statement DATE, use ONLY the most recent date.
			- DUAL-CURRENCY ACCOUNTS: some brokerages (e.g. RBC Direct Investing) split ONE account into two
			  side-by-side sub-statements for the SAME date — a Canadian-dollar side ("Cdn. Dollar Statement"
			  / "…(CDN$)") and a US-dollar side ("U.S. Dollar Statement" / "…(U.S.$)"), each with its own
			  Asset Summary, Asset Review holdings, and cash balance. These are NOT duplicates and NOT
			  different dates — extract BOTH sides. The CAD-side securities/cash are "CAD"; the USD-side
			  securities/cash are "USD". One side may be empty ($0) while the other holds everything — still
			  return whatever the non-empty side holds. Give the two sides DISTINCT account labels that differ
			  only by currency (e.g. "64079 CAD RRSP" and "64079 USD RRSP"), but the SAME owner and the SAME
			  accountType.
			- Include ALL accounts and account types (Cash, TFSA, RRSP, RESP, etc.).
			- "holdings": real security positions only, listed under ANY asset-class heading (Common Shares,
			  Preferred Shares, Foreign Securities, Mutual Funds, "Other", ADRs, etc. — include them all).
			  EXCLUDE tax/GST lines, FX-rate lines, activity and transaction history, dividends, and any
			  "Total"/"Total Value of…" subtotal rows.
			- "cash": the uninvested CASH / money-market / sweep balance for each account SIDE that holds cash
			  (the account's cash, not invested in securities). One entry per account label (so a dual-currency
			  account can have a CAD cash entry AND a USD cash entry); skip $0 / no-cash sides. Use the cash
			  amount in that side's currency.
			- "ticker" must be the exchange symbol (e.g. NVDA, TSLA, VFV, XQQ), NOT the company name. The
			  symbol may be glued to the end of the company name in the text.
			- "shares" is the quantity held.
			- "bookValue" is the TOTAL book/cost value (the "Book Value" column), not the per-unit price.
			- "currency" is the account's currency: "CAD" or "USD".
			- "account" is a short label for the account, combining the account number and type when
			  available, e.g. "687WK3-B USD Cash", "RRSP (USD)", "TFSA", "Family RESP". Use the SAME label
			  for a holding and the cash in the same account. If the type is NOT in the account-number line
			  but appears in the statement HEADER/title (e.g. "TFSA Statement", "RRSP Statement", or
			  "Cdn. Dollar Statement"/"US Dollar Statement" which mean a Cash / non-registered account),
			  fold that type and the statement currency into the label, e.g. "68511 CAD TFSA".
			- If the same security is held in more than one account, return it once per account.
			- "accounts": one entry per DISTINCT account, describing who owns it and its registration type.
			  * "ownerType": "Joint" when the header lists two individual holders (two names, "OR", "JTWROS",
			    "joint"); "Corporate" when the holder is a company/business (name contains INC, LTD, CORP,
			    LIMITED, INCORPORATED, or a numbered company like "10264083 CANADA INC."); otherwise "Solo".
			  * "ownerName": the holder name(s), title-cased — for an individual "Gaurav Oli", for joint
			    "Gaurav Oli & Varsha Gupta", for a corporation the exact company name "10264083 Canada Inc.".
			    For a corporate statement addressed "COMPANY NAME / ATTN A PERSON", the owner is the COMPANY,
			    not the attn person.
			  * "accountType": the normalized registration type — one of TFSA, RRSP, RRIF, RESP, LIRA,
			    Margin, Cash, or Corporate. Derive it from the statement header/title and/or the account
			    label. A "Cdn. Dollar"/"US Dollar"/non-registered/cash account is "Cash"; a corporate/business
			    account with no registration is "Corporate". Leave null only if genuinely undeterminable.
			  Use the SAME "account" label as the holdings/cash for that account.

			Return ONLY a JSON object, no prose, no markdown fences:
			{"holdings":[{"ticker":"NVDA","companyName":"NVIDIA CORP","shares":401,"bookValue":50100.46,"currency":"USD","account":"687WK3-B USD Cash"}],
			 "cash":[{"account":"687WK3-B USD Cash","currency":"USD","amount":12345.67}],
			 "accounts":[{"account":"687WK3-B USD Cash","ownerType":"Joint","ownerName":"Gaurav Oli & Varsha Gupta","accountType":"Cash"}]}

			STATEMENT TEXT:
			""";

	private static final ObjectMapper JSON = JsonMapper.builder().build();

	private StatementExtraction() {
	}

	static String extractText(byte[] pdfBytes) {
		try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
			return new PDFTextStripper().getText(doc);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not read PDF", ex);
		}
	}

	/** @param message an informational note for the UI (e.g. "Parsed with AI assistance…"), or null —
	 *     NOT a failure signal; {@link ImportConfidence} only treats this as "something's wrong" when
	 *     non-null, so callers checking confidence must pass null here and attach their own note
	 *     afterward, same as {@link AdaptiveStatementParser} does. */
	static StatementParser.ParseResult toParseResult(Statement parsed, String message) {
		List<ParsedHolding> holdings = parsed.holdings().stream()
				.filter(h -> h.ticker() != null && !h.ticker().isBlank() && h.shares() != null)
				.map(StatementExtraction::toHolding)
				.toList();
		List<ParsedCash> cash = parsed.cash().stream()
				.filter(c -> c.account() != null && c.amount() != null && c.amount().signum() > 0)
				.map(StatementExtraction::toCash)
				.toList();
		List<ParsedAccount> accounts = parsed.accounts().stream()
				.filter(a -> a.account() != null && !a.account().isBlank())
				.map(StatementExtraction::toAccount)
				.toList();
		return new StatementParser.ParseResult(holdings, cash, accounts, message);
	}

	/**
	 * Deserialize the model response. Prefers the {@code {"holdings":[...],"cash":[...]}} object, but
	 * tolerates a bare holdings array (older behavior / a model that ignored the object instruction).
	 */
	static Statement readResponse(String response) {
		int objStart = response.indexOf('{');
		int objEnd = response.lastIndexOf('}');
		if (objStart >= 0 && objEnd > objStart && response.substring(objStart, objEnd + 1).contains("holdings")) {
			return JSON.readValue(response.substring(objStart, objEnd + 1), Statement.class);
		}
		int start = response.indexOf('[');
		int end = response.lastIndexOf(']');
		if (start < 0 || end <= start) {
			throw new IllegalStateException("Model returned no usable JSON");
		}
		List<Holding> holdings = JSON.readValue(response.substring(start, end + 1), new TypeReference<List<Holding>>() {
		});
		return new Statement(holdings, List.of(), List.of());
	}

	static String toJson(Statement statement) {
		return JSON.writeValueAsString(statement);
	}

	private static ParsedAccount toAccount(Account a) {
		String type = a.ownerType() == null ? null : a.ownerType().trim();
		if (type != null && !type.equalsIgnoreCase("Joint") && !type.equalsIgnoreCase("Solo")
				&& !type.equalsIgnoreCase("Corporate")) {
			type = null; // unrecognized → leave unset rather than storing noise
		}
		else if (type != null) {
			type = type.substring(0, 1).toUpperCase() + type.substring(1).toLowerCase();
		}
		String name = a.ownerName() == null || a.ownerName().isBlank() ? null : a.ownerName().trim();
		String accountType = AccountLabels.canonicalType(a.accountType());
		return new ParsedAccount(a.account().trim(), type, name, accountType);
	}

	private static ParsedHolding toHolding(Holding h) {
		String ccy = h.currency() == null ? "CAD" : h.currency().trim().toUpperCase();
		if (!ccy.equals("CAD") && !ccy.equals("USD")) {
			ccy = "CAD";
		}
		return new ParsedHolding(h.ticker().trim().toUpperCase(),
				h.companyName() == null ? null : h.companyName().trim(), h.shares(), h.bookValue(), ccy, null,
				h.account() == null ? null : h.account().trim(), false, List.of());
	}

	private static ParsedCash toCash(Cash c) {
		String ccy = c.currency() == null ? "CAD" : c.currency().trim().toUpperCase();
		if (!ccy.equals("CAD") && !ccy.equals("USD")) {
			ccy = "CAD";
		}
		return new ParsedCash(c.account().trim(), ccy, c.amount());
	}

	/** Loose mirror of the model's response object. */
	record Statement(List<Holding> holdings, List<Cash> cash, List<Account> accounts) {
		Statement {
			holdings = holdings == null ? List.of() : holdings;
			cash = cash == null ? List.of() : cash;
			accounts = accounts == null ? List.of() : accounts;
		}
	}

	/** Loose mirror of the model's JSON objects. */
	record Holding(String ticker, String companyName, BigDecimal shares, BigDecimal bookValue, String currency,
			String account) {
	}

	record Cash(String account, String currency, BigDecimal amount) {
	}

	record Account(String account, String ownerType, String ownerName, String accountType) {
	}
}
