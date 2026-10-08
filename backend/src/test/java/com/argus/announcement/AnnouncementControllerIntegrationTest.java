package com.argus.announcement;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.argus.TestcontainersConfiguration;
import com.argus.security.AppUserRepository;
import com.argus.security.SessionStore;
import com.argus.security.TestUserSessions;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** A "What's new" note shows once per person, and one person dismissing it never hides it for anyone else. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AnnouncementControllerIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	AppUserRepository users;

	@Autowired
	SessionStore sessions;

	@Autowired
	JdbcTemplate jdbc;

	private long noteId;

	@BeforeEach
	void oneNote() {
		jdbc.update("delete from announcement");
		noteId = jdbc.queryForObject("insert into announcement (title, body) values ('What''s new', 'First point\nSecond point\n') returning id",
				Long.class);
	}

	@Test
	void eachPersonSeesItOnceAndDismissingIsPersonal() throws Exception {
		Cookie alice = TestUserSessions.loginAsNewUser(users, sessions);
		Cookie bob = TestUserSessions.loginAsNewUser(users, sessions);

		mvc.perform(get("/api/announcements/unread").cookie(alice)).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].title").value("What's new"))
				.andExpect(jsonPath("$[0].points.length()").value(2));

		mvc.perform(post("/api/announcements/" + noteId + "/read").cookie(alice)).andExpect(status().isOk());
		mvc.perform(post("/api/announcements/" + noteId + "/read").cookie(alice)).andExpect(status().isOk()); // idempotent

		mvc.perform(get("/api/announcements/unread").cookie(alice)).andExpect(jsonPath("$.length()").value(0));
		mvc.perform(get("/api/announcements/unread").cookie(bob)).andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	void signedOutGetsNothing() throws Exception {
		mvc.perform(get("/api/announcements/unread")).andExpect(status().isUnauthorized());
	}
}
