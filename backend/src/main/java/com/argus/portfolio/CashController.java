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

	public record CashView(Long id, String account, String currency, BigDecimal amount,
			String accountName, String ownerType, String ownerName) {
	}

	private final CashService cash;
	private final LivePortfolioService live;
	private final AccountMetaRepository accountMeta;

	public CashController(CashService cash, LivePortfolioService live, AccountMetaRepository accountMeta) {
		this.cash = cash;
		this.live = live;
		this.accountMeta = accountMeta;
	}

	@GetMapping
	public List<CashView> list() {
		return cash.list().stream().map(this::toView).toList();
	}

	private CashView toView(CashBalance c) {
		AccountLabels.Parsed label = AccountLabels.parse(c.getAccount());
		AccountMeta meta = accountMeta.findByAccount(c.getAccount()).stream().findFirst().orElse(null);
		return new CashView(c.getId(), c.getAccount(), c.getCurrency(), c.getAmount(),
				label.displayName(), meta == null ? null : meta.getOwnerType(),
				meta == null ? null : meta.getOwnerName());
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
