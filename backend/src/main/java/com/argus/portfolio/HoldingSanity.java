package com.argus.portfolio;

import com.argus.marketdata.ListingResolver;
import com.argus.marketdata.YahooChartClient;
import com.argus.security.AppUserRepository;
import com.argus.security.CurrentUserContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The last check on a parsed statement before it can touch the portfolio — every parser's output passes
 * through here. Built after a statement import (2026-10-07) wrote 57 bad rows: currency codes, a GST number,
 * the holder's surname and a company name as "tickers", plus duplicates of real holdings that had no bank or
 * account to reconcile against.
 *
 * <ul>
 *   <li><b>Dropped</b> — a "ticker" that is a currency code, a tax/statement word, or part of the account
 *       holder's own name. These are never holdings.</li>
 *   <li><b>Held for review</b> (never auto-applied) — a holding of an already-held ticker with no bank or
 *       account to tie it to, one that repeats an existing holding (same ticker and shares), or a symbol with no market quote
 *       (e.g. "TESLA" for TSLA).</li>
 * </ul>
 */
@Component
public class HoldingSanity {

	/** Words that turn up in a statement's ticker position but are never a holding. */
	static final Set<String> NOT_A_TICKER = Set.of(
			"USD", "CAD", "EUR", "GBP", "JPY", "AUD", "CHF", "CNY", "HKD", "INR", "MXN", "NZD", "SGD",
			"GST", "HST", "PST", "QST", "TAX", "TAXES", "FEE", "FEES", "CASH", "TOTAL", "SUBTOTAL", "NET", "BAL",
			"BALANCE", "ACCT", "ACCOUNT", "PAGE", "DATE", "QTY", "PRICE", "VALUE", "BOOK", "COST", "MARKET",
			"INC", "LTD", "CORP", "CO", "THE", "AND", "OR", "OF", "FOR", "TO", "FROM", "NA", "N", "Y");

	/** A clean statement, plus what was dropped or flagged (empty when nothing was). */
	public record Checked(StatementParser.ParseResult result, List<String> problems) {
		public boolean clean() {
			return problems.isEmpty();
		}
	}

	private final PositionRepository positions;
	private final AppUserRepository users;
	private final YahooChartClient yahoo;
	private final ListingResolver listings;

	/** Off in tests, so staging a statement never makes a network call from a test JVM. */
	@Value("${argus.import.verify-symbols:true}")
	private boolean verifySymbols = true;

	public HoldingSanity(PositionRepository positions, AppUserRepository users, YahooChartClient yahoo,
			ListingResolver listings) {
		this.positions = positions;
		this.users = users;
		this.yahoo = yahoo;
		this.listings = listings;
	}

	public Checked check(StatementParser.ParseResult result, String institution) {
		Set<String> nameTokens = holderNameTokens();
		List<Position> existing = positions.findAllByOrderByTickerAsc(); // this person's own, by tenant
		Set<String> known = new java.util.HashSet<>();
		existing.forEach(p -> known.add(p.getTicker().toUpperCase(Locale.ROOT)));
		Map<String, Boolean> quoted = new HashMap<>();

		List<String> problems = new ArrayList<>();
		List<ParsedHolding> kept = new ArrayList<>();
		for (ParsedHolding h : result.holdings()) {
			String t = h.ticker() == null ? "" : h.ticker().trim().toUpperCase(Locale.ROOT);
			if (t.isEmpty() || NOT_A_TICKER.contains(t) || nameTokens.contains(t)) {
				problems.add("dropped \"" + h.ticker() + "\" — a currency, tax or statement word, or a name, not a ticker");
				continue;
			}
			List<String> issues = new ArrayList<>(h.issues());
			if (institution == null && (h.account() == null || h.account().isBlank()) && known.contains(t)) {
				// Only a risk when the portfolio already holds this ticker: with nothing to reconcile against, the
				// import would add a second copy rather than update the existing one.
				issues.add("no bank or account to match it against, and " + t + " is already held — it could duplicate it");
			}
			if (duplicates(h, t, existing)) {
				issues.add("same ticker and share count as a holding already in the portfolio — possibly a duplicate");
			}
			if (!known.contains(t) && !hasQuote(t, quoted)) {
				issues.add("\"" + t + "\" has no market quote — check the ticker (e.g. TSLA, not TESLA)");
			}
			if (issues.size() > h.issues().size()) {
				problems.add(t + ": " + String.join("; ", issues.subList(h.issues().size(), issues.size())));
				kept.add(new ParsedHolding(h.ticker(), h.companyName(), h.shares(), h.costBasis(), h.costBasisCurrency(),
						h.acquisitionDate(), h.account(), true, issues));
			}
			else {
				kept.add(h);
			}
		}
		return new Checked(new StatementParser.ParseResult(kept, result.cash(), result.accounts(), result.message()), problems);
	}

	private boolean duplicates(ParsedHolding h, String ticker, List<Position> existing) {
		if (h.shares() == null) {
			return false;
		}
		for (Position p : existing) {
			if (!ticker.equalsIgnoreCase(p.getTicker()) || p.getShares() == null) {
				continue;
			}
			boolean sameShares = p.getShares().compareTo(h.shares()) == 0;
			boolean sameAccount = h.account() != null && h.account().equalsIgnoreCase(p.getAccount());
			if (sameShares && !sameAccount) {
				return true; // the same lot arriving again without the account that would have matched it
			}
		}
		return false;
	}

	private boolean hasQuote(String ticker, Map<String, Boolean> cache) {
		if (!verifySymbols) {
			return true;
		}
		return cache.computeIfAbsent(ticker, t -> {
			try {
				for (String symbol : listings.yahooSymbols(t, true)) { // the .TO listing too: a TSX-only holding is real
					var s = yahoo.fetch(symbol, "5d");
					if (s.isPresent() && s.get().livePrice() != null && s.get().livePrice().compareTo(BigDecimal.ZERO) > 0) {
						return true;
					}
				}
				return false;
			}
			catch (RuntimeException ex) {
				return true; // can't check right now — don't block an import on the quote service
			}
		});
	}

	/** The signed-in holder's name split into upper-case words ("Gaurav Oli" → GAURAV, OLI). */
	private Set<String> holderNameTokens() {
		Long id = CurrentUserContext.get();
		if (id == null) {
			return Set.of();
		}
		return users.findById(id).map(u -> {
			Set<String> tokens = new java.util.HashSet<>();
			for (String part : (u.getName() == null ? "" : u.getName()).split("[^A-Za-z]+")) {
				if (part.length() >= 2) {
					tokens.add(part.toUpperCase(Locale.ROOT));
				}
			}
			return tokens;
		}).orElse(Set.of());
	}
}
