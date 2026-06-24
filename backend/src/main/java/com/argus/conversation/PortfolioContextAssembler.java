package com.argus.conversation;

import com.argus.calendar.CalendarEvent;
import com.argus.portfolio.HealthDeduction;
import com.argus.portfolio.HealthScoreResult;
import com.argus.portfolio.PortfolioSnapshot;
import com.argus.portfolio.PositionValue;
import com.argus.recommendation.Recommendation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Builds the grounding block for the dashboard portfolio Ask-AI chat (Story 7.2, FR-31). The block is
 * the model's only source of truth: all holdings + weights, the rule-based health score + deductions,
 * upcoming calendar events, the most recent recommendations, and the investor profile. The model is
 * told to cite these figures, never invent them.
 *
 * <p>Pure and deterministic: it takes already-loaded data + {@code today} (for "in N days" phrasing)
 * and renders text. Gathering/capping the inputs is the {@link ConversationService}'s job.
 */
@Component
public class PortfolioContextAssembler {

	private static final BigDecimal HUNDRED = new BigDecimal("100");
	/** Cap the (typically largest) holdings section so a big portfolio + history stays within the prompt budget. */
	private static final int MAX_HOLDINGS = 25;

	public String assemble(PortfolioSnapshot portfolio, HealthScoreResult health,
			List<CalendarEvent> upcomingEvents, List<Recommendation> recentRecs, String investorProfile,
			LocalDate today) {
		StringBuilder b = new StringBuilder();

		b.append("=== INVESTOR PROFILE ===\n").append(investorProfile).append("\n\n");

		b.append("=== PORTFOLIO ===\n");
		b.append("Total value: ").append(cad(portfolio.totalValueCad()))
				.append(" (cost ").append(cad(portfolio.totalCostCad()))
				.append(", total P&L ").append(cad(portfolio.totalPnlCad())).append(")\n");
		if (portfolio.positions().isEmpty()) {
			b.append("Holdings: (none imported yet)\n");
		} else {
			List<PositionValue> holdings = portfolio.positions();
			List<PositionValue> shown = holdings.stream()
					.sorted(Comparator.comparing(
							(PositionValue p) -> p.weightPercent() == null ? BigDecimal.valueOf(-1) : p.weightPercent())
							.reversed())
					.limit(MAX_HOLDINGS)
					.toList();
			b.append("Holdings");
			if (holdings.size() > MAX_HOLDINGS) {
				b.append(" (top ").append(MAX_HOLDINGS).append(" of ").append(holdings.size()).append(" by weight)");
			}
			b.append(":\n");
			for (PositionValue p : shown) {
				b.append("- ").append(p.ticker());
				if (p.companyName() != null && !p.companyName().isBlank()) {
					b.append(" (").append(p.companyName()).append(')');
				}
				b.append(": ");
				b.append(p.weightPercent() != null ? plain(p.weightPercent()) + "% of portfolio" : "weight n/a");
				if (p.cadMarketValue() != null) {
					b.append(", value ").append(cad(p.cadMarketValue()));
				}
				if (p.cadPnl() != null) {
					b.append(", P&L ").append(cad(p.cadPnl()));
				}
				b.append('\n');
			}
			if (holdings.size() > MAX_HOLDINGS) {
				b.append("- …and ").append(holdings.size() - MAX_HOLDINGS).append(" smaller holdings\n");
			}
		}

		b.append("\n=== HEALTH SCORE ===\n").append(health.score()).append("/100\n");
		if (health.deductions().isEmpty()) {
			b.append("- No deductions — well balanced.\n");
		}
		for (HealthDeduction d : health.deductions()) {
			b.append("- ").append(d.label()).append(": ").append(d.reason())
					.append(" (-").append(d.points()).append(")\n");
		}

		b.append("\n=== UPCOMING EVENTS ===\n");
		if (upcomingEvents.isEmpty()) {
			b.append("- (none in the window)\n");
		}
		for (CalendarEvent e : upcomingEvents) {
			long days = today == null || e.getEventDate() == null
					? -1 : ChronoUnit.DAYS.between(today, e.getEventDate());
			b.append("- ").append(e.getType().name());
			if (e.getTicker() != null && !e.getTicker().isBlank()) {
				b.append(' ').append(e.getTicker());
			}
			if (e.getTitle() != null && !e.getTitle().isBlank()) {
				b.append(" — ").append(e.getTitle());
			}
			if (e.getEventDate() != null) {
				b.append(" (").append(e.getEventDate());
				if (days == 0) {
					b.append(", today");
				} else if (days > 0) {
					b.append(", in ").append(days).append(days == 1 ? " day" : " days");
				}
				b.append(')');
			}
			b.append('\n');
		}

		b.append("\n=== RECENT RECOMMENDATIONS ===\n");
		if (recentRecs.isEmpty()) {
			b.append("- (none yet)\n");
		}
		for (Recommendation r : recentRecs) {
			b.append("- ").append(r.getTicker()).append(": ").append(r.getDirection().name())
					.append(" (bull ").append(pct(r.getBullProbability()))
					.append(", bear ").append(pct(r.getBearProbability()))
					.append(", confidence ").append(pct(r.getConfidence()))
					.append("), status ").append(r.getStatus().name()).append('\n');
		}

		return b.toString();
	}

	/** A 0–1 probability/confidence as a whole-number percentage, e.g. {@code 0.65 → "65%"}. */
	private static String pct(BigDecimal zeroToOne) {
		if (zeroToOne == null) {
			return "n/a";
		}
		return zeroToOne.multiply(HUNDRED).setScale(0, RoundingMode.HALF_UP).toPlainString() + "%";
	}

	private static String cad(BigDecimal amount) {
		return amount == null ? "n/a" : "CAD " + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
	}

	private static String plain(BigDecimal value) {
		return value == null ? "n/a" : value.stripTrailingZeros().toPlainString();
	}
}
