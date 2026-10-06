package com.argus.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.TestcontainersConfiguration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Deleting a person against real Postgres: nothing of theirs survives, and nobody else's data is touched. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UserDeletionServiceIntegrationTest {

	@Autowired
	UserDeletionService deletion;

	@Autowired
	AppUserRepository users;

	@Autowired
	InvitedEmailRepository invites;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void everyPerUserTableIsCoveredByTheDeletion() {
		List<String> withUserId = jdbc.queryForList(
				"select table_name from information_schema.columns where column_name = 'user_id' "
						+ "and table_schema = 'public' and table_name <> 'flyway_schema_history'", String.class);
		Set<String> missing = new HashSet<>(withUserId);
		missing.removeAll(UserDeletionService.USER_TABLES);
		assertTrue(missing.isEmpty(), "a new per-user table must be added to UserDeletionService.USER_TABLES: " + missing);
	}

	@Test
	void deletesTheAccountTheirDataAndTheirInviteOnly() {
		AppUser gone = users.save(new AppUser("sub-del-1", "gone@example.com", "Gone", null, false));
		AppUser kept = users.save(new AppUser("sub-del-2", "kept@example.com", "Kept", null, false));
		invites.save(new InvitedEmail("gone@example.com", "admin"));
		invites.save(new InvitedEmail("kept@example.com", "admin"));
		jdbc.update("insert into user_activity_day (user_id, activity_date, first_seen_at, last_seen_at) values (?, current_date, now(), now())", gone.getId());
		jdbc.update("insert into user_activity_day (user_id, activity_date, first_seen_at, last_seen_at) values (?, current_date, now(), now())", kept.getId());

		deletion.delete(gone);

		assertTrue(users.findById(gone.getId()).isEmpty());
		assertTrue(invites.findById("gone@example.com").isEmpty());
		assertEquals(0, jdbc.queryForObject("select count(*) from user_activity_day where user_id = ?", Integer.class, gone.getId()));
		assertTrue(users.findById(kept.getId()).isPresent());
		assertTrue(invites.findById("kept@example.com").isPresent());
		assertEquals(1, jdbc.queryForObject("select count(*) from user_activity_day where user_id = ?", Integer.class, kept.getId()));
	}
}
