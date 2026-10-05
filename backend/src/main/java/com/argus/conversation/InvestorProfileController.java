package com.argus.conversation;

import com.argus.common.BadRequestException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * User-editable investor-profile endpoints (Story 7.6), session-gated under {@code /api/**}. Holds what
 * can't be inferred from statements — risk tolerance, financial goal, target, residency/home-currency
 * overrides, and free-text preferences — which then ground the portfolio chat (FR-31) and the Canadian
 * persona (FR-34). Domain-namespaced (like {@code /api/notifications/preferences}), not under /api/settings.
 */
@RestController
@RequestMapping("/api/investor-profile")
public class InvestorProfileController {

	private final InvestorProfileService service;

	public InvestorProfileController(InvestorProfileService service) {
		this.service = service;
	}

	@GetMapping
	public InvestorProfileView get() {
		return InvestorProfileView.of(service.current());
	}

	@PutMapping
	public InvestorProfileView put(@RequestBody(required = false) InvestorProfileUpdate body) {
		if (body == null) {
			throw new BadRequestException("Missing request body");
		}
		RiskTolerance risk;
		TradingHorizon horizon;
		try {
			risk = RiskTolerance.fromInput(body.riskTolerance());
		}
		catch (IllegalArgumentException ex) {
			throw new BadRequestException("Unknown risk tolerance: " + body.riskTolerance()
					+ " (expected CONSERVATIVE, BALANCED, GROWTH or AGGRESSIVE)");
		}
		try {
			horizon = TradingHorizon.fromInput(body.tradingHorizon());
		}
		catch (IllegalArgumentException ex) {
			throw new BadRequestException("Unknown trading horizon: " + body.tradingHorizon()
					+ " (expected LONG_TERM_HOLDER, ACTIVE_TRADER or MIX)");
		}
		if (body.targetAmount() != null && body.targetAmount().signum() < 0) {
			throw new BadRequestException("Target amount must be zero or positive");
		}
		String homeCurrency = trimToNull(body.homeCurrency());
		if (homeCurrency != null && homeCurrency.length() != 3) {
			throw new BadRequestException("Home currency must be a 3-letter code (e.g. CAD, USD)");
		}
		InvestorProfile saved = service.save(risk, horizon, trimToNull(body.financialGoal()), body.targetAmount(),
				body.targetDate(), trimToNull(body.residency()),
				homeCurrency == null ? null : homeCurrency.toUpperCase(), trimToNull(body.notes()));
		return InvestorProfileView.of(saved);
	}

	/** Dismiss the first-login questions without answering — never asked again. */
	@PostMapping("/skip-onboarding")
	public InvestorProfileView skipOnboarding() {
		return InvestorProfileView.of(service.skipOnboarding());
	}

	private static String trimToNull(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}

	/** The current profile; enum fields are the enum name (or null). {@code needsOnboarding} is true
	 * until this person has saved (or skipped) a profile at least once. */
	public record InvestorProfileView(String riskTolerance, String tradingHorizon, String financialGoal,
			BigDecimal targetAmount, LocalDate targetDate, String residency, String homeCurrency, String notes,
			boolean needsOnboarding, Instant updatedAt) {

		static InvestorProfileView of(InvestorProfile p) {
			return new InvestorProfileView(
					p.getRiskTolerance() == null ? null : p.getRiskTolerance().name(),
					p.getTradingHorizon() == null ? null : p.getTradingHorizon().name(),
					p.getFinancialGoal(), p.getTargetAmount(), p.getTargetDate(),
					p.getResidency(), p.getHomeCurrency(), p.getNotes(),
					p.getOnboardingCompletedAt() == null, p.getUpdatedAt());
		}
	}

	/** Full-replace update body (any field may be null to clear it). */
	public record InvestorProfileUpdate(String riskTolerance, String tradingHorizon, String financialGoal,
			BigDecimal targetAmount, LocalDate targetDate, String residency, String homeCurrency, String notes) {
	}
}
