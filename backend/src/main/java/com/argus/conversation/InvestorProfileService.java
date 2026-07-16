package com.argus.conversation;

import com.argus.portfolio.AccountMeta;
import com.argus.portfolio.AccountMetaRepository;
import com.argus.portfolio.Position;
import com.argus.portfolio.PositionRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the investor-profile sentence that grounds the portfolio chat (Story 7.2/7.5, FR-31),
 * derived from the user's ACTUAL accounts rather than a hardcoded string. Residency and home currency
 * are configurable ({@code argus.investor.*}); the account-type mix, whether a corporation is held,
 * and the currencies of held securities are read live from {@link AccountMeta} + {@link Position}. When
 * nothing has been imported yet it falls back to a sensible generic description. A persisted, fully
 * user-editable profile (risk tolerance, goals) is the remaining part of Story 7.5.
 */
@Service
public class InvestorProfileService {

	private final AccountMetaRepository accountMeta;
	private final PositionRepository positions;
	private final String residency;
	private final String homeCurrency;

	public InvestorProfileService(AccountMetaRepository accountMeta, PositionRepository positions,
			@Value("${argus.investor.residency:Canadian}") String residency,
			@Value("${argus.investor.home-currency:CAD}") String homeCurrency) {
		this.accountMeta = accountMeta;
		this.positions = positions;
		this.residency = residency;
		this.homeCurrency = homeCurrency;
	}

	/** A one-paragraph investor profile grounded in the current accounts, for the LLM context. */
	@Transactional(readOnly = true)
	public String describe() {
		List<AccountMeta> accounts = accountMeta.findAll();

		Set<String> accountTypes = new TreeSet<>();
		boolean hasCorporate = false;
		for (AccountMeta a : accounts) {
			if (a.getAccountType() != null && !a.getAccountType().isBlank()) {
				accountTypes.add(a.getAccountType());
			}
			String ownerType = a.getOwnerType();
			if ("Corporate".equalsIgnoreCase(ownerType)
					|| "Corporate".equalsIgnoreCase(a.getAccountType())) {
				hasCorporate = true;
			}
		}

		Set<String> currencies = new LinkedHashSet<>();
		for (Position p : positions.findAllByOrderByTickerAsc()) {
			if (p.getCostBasisCurrency() != null && !p.getCostBasisCurrency().isBlank()) {
				currencies.add(p.getCostBasisCurrency().toUpperCase());
			}
		}

		StringBuilder b = new StringBuilder();
		b.append(residency).append(" investor; home currency ").append(homeCurrency).append('.');

		if (!accountTypes.isEmpty()) {
			b.append(" Account types held: ").append(String.join(", ", accountTypes)).append('.');
		}
		if (hasCorporate) {
			b.append(" Includes a corporate (incorporation) account, kept separate from personal accounts.");
		}
		if (!currencies.isEmpty()) {
			b.append(" Holds ").append(joinCurrencies(currencies)).append("-denominated securities.");
		}
		// Tax context is relevant whenever registered accounts and/or US holdings are present; keep it
		// generic but only mention what applies.
		boolean registered = accountTypes.stream().anyMatch(t -> t.equalsIgnoreCase("TFSA")
				|| t.equalsIgnoreCase("RRSP") || t.equalsIgnoreCase("RRIF") || t.equalsIgnoreCase("RESP")
				|| t.equalsIgnoreCase("LIRA"));
		if (registered || currencies.contains("USD")) {
			b.append(" Tax context matters");
			if (registered) {
				b.append(" (registered-account contribution room");
				if (currencies.contains("USD")) {
					b.append("; US withholding tax on US-listed dividends");
				}
				b.append(')');
			} else {
				b.append(" (US withholding tax on US-listed dividends)");
			}
			b.append('.');
		}
		return b.toString();
	}

	private static String joinCurrencies(Set<String> currencies) {
		if (currencies.size() == 1) {
			return currencies.iterator().next();
		}
		return String.join(" and ", currencies);
	}
}
