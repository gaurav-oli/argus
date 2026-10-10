package com.argus.recommendation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S-B5 — the paper book per ticker, for the Intelligence thesis board: is the Investor in this name right
 * now (which way, how many legs, how much, live P&L), and how has it done here before (closed trades, wins,
 * realized P&L, last result). Folded from the ledger rows, so it shows exactly what the Investor record does.
 * Pure.
 */
public final class PaperByTicker {

	private PaperByTicker() {
	}

	/**
	 * @param openDirection  BULLISH / BEARISH while legs are open; null when flat
	 * @param unrealizedPct  amount-weighted live return of the open legs; null when flat or unpriced
	 * @param unrealizedPnl  live P&L of the open legs in dollars; null when flat or unpriced
	 * @param lastResult     WON / LOST for the most recent close; null when nothing has closed
	 */
	public record View(String ticker, String openDirection, int openLegs, BigDecimal openAmount, BigDecimal unrealizedPct,
			BigDecimal unrealizedPnl, int closedTrades, int wins, BigDecimal realizedPnl, Instant lastClosedAt, String lastResult) {
	}

	public static List<View> fold(List<PaperInvestorService.LedgerRow> rows) {
		Map<String, List<PaperInvestorService.LedgerRow>> byTicker = new LinkedHashMap<>();
		for (var r : rows) byTicker.computeIfAbsent(r.ticker(), k -> new ArrayList<>()).add(r);
		List<View> out = new ArrayList<>();
		for (var e : byTicker.entrySet()) {
			List<PaperInvestorService.LedgerRow> open = e.getValue().stream().filter(r -> "OPEN".equals(r.status())).toList();
			List<PaperInvestorService.LedgerRow> closed = e.getValue().stream().filter(r -> "CLOSED".equals(r.status())).toList();

			BigDecimal openAmount = open.stream().map(PaperInvestorService.LedgerRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
			BigDecimal pricedAmount = BigDecimal.ZERO;
			BigDecimal livePnl = BigDecimal.ZERO;
			for (var r : open) {
				if (r.unrealizedPct() == null) continue;
				pricedAmount = pricedAmount.add(r.amount());
				livePnl = livePnl.add(r.amount().multiply(r.unrealizedPct()).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
			}
			boolean priced = pricedAmount.signum() > 0;
			BigDecimal unrealizedPct = priced
					? livePnl.multiply(BigDecimal.valueOf(100)).divide(pricedAmount, 2, RoundingMode.HALF_UP) : null;

			var last = closed.stream().filter(r -> r.closedAt() != null)
					.max(Comparator.comparing(PaperInvestorService.LedgerRow::closedAt)).orElse(null);
			out.add(new View(e.getKey(), open.isEmpty() ? null : open.get(0).direction(), open.size(),
					open.isEmpty() ? null : openAmount.setScale(2, RoundingMode.HALF_UP), unrealizedPct,
					priced ? livePnl.setScale(2, RoundingMode.HALF_UP) : null, closed.size(),
					(int) closed.stream().filter(r -> Boolean.TRUE.equals(r.won())).count(),
					closed.isEmpty() ? null : closed.stream().map(r -> r.pnl() == null ? BigDecimal.ZERO : r.pnl())
							.reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP),
					last == null ? null : last.closedAt(),
					last == null || last.won() == null ? null : last.won() ? "WON" : "LOST"));
		}
		out.sort(Comparator.comparing(View::ticker));
		return out;
	}
}
