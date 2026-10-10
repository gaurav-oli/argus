package com.argus.cost;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.TestcontainersConfiguration;
import com.argus.security.CurrentUserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

/** S-C3: soft daily caps per person, with the admin exempt. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = { "argus.quota.enabled=true", "argus.quota.deep-analysis=2", "argus.quota.ask-ai=0" })
class UsageQuotaIntegrationTest {

	@Autowired
	UsageQuota quota;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	StringRedisTemplate redis;

	long friend;
	long admin;

	private long user(String email, boolean isAdmin) {
		jdbc.update("delete from app_user where email = ?", email);
		return jdbc.queryForObject("insert into app_user (google_sub, email, name, is_admin) values (?, ?, ?, ?) returning id",
				Long.class, "sub-" + email, email, email, isAdmin);
	}

	@BeforeEach
	void setUp() {
		friend = user("friend-quota@example.test", false);
		admin = user("admin-quota@example.test", true);
		var keys = redis.keys("argus:quota:*");
		if (keys != null && !keys.isEmpty()) redis.delete(keys);
	}

	@Test
	void aFriendIsStoppedAtTheCapWithAFriendly429() {
		CurrentUserContext.runAs(friend, () -> {
			quota.consume(UsageQuota.Kind.DEEP_ANALYSIS);
			quota.consume(UsageQuota.Kind.DEEP_ANALYSIS);
			ResponseStatusException ex = assertThrows(ResponseStatusException.class,
					() -> quota.consume(UsageQuota.Kind.DEEP_ANALYSIS));
			assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
			assertTrue(ex.getReason().contains("today's 2 deep analyses"), ex.getReason());
		});
		assertEquals(2, quota.today(friend).get("DEEP_ANALYSIS").used(), "the refused call doesn't count");
	}

	@Test
	void theAdminIsExemptAndAZeroCapIsUnlimited() {
		CurrentUserContext.runAs(admin, () -> {
			for (int i = 0; i < 5; i++) quota.consume(UsageQuota.Kind.DEEP_ANALYSIS);
		});
		CurrentUserContext.runAs(friend, () -> {
			for (int i = 0; i < 50; i++) quota.consume(UsageQuota.Kind.ASK_AI);
		});
		assertEquals(0, quota.today(admin).get("DEEP_ANALYSIS").cap());
	}

	@Test
	void backgroundJobsAreNeverMetered() {
		assertDoesNotThrow(() -> {
			for (int i = 0; i < 10; i++) quota.consume(UsageQuota.Kind.DEEP_ANALYSIS);
		});
	}
}
