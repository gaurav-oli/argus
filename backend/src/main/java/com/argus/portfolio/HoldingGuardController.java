package com.argus.portfolio;

import com.argus.common.NotFoundException;
import com.argus.security.CurrentUserService;
import com.argus.technical.LivePriceService;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The signed-in person's real-holdings protection ({@link HoldingGuard}): their stops, open alerts, and past decisions. */
@RestController
@RequestMapping("/api/guard")
public class HoldingGuardController {

	private static final Set<String> DECISIONS = Set.of("SELL", "TIGHTEN", "HOLD");

	private final JdbcTemplate jdbc;
	private final CurrentUserService currentUser;
	private final HoldingGuard guard;
	private final LivePriceService prices;

	public HoldingGuardController(JdbcTemplate jdbc, CurrentUserService currentUser, HoldingGuard guard, LivePriceService prices) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.guard = guard;
		this.prices = prices;
	}

	/** {@code needsBrokerUpdate}: the recommended stop differs from what the person confirmed setting at their broker. */
	public record GuardedHolding(String ticker, String account, BigDecimal price, BigDecimal stop, BigDecimal startPrice,
			BigDecimal brokerStop, Instant brokerConfirmedAt, Instant stopChangedAt, boolean needsBrokerUpdate) {
	}

	/** {@code decideAt} is when Argus decides for the person if they haven't. */
	public record GuardAlert(long id, String ticker, String account, String kind, String recommendation, String detail,
			BigDecimal price, BigDecimal stop, String status, Instant createdAt, Instant decideAt, String decidedBy,
			String decision, Instant decidedAt, BigDecimal outcomePct) {
	}

	public record GuardView(boolean enabled, List<GuardedHolding> holdings, List<GuardAlert> open, List<GuardAlert> history) {
	}

	/** Turn protection on or off for yourself. */
	@PostMapping("/enabled")
	public void setEnabled(@RequestBody EnabledBody body, HttpServletRequest request) {
		guard.setEnabled(currentUser.require(request).getId(), body != null && body.enabled());
	}

	public record EnabledBody(boolean enabled) {
	}

	@GetMapping
	public GuardView view(HttpServletRequest request) {
		long user = currentUser.require(request).getId();
		List<GuardedHolding> holdings = jdbc.query("""
				select ticker, account, stop_price, start_price, broker_stop, broker_confirmed_at, stop_changed_at
				from holding_guard where user_id = ? order by ticker, account""", (rs, n) -> {
					BigDecimal stop = rs.getBigDecimal(3);
					BigDecimal broker = rs.getBigDecimal(5);
					return new GuardedHolding(rs.getString(1), rs.getString(2), prices.latestPrice(rs.getString(1)).orElse(null), stop,
							rs.getBigDecimal(4), broker, instant(rs.getTimestamp(6)), instant(rs.getTimestamp(7)),
							broker == null || broker.compareTo(stop) != 0);
				}, user);
		return new GuardView(guard.isEnabled(user), holdings, alerts(user, "status = 'OPEN'"),
				alerts(user, "status = 'DECIDED' and decided_at > now() - interval '30 days'"));
	}

	/** The person's answer to an open alert: SELL (I sold), TIGHTEN (I moved my broker stop) or HOLD (I'm keeping it). */
	@PostMapping("/alerts/{id}/decide")
	public void decide(@PathVariable long id, @RequestBody DecisionBody body, HttpServletRequest request) {
		long user = currentUser.require(request).getId();
		String decision = body == null || body.decision() == null ? "" : body.decision().toUpperCase(java.util.Locale.ROOT);
		if (!DECISIONS.contains(decision)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "decision must be SELL, TIGHTEN or HOLD");
		}
		if (!guard.decideByUser(user, id, decision)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "That alert is no longer open — Argus may have already decided");
		}
	}

	/** "I've set this stop at my broker." */
	@PostMapping("/stops/confirm")
	public void confirmStop(@RequestBody ConfirmBody body, HttpServletRequest request) {
		long user = currentUser.require(request).getId();
		if (body == null || body.ticker() == null || !guard.confirmBrokerStop(user, body.ticker(), body.account())) {
			throw new NotFoundException("Guarded holding", body == null ? "?" : body.ticker());
		}
	}

	public record DecisionBody(String decision) {
	}

	public record ConfirmBody(String ticker, String account) {
	}

	private List<GuardAlert> alerts(long user, String where) {
		return jdbc.query("select * from guard_alert where user_id = ? and " + where + " order by created_at desc limit 50",
				(rs, n) -> {
					Instant created = rs.getTimestamp("created_at").toInstant();
					return new GuardAlert(rs.getLong("id"), rs.getString("ticker"), rs.getString("account"), rs.getString("kind"),
							rs.getString("recommendation"), rs.getString("detail"), rs.getBigDecimal("price"), rs.getBigDecimal("stop_price"),
							rs.getString("status"), created, created.plus(HoldingGuard.DECIDE_AFTER), rs.getString("decided_by"),
							rs.getString("decision"), instant(rs.getTimestamp("decided_at")), rs.getBigDecimal("outcome_pct"));
				}, user);
	}

	private static Instant instant(java.sql.Timestamp t) {
		return t == null ? null : t.toInstant();
	}
}
