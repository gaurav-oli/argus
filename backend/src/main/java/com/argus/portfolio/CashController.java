package com.argus.portfolio;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Per-account cash balances. Changes re-push the live portfolio snapshot so totals update at once. */
@RestController
@RequestMapping("/api/portfolio/cash")
public class CashController {

	public record CashEntry(String account, String currency, BigDecimal amount) {
	}

	public record CashView(Long id, String account, String currency, BigDecimal amount) {
	}

	private final CashService cash;
	private final LivePortfolioService live;

	public CashController(CashService cash, LivePortfolioService live) {
		this.cash = cash;
		this.live = live;
	}

	@GetMapping
	public List<CashView> list() {
		return cash.list().stream()
				.map(c -> new CashView(c.getId(), c.getAccount(), c.getCurrency(), c.getAmount())).toList();
	}

	@PutMapping
	public void set(@RequestBody CashEntry entry) {
		cash.set(entry.account(), entry.currency(), entry.amount());
		live.pushCurrent();
	}

	@DeleteMapping
	public void delete(@RequestBody CashEntry entry) {
		cash.delete(entry.account(), entry.currency());
		live.pushCurrent();
	}
}
