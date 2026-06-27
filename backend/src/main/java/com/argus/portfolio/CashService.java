package com.argus.portfolio;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Manages per-account cash balances and their CAD total (folded into the live portfolio value). */
@Service
public class CashService {

	private final CashBalanceRepository cash;

	public CashService(CashBalanceRepository cash) {
		this.cash = cash;
	}

	@Transactional(readOnly = true)
	public List<CashBalance> list() {
		return cash.findAllByOrderByAccountAsc();
	}

	/** Set the cash for an account+currency (creates or updates). Removes the row when set to 0. */
	@Transactional
	public void set(String account, String currency, BigDecimal amount) {
		String cur = currency == null ? "CAD" : currency.trim().toUpperCase();
		BigDecimal amt = amount == null ? BigDecimal.ZERO : amount;
		CashBalance existing = cash.findByAccountAndCurrency(account, cur).orElse(null);
		if (amt.signum() <= 0) {
			if (existing != null) {
				cash.delete(existing);
			}
			return;
		}
		if (existing == null) {
			cash.save(new CashBalance(account, cur, amt));
		}
		else {
			existing.setAmount(amt);
			cash.save(existing);
		}
	}

	@Transactional
	public void delete(String account, String currency) {
		cash.findByAccountAndCurrency(account, currency == null ? "CAD" : currency.trim().toUpperCase())
				.ifPresent(cash::delete);
	}

	/** Total cash in CAD: CAD balances at face value, USD balances converted at {@code usdCad}. */
	@Transactional(readOnly = true)
	public BigDecimal totalCad(BigDecimal usdCad) {
		BigDecimal total = BigDecimal.ZERO;
		for (CashBalance c : cash.findAll()) {
			if ("CAD".equalsIgnoreCase(c.getCurrency())) {
				total = total.add(c.getAmount());
			}
			else if (usdCad != null) {
				total = total.add(c.getAmount().multiply(usdCad));
			}
		}
		return total;
	}
}
