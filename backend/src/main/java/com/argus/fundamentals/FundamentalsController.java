package com.argus.fundamentals;

import com.argus.common.NotFoundException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Agent 12's fundamentals for the Intelligence page, session-gated under {@code /api/fundamentals}. Reads stored snapshots only. */
@RestController
@RequestMapping("/api/fundamentals")
public class FundamentalsController {

	private final FundamentalsService service;

	public FundamentalsController(FundamentalsService service) {
		this.service = service;
	}

	/** Every analysed company, the most decisive score (either direction) first. */
	@GetMapping
	public List<Row> list() {
		return service.all().stream().filter(Fundamentals::applicable).map(Row::from)
				.sorted(Comparator.comparingDouble((Row r) -> Math.abs(r.score())).reversed()).toList();
	}

	@GetMapping("/{ticker}")
	public Fundamentals detail(@PathVariable String ticker) {
		String t = ticker.trim().toUpperCase(Locale.ROOT);
		// Never block the page on ~10 Finnhub calls: serve what's stored and refresh a stale one behind it,
		// so the page's next poll (or the next visit) gets the new snapshot.
		service.refreshInBackgroundIfOlderThan(t, FundamentalsService.MISSED_AFTER);
		return service.latest(t).orElseThrow(() -> new NotFoundException("Fundamentals", t));
	}

	public record Row(String ticker, String name, String industry, double score, String bias, String valuationVerdict, Double impliedGrowthPct,
			Double deliveredGrowthPct, Double peerPePremiumPct, List<String> notes) {

		static Row from(Fundamentals f) {
			return new Row(f.ticker(), f.name(), f.industry(), f.score(), f.bias(), f.valuation() == null ? null : f.valuation().verdict(),
					f.valuation() == null ? null : f.valuation().impliedGrowthPct(), f.valuation() == null ? null : f.valuation().deliveredGrowthPct(),
					f.peers() == null ? null : f.peers().premiumPct(), f.notes().stream().limit(4).toList());
		}
	}
}
