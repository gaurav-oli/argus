package com.argus.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.TestcontainersConfiguration;
import com.argus.email.EmailSender;
import com.argus.push.PushService;
import com.argus.security.AppUser;
import com.argus.security.AppUserRepository;
import com.argus.technical.LivePriceService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Real-holdings protection end to end: a stop is set when protection begins, a broken stop or an AVOID call raises an
 * alert, a reminder follows at 5 minutes, and at 15 minutes with no answer Argus decides, records it, and escalates.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class HoldingGuardIntegrationTest {

	@Autowired
	HoldingGuard guard;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	AppUserRepository users;

	@MockitoBean
	LivePriceService prices;

	@MockitoBean
	PushService push;

	@MockitoBean
	EmailSender email;

	private long userId;
	private Instant t0;

	@BeforeEach
	void setUp() {
		jdbc.update("delete from guard_alert");
		jdbc.update("delete from holding_guard");
		jdbc.update("delete from positions");
		AppUser u = users.save(new AppUser("sub-guard-" + System.nanoTime(), "holder" + System.nanoTime() + "@example.com", "Holder", null, false));
		userId = u.getId();
		guard.setEnabled(userId, true); // protection is opt-in
		jdbc.update("insert into positions (ticker, shares, cost_basis, user_id, account) values ('TSLA', 100, 30000, ?, 'TFSA')", userId);
		when(email.configured()).thenReturn(true);
		t0 = Instant.now();
	}

	private void price(String p) {
		when(prices.latestPrice("TSLA")).thenReturn(Optional.of(new BigDecimal(p)));
	}

	private Map<String, Object> onlyAlert() {
		return jdbc.queryForMap("select * from guard_alert where user_id = ?", userId);
	}

	@Test
	void protectionStartsWithAStopMeasuredFromTodaysPrice() {
		price("400");
		guard.scan(t0);

		BigDecimal stop = jdbc.queryForObject("select stop_price from holding_guard where user_id = ? and ticker = 'TSLA'", BigDecimal.class, userId);
		assertEquals(0, new BigDecimal("360").compareTo(stop), "a 10% stop (no chart history yet) below today's 400");
	}

	@Test
	void aBrokenStopAlertsRemindsThenArgusDecidesAndEscalates() {
		price("400");
		guard.scan(t0);
		price("355"); // through the 360 stop
		guard.scan(t0.plusSeconds(300));

		Map<String, Object> a = onlyAlert();
		assertEquals("STOP_BROKEN", a.get("kind"));
		assertEquals("SELL", a.get("recommendation"));
		verify(push).sendToUser(eq(userId), startsWith("⚠ Consider selling TSLA"), anyString(), eq("/portfolio"));

		Instant created = ((java.sql.Timestamp) a.get("created_at")).toInstant();
		guard.escalate(created.plus(Duration.ofMinutes(6)));
		verify(push).sendToUser(eq(userId), startsWith("Reminder:"), anyString(), anyString());

		guard.escalate(created.plus(Duration.ofMinutes(16)));
		a = onlyAlert();
		assertEquals("DECIDED", a.get("status"));
		assertEquals("ARGUS", a.get("decided_by"));
		assertEquals("SELL", a.get("decision"));
		verify(push).sendToUser(eq(userId), contains("Argus decided: SELL TSLA in TFSA"), anyString(), anyString());
		verify(email).send(anyString(), contains("Argus decided: SELL TSLA"), anyString());

		guard.escalate(created.plus(Duration.ofMinutes(30)));
		verify(email, times(1)).send(anyString(), anyString(), anyString()); // decided once, never again
	}

	@Test
	void anAnswerWithinFifteenMinutesIsTheDecisionArgusNeverOverridesIt() {
		price("400");
		guard.scan(t0);
		price("355");
		guard.scan(t0.plusSeconds(300));
		long id = ((Number) onlyAlert().get("id")).longValue();

		assertTrue(guard.decideByUser(userId, id, "HOLD"));
		guard.escalate(t0.plus(Duration.ofMinutes(40)));

		Map<String, Object> a = onlyAlert();
		assertEquals("USER", a.get("decided_by"));
		assertEquals("HOLD", a.get("decision"));
		verify(email, never()).send(anyString(), anyString(), anyString());
	}

	@Test
	void aRisingStockRaisesItsStopAndSaysSo() {
		price("400");
		guard.scan(t0);
		price("440"); // +10% (> 1 × the 3% default ATR): the stop trails up
		guard.scan(t0.plus(Duration.ofDays(2)));

		Map<String, Object> a = onlyAlert();
		assertEquals("STOP_RAISED", a.get("kind"));
		assertEquals("TIGHTEN", a.get("recommendation"));
		BigDecimal stop = jdbc.queryForObject("select stop_price from holding_guard where user_id = ? and ticker = 'TSLA'", BigDecimal.class, userId);
		assertTrue(stop.doubleValue() > 400, "at least breakeven from where protection started: " + stop);
	}

	@Test
	void aConfidentAvoidCallOnAHoldingIsASellAlert() {
		price("400");
		guard.scan(t0);
		jdbc.update("""
				insert into recommendations (ticker, direction, bull_probability, bear_probability, confidence, status, action,
				  conviction_score, created_at, thesis)
				values ('TSLA', 'BEARISH', 0.2, 0.8, 0.7, 'PENDING', 'STRONG_AVOID', 82, now(), 'Deliveries collapsing')""");
		guard.scan(t0.plusSeconds(300));

		Map<String, Object> a = onlyAlert();
		assertEquals("CALL_REVERSED", a.get("kind"));
		assertEquals("SELL", a.get("recommendation"));
		assertTrue(((String) a.get("detail")).contains("Deliveries collapsing"));
		jdbc.update("delete from recommendations where ticker = 'TSLA'");
	}

	@Test
	void aDecisionIsScoredAWeekLater() {
		price("400");
		guard.scan(t0);
		price("355");
		guard.scan(t0.plusSeconds(300));
		long id = ((Number) onlyAlert().get("id")).longValue();
		guard.decideByUser(userId, id, "SELL");
		jdbc.update("update guard_alert set decided_at = now() - interval '8 days' where id = ?", id);
		price("320"); // it kept falling: selling saved ~10%

		guard.recordOutcomes(Instant.now());

		BigDecimal outcome = jdbc.queryForObject("select outcome_pct from guard_alert where id = ?", BigDecimal.class, id);
		assertTrue(outcome.doubleValue() < -9, "price fell after the sell: " + outcome);
		verify(push, atLeastOnce()).sendToUser(anyLong(), anyString(), anyString(), anyString());
	}

	@Test
	void someoneWhoHasntOptedInIsNeverWatchedOrEmailed() {
		guard.setEnabled(userId, false);
		price("400");
		guard.scan(t0);
		price("300");
		guard.scan(t0.plusSeconds(300));

		assertEquals(0, jdbc.queryForObject("select count(*) from guard_alert where user_id = ?", Integer.class, userId));
		verify(push, never()).sendToUser(anyLong(), anyString(), anyString(), anyString());
	}

	private void call(String action, int conviction) {
		jdbc.update("insert into recommendations (ticker, direction, bull_probability, bear_probability, confidence, status, action, "
				+ "conviction_score, created_at) values ('TSLA', 'BEARISH', 0.2, 0.8, 0.7, 'PENDING', ?, ?, now())", action, conviction);
	}

	@Test
	void theSameAvoidCallRepeatedAllDayIsOneAlertNotOneEach() {
		price("400");
		guard.scan(t0);
		call("STRONG_AVOID", 83);
		guard.scan(t0.plusSeconds(300));
		long first = ((Number) onlyAlert().get("id")).longValue();
		guard.decideByUser(userId, first, "HOLD");

		for (int i = 0; i < 6; i++) { // Agent 5 re-reviews the stock again and again — the opinion doesn't change
			call("STRONG_AVOID", 82 + i);
			guard.scan(t0.plusSeconds(600 + i * 60));
		}
		assertEquals(1, jdbc.queryForObject("select count(*) from guard_alert where user_id = ? and kind = 'CALL_REVERSED'", Integer.class, userId));

		call("WATCH", 30); // the call softens…
		call("AVOID", 70); // …then turns against the holding again: that IS new
		guard.scan(t0.plusSeconds(1200));
		assertEquals(2, jdbc.queryForObject("select count(*) from guard_alert where user_id = ? and kind = 'CALL_REVERSED'", Integer.class, userId));
		jdbc.update("delete from recommendations where ticker = 'TSLA'");
	}

	@Test
	void aStockHeldInSeveralAccountsIsOneAlertListingThemAll() {
		jdbc.update("insert into positions (ticker, shares, cost_basis, user_id, account) values ('TSLA', 10, 3000, ?, 'RRSP'), "
				+ "('TSLA', 5, 1500, ?, 'Cash')", userId, userId);
		price("400");
		guard.scan(t0);
		price("355");
		guard.scan(t0.plusSeconds(300));

		assertEquals(1, jdbc.queryForObject("select count(*) from guard_alert where user_id = ?", Integer.class, userId));
		assertEquals("Cash, RRSP, TFSA", onlyAlert().get("account"));
		verify(push, times(1)).sendToUser(eq(userId), startsWith("⚠ Consider selling TSLA"), anyString(), anyString());
	}

	@Test
	void aRaiseYourStopAlertIsOnePushAndIsSettledQuietly() {
		price("400");
		guard.scan(t0);
		price("440");
		guard.scan(t0.plus(Duration.ofDays(2)));
		Instant created = ((java.sql.Timestamp) onlyAlert().get("created_at")).toInstant();

		guard.escalate(created.plus(Duration.ofMinutes(6)));
		guard.escalate(created.plus(Duration.ofMinutes(16)));

		assertEquals("DECIDED", onlyAlert().get("status"));
		verify(push, times(1)).sendToUser(eq(userId), anyString(), anyString(), anyString()); // just the alert itself
		verify(email, never()).send(anyString(), anyString(), anyString());
	}
}
