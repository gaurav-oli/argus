package com.argus.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Captures and serves the daily portfolio-value series for the chart (Story 3.6, FR-4). One point
 * per day (idempotent upsert keyed by date); a scheduled daily job records the current live total,
 * and {@link #history} returns the points in a selected window. History accrues over time — it's
 * naturally sparse until the daily capture has run for a while.
 */
@Service
public class PortfolioHistoryService {

	private static final Logger log = LoggerFactory.getLogger(PortfolioHistoryService.class);
	private static final ZoneId TORONTO = ZoneId.of("America/Toronto");

	private final PortfolioValuePointRepository points;
	private final LivePortfolioService live;

	public PortfolioHistoryService(PortfolioValuePointRepository points, LivePortfolioService live) {
		this.points = points;
		this.live = live;
	}

	/** Upsert today's total portfolio CAD value. Idempotent: a second call the same day updates it. */
	@Transactional
	public void capture() {
		BigDecimal value = live.currentSnapshot().totalValueCad();
		// Skip days with no priced value (no feed / empty portfolio) rather than recording a
		// misleading 0 that would crater the chart.
		if (value == null || value.signum() <= 0) {
			return;
		}
		LocalDate today = LocalDate.now(TORONTO);
		points.findByCapturedOn(today)
				.ifPresentOrElse(p -> {
					p.setTotalValueCad(value);
					points.save(p);
				}, () -> points.save(new PortfolioValuePoint(today, value)));
	}

	@Transactional(readOnly = true)
	public List<ValuePoint> history(HistoryRange range) {
		LocalDate from = range.startFrom(LocalDate.now(TORONTO));
		List<PortfolioValuePoint> rows = (from == null)
				? points.findAllByOrderByCapturedOnAsc()
				: points.findByCapturedOnGreaterThanEqualOrderByCapturedOnAsc(from);
		return rows.stream().map(p -> new ValuePoint(p.getCapturedOn(), p.getTotalValueCad())).toList();
	}

	/** Daily end-of-session capture (16:30 ET). Never throws out of the scheduler. */
	@Scheduled(cron = "0 30 16 * * *", zone = "America/New_York")
	public void scheduledCapture() {
		try {
			capture();
		} catch (RuntimeException ex) {
			log.warn("Scheduled portfolio-value capture failed: {}", ex.getMessage());
		}
	}
}
