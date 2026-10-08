package com.argus.security;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Permanently removes one person from Argus: every row of their private data, their account and
 * their invite, in one transaction. Shared market intelligence (news, recommendations, agent output)
 * is not per-user and is untouched. Most per-user foreign keys have no ON DELETE rule, so the tables
 * are cleared explicitly, children before parents.
 */
@Service
public class UserDeletionService {

	private static final Logger log = LoggerFactory.getLogger(UserDeletionService.class);

	/** Every table holding a {@code user_id}, in a safe delete order. A new per-user table must be added here. */
	static final List<String> USER_TABLES = List.of(
			"corporate_actions", "position_lots", "position_audit", "positions", "portfolio_value_history",
			"portfolio_imports", "cash_balances", "account_meta", "health_score", "briefings",
			"push_subscriptions", "investor_profile", "breaking_alert_read", "user_activity_day", "announcement_read");

	private final JdbcTemplate jdbc;

	public UserDeletionService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional
	public void delete(AppUser user) {
		int rows = 0;
		for (String table : USER_TABLES) {
			rows += jdbc.update("delete from " + table + " where user_id = ?", user.getId());
		}
		jdbc.update("delete from app_user where id = ?", user.getId());
		jdbc.update("delete from invited_email where email = ?", InvitedEmail.normalize(user.getEmail()));
		log.info("Deleted user {} and {} row(s) of their data", user.getId(), rows);
	}
}
