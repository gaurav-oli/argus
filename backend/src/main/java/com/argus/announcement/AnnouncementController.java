package com.argus.announcement;

import com.argus.security.AppUser;
import com.argus.security.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "What's new" notes: shared announcements each signed-in person sees once — the next time they open Argus,
 * on whichever device — until they mark it read. Session-gated under {@code /api/announcements}.
 */
@RestController
@RequestMapping("/api/announcements")
public class AnnouncementController {

	private final JdbcTemplate jdbc;
	private final CurrentUserService currentUser;

	public AnnouncementController(JdbcTemplate jdbc, CurrentUserService currentUser) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
	}

	/** Published announcements this person hasn't read yet, newest first. */
	@GetMapping("/unread")
	public List<AnnouncementView> unread(HttpServletRequest request) {
		AppUser user = currentUser.require(request);
		return jdbc.query("""
				select a.id, a.title, a.body, a.published_at from announcement a
				where a.published_at <= now()
				  and not exists (select 1 from announcement_read r where r.announcement_id = a.id and r.user_id = ?)
				order by a.published_at desc, a.id desc""",
				(rs, n) -> new AnnouncementView(rs.getLong(1), rs.getString(2), lines(rs.getString(3)),
						rs.getTimestamp(4).toInstant()),
				user.getId());
	}

	/** Mark one announcement read for this person (idempotent). */
	@PostMapping("/{id}/read")
	public void markRead(@PathVariable long id, HttpServletRequest request) {
		AppUser user = currentUser.require(request);
		jdbc.update("insert into announcement_read (announcement_id, user_id) select ?, ? where exists "
				+ "(select 1 from announcement where id = ?) on conflict do nothing", id, user.getId(), id);
	}

	private static List<String> lines(String body) {
		return Arrays.stream(body.split("\\R")).map(String::strip).filter(l -> !l.isEmpty()).toList();
	}

	public record AnnouncementView(long id, String title, List<String> points, Instant publishedAt) {
	}
}
