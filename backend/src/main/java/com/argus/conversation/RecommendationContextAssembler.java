package com.argus.conversation;

import com.argus.portfolio.HealthDeduction;
import com.argus.portfolio.HealthScoreResult;
import com.argus.portfolio.PortfolioSnapshot;
import com.argus.portfolio.PositionValue;
import com.argus.recommendation.Recommendation;
import com.argus.recommendation.RecommendationSignal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/**
 * Builds the grounding block for a recommendation Ask-AI chat (Story 7.1, FR-30). The block is the
 * model's <b>only</b> source of truth: the recommendation's auditable probabilities + per-agent
 * diagnostic (each signal's direction, weight, rationale) and the user's current portfolio state
 * (holdings/weights + the rule-based health score). All numbers come from the scoring engine and the
 * portfolio services — the model is told to cite them, never to invent them.
 *
 * <p>Pure and deterministic: it takes the already-loaded recommendation, portfolio snapshot, and
 * health result and renders text. Gathering those is the {@link ConversationService}'s job.
 */
@Component
public class RecommendationContextAssembler {

	private static final BigDecimal HUNDRED = new BigDecimal("100");

	public String assemble(Recommendation rec, PortfolioSnapshot portfolio, HealthScoreResult health) {
		StringBuilder b = new StringBuilder();

		b.append("=== RECOMMENDATION: ").append(rec.getTicker()).append(" ===\n");
		b.append("Direction: ").append(rec.getDirection().name())
				.append(" (bull ").append(pct(rec.getBullProbability()))
				.append(", bear ").append(pct(rec.getBearProbability()))
				.append("), confidence ").append(pct(rec.getConfidence())).append('\n');
		if (rec.getHorizon() != null) {
			b.append("Horizon: ").append(rec.getHorizon()).append('\n');
		}
		if (rec.getPriceTarget() != null) {
			b.append("Price target: ").append(money(rec.getPriceTarget())).append('\n');
		}
		b.append("Status: ").append(rec.getStatus().name()).append('\n');
		b.append("Signals (per-agent diagnostic — conflicts included):\n");
		if (rec.getSignals().isEmpty()) {
			b.append("- (no agent signals recorded)\n");
		}
		for (RecommendationSignal s : rec.getSignals()) {
			b.append("- ").append(s.getAgent()).append(": ").append(s.getDirection().name())
					.append(" (weight ").append(plain(s.getWeight())).append(")");
			if (s.getRationale() != null && !s.getRationale().isBlank()) {
				b.append(" — ").append(s.getRationale());
			}
			b.append('\n');
		}

		b.append("\n=== YOUR PORTFOLIO ===\n");
		b.append("Total value: ").append(cad(portfolio.totalValueCad()))
				.append(" (cost ").append(cad(portfolio.totalCostCad()))
				.append(", total P&L ").append(cad(portfolio.totalPnlCad())).append(")\n");
		if (portfolio.positions().isEmpty()) {
			b.append("Holdings: (none imported yet)\n");
		} else {
			b.append("Holdings:\n");
			for (PositionValue p : portfolio.positions()) {
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
		}

		b.append("Health score: ").append(health.score()).append("/100\n");
		if (health.deductions().isEmpty()) {
			b.append("- No deductions — well balanced.\n");
		}
		for (HealthDeduction d : health.deductions()) {
			b.append("- ").append(d.label()).append(": ").append(d.reason())
					.append(" (-").append(d.points()).append(")\n");
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
		return amount == null ? "n/a" : "CAD " + money(amount);
	}

	private static String money(BigDecimal amount) {
		return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
	}

	private static String plain(BigDecimal value) {
		return value == null ? "n/a" : value.stripTrailingZeros().toPlainString();
	}
}
