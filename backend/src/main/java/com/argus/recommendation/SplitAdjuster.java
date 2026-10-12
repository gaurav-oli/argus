package com.argus.recommendation;

import com.argus.technical.HistoryReadjusted;
import java.sql.Timestamp;
import java.time.LocalTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Re-bases prices recorded in old-share terms when a ticker splits (or spins off): open paper trades opened before
 * the split, and real-holding protection stops. Without it a 1-for-20 reverse split reads as a +1,900% gain and a
 * 4-for-1 split as a −75% crash that fires the stop — the same instrument mix-up that gave DOL its −153% trades.
 * Closed trades are left alone: both their prices are on the old basis, so their return is already right.
 */
@Component
public class SplitAdjuster {

	private static final Logger log = LoggerFactory.getLogger(SplitAdjuster.class);
	private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

	private final JdbcTemplate jdbc;

	public SplitAdjuster(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@EventListener
	public void on(HistoryReadjusted e) {
		double f = e.factor();
		// A split takes effect at the open: anything entered before that day's 09:30 ET was priced in old shares.
		Timestamp cutoff = Timestamp.from(e.effectiveFrom().atTime(LocalTime.of(9, 30)).atZone(NEW_YORK).toInstant());
		int trades = jdbc.update("""
				update simulated_trades set entry_price = entry_price * ?, stop_price = stop_price * ?,
				    initial_stop = initial_stop * ?, high_water = high_water * ?, target_price = target_price * ?,
				    shares = shares / ?
				where ticker = ? and status = 'OPEN' and entry_at < ?""", f, f, f, f, f, f, e.ticker(), cutoff);
		// Brokers re-base standing stop orders on a split, so the confirmed broker stop moves with it.
		int guards = jdbc.update("""
				update holding_guard set stop_price = stop_price * ?, initial_stop = initial_stop * ?,
				    start_price = start_price * ?, high_water = high_water * ?, broker_stop = broker_stop * ?
				where ticker = ? and created_at < ?""", f, f, f, f, f, e.ticker(), cutoff);
		if (trades + guards > 0) {
			log.warn("Split: re-based {} open paper trade(s) and {} holding stop(s) on {} ×{}", trades, guards, e.ticker(),
					String.format(java.util.Locale.ROOT, "%.4f", f));
		}
	}
}
