package com.argus.sec;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Agent 4 read endpoint — recent insider (Form 4) activity. Session-gated like all /api. */
@RestController
@RequestMapping("/api/sec")
public class SecController {

	/** One insider transaction for the feed. */
	public record InsiderView(String ticker, String insiderName, String insiderTitle, String transactionType,
			BigDecimal shares, BigDecimal value, LocalDate filedAt, String url) {
	}

	private final SecFilingRepository filings;

	public SecController(SecFilingRepository filings) {
		this.filings = filings;
	}

	@GetMapping("/insider")
	public List<InsiderView> insider() {
		return filings.findTop50ByOrderByFiledAtDesc().stream()
				.map(f -> new InsiderView(f.getTicker(), f.getInsiderName(), f.getInsiderTitle(),
						f.getTransactionType(), f.getShares(), f.getValue(), f.getFiledAt(), f.getUrl()))
				.toList();
	}
}
