package com.argus.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.TestcontainersConfiguration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Pre-event alert scan against real Postgres + Redis (Story 5.2): a reminder is queued on the
 * notifications stream and recorded so a re-scan doesn't duplicate it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CalendarAlertIntegrationTest {

	@Autowired
	CalendarAlertService service;

	@Autowired
	CalendarEventRepository events;

	@Autowired
	CalendarEventAlertRepository alerts;

	@Autowired
	StringRedisTemplate redis;

	@BeforeEach
	void clean() {
		alerts.deleteAll();
		events.deleteAll();
		redis.delete(com.argus.notification.NotificationStream.KEY);
	}

	@Test
	void queuesReminderOnceAndIsIdempotent() {
		LocalDate today = LocalDate.now(ZoneId.of("America/Toronto"));
		events.save(new CalendarEvent(CalendarEventType.EARNINGS, "AAPL", "AAPL earnings",
				today.plusDays(1), "finnhub-earnings", "EARNINGS:AAPL:soon"));

		assertEquals(1, service.scan());
		assertEquals(0, service.scan(), "the same reminder must not fire twice");

		assertEquals(1, alerts.count());
		List<MapRecord<String, Object, Object>> queued = redis.opsForStream()
				.read(StreamOffset.fromStart(com.argus.notification.NotificationStream.KEY));
		assertEquals(1, queued.size());
		assertTrue(String.valueOf(queued.get(0).getValue()).contains("calendar.event_reminder"));
	}
}
