package com.argus.portfolio;

import com.argus.common.BadRequestException;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * Deterministic brokerage-statement parser (Story 3.1, FR-1). Extracts text with Apache PDFBox and
 * applies line-based heuristics to pull out holdings (ticker, shares, cost basis, acquisition
 * date). No LLM is involved — extraction must be deterministic and unit-testable, and keeps the
 * platform's framing rule (the model never generates numbers).
 *
 * <p>Heuristic: a line is treated as a holding when it starts with a ticker token and carries at
 * least one numeric token. Each field the line cannot yield is flagged (not dropped), so a
 * partially-parsed row is kept with {@code needsReview = true} (FR-1 consequence #2). Robustness to
 * the full variety of statement layouts is aspirational (assumption A-14, ≥95% target) and is
 * refined in later work; the contract here is "never silently lose a row".
 */
@Component
public class StatementParser {

	/** Leading ticker: 1–5 uppercase letters, optional exchange/class suffix (e.g. {@code SHOP.TO}). */
	private static final Pattern TICKER = Pattern.compile("^([A-Z]{1,5}(?:\\.[A-Z]{1,3})?)\\b");

	/**
	 * A number with optional thousands separators and decimals (e.g. {@code 1,234.56}). The
	 * comma-grouped alternative requires at least one group ({@code +}, not {@code *}) so that a
	 * plain ≥1000 value without separators (e.g. {@code 12345.67}) falls through to the second
	 * alternative and is matched whole — rather than being split into {@code 123} + {@code 45.67}.
	 */
	private static final Pattern NUMBER = Pattern.compile("\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?");

	private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

	/**
	 * Any date written with {@code -} or {@code /} separators. Stripped from a row before the
	 * number scan so date digits (e.g. {@code 01/15/2023}) aren't misread as shares/cost basis.
	 * Only ISO dates are additionally {@link #parseDate parsed} into a value; other formats leave
	 * {@code acquisitionDate} null and flag the row (no silent drop, no polluted numbers).
	 */
	private static final Pattern DATE_LIKE = Pattern.compile("\\d{1,4}[-/]\\d{1,2}[-/]\\d{1,4}");
	private static final Pattern CURRENCY = Pattern.compile("\\b(USD|CAD)\\b");

	private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

	/** Lines that look tabular but are summaries, not holdings — skip outright. */
	private static final Pattern NON_HOLDING = Pattern.compile(
			"^(TOTAL|SUBTOTAL|CASH|BALANCE|ACCOUNT|PAGE)\\b", Pattern.CASE_INSENSITIVE);

	/**
	 * Parse a statement PDF.
	 *
	 * @param pdfBytes the uploaded file's raw bytes
	 * @return the extracted holdings plus an optional top-level note; never throws on a
	 *     non-statement / empty PDF (returns zero holdings with a message instead)
	 * @throws BadRequestException when the bytes are not a readable PDF at all
	 */
	public ParseResult parse(byte[] pdfBytes) {
		String text = extractText(pdfBytes);
		List<ParsedHolding> holdings = new ArrayList<>();

		for (String rawLine : text.split("\\r?\\n")) {
			String line = rawLine.strip();
			if (line.isEmpty() || NON_HOLDING.matcher(line).find()) {
				continue;
			}
			Matcher tickerMatch = TICKER.matcher(line);
			if (!tickerMatch.find()) {
				continue; // not a holding row
			}
			String remainder = line.substring(tickerMatch.end());

			// Pull the date out first, then strip every date-like token (any separator form) so
			// its digits (e.g. 2024-03-10 or 01/15/2023) don't get mistaken for shares/cost basis.
			LocalDate acquisitionDate = parseDate(remainder);
			String numericPart = DATE_LIKE.matcher(remainder).replaceAll(" ");

			List<String> numbers = new ArrayList<>();
			Matcher num = NUMBER.matcher(numericPart);
			while (num.find()) {
				numbers.add(num.group());
			}
			if (numbers.isEmpty()) {
				continue; // ticker with no numeric data — too weak to treat as a holding (header/noise)
			}
			holdings.add(toHolding(tickerMatch.group(1), remainder, numbers, acquisitionDate));
		}

		String message = holdings.isEmpty()
				? "No holdings could be read from this PDF. Add positions manually, or try re-uploading a different statement."
				: null;
		return new ParseResult(holdings, message);
	}

	private ParsedHolding toHolding(String ticker, String remainder, List<String> numbers,
			LocalDate acquisitionDate) {
		List<String> issues = new ArrayList<>();

		// First numeric token = shares; a second money-like token = cost basis. Currency is matched
		// by its own pattern; the date was already extracted and stripped from the number scan.
		BigDecimal shares = parseNumber(numbers.get(0));
		if (shares == null) {
			issues.add("shares: could not be read");
		} else if (shares.signum() <= 0) {
			issues.add("shares: not a positive quantity"); // implausible — flag, don't accept silently
		}

		BigDecimal costBasis = numbers.size() > 1 ? parseNumber(numbers.get(1)) : null;
		if (costBasis == null) {
			issues.add("costBasis: could not be read");
		} else if (costBasis.signum() < 0) {
			issues.add("costBasis: negative value"); // cost basis is never negative
		}

		Matcher cur = CURRENCY.matcher(remainder);
		String currency = cur.find() ? cur.group(1) : "USD";

		if (acquisitionDate == null) {
			issues.add("acquisitionDate: could not be read");
		}

		boolean needsReview = !issues.isEmpty();
		return new ParsedHolding(ticker, null, shares, costBasis, currency, acquisitionDate, null, needsReview, issues);
	}

	private static BigDecimal parseNumber(String token) {
		if (token == null) {
			return null;
		}
		try {
			return new BigDecimal(token.replace(",", ""));
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	private static LocalDate parseDate(String text) {
		Matcher m = ISO_DATE.matcher(text);
		if (!m.find()) {
			return null;
		}
		try {
			return LocalDate.parse(m.group(), ISO);
		} catch (DateTimeParseException ex) {
			return null;
		}
	}

	private static String extractText(byte[] pdfBytes) {
		try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
			return new PDFTextStripper().getText(doc);
		} catch (IOException ex) {
			throw new BadRequestException("Uploaded file is not a readable PDF");
		}
	}

	/** Outcome of parsing a statement: the holdings plus an optional user-facing note. */
	public record ParseResult(List<ParsedHolding> holdings, String message) {
	}
}
