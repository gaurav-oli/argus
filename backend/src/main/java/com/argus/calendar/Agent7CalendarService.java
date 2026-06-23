package com.argus.calendar;

import com.argus.calendar.CalendarSource.RawEvent;
import com.argus.portfolio.Position;
import com.argus.portfolio.PositionRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Agent 7 — the economic/earnings calendar (Story 5.1, FR-21). Once a day it pulls upcoming events
 * from every configured {@link CalendarSource} (Finnhub earnings, Fed RSS), deduplicates against
 * what's stored, and persists them. Per-event and per-source failures are isolated so one bad item
 * or flaky source never aborts the run; a duplicate within a run is dropped before the DB sees it.
 */
@Service
public class Agent7CalendarService {

	private static final Logger log = LoggerFactory.getLogger(Agent7CalendarService.class);

	private final List<CalendarSource> sources;
	private final CalendarEventRepository events;
	private final PositionRepository positions;

	public Agent7CalendarService(List<CalendarSource> sources, CalendarEventRepository events,
			PositionRepository positions) {
		this.sources = sources;
		this.events = events;
		this.positions = positions;
	}

	/** Daily run at 06:00 America/New_York. Never throws out of the scheduler. */
	@Scheduled(cron = "0 0 6 * * *", zone = "America/New_York")
	public void scheduledRun() {
		try {
			ingestOnce();
		} catch (RuntimeException ex) {
			log.warn("Agent 7 calendar run failed: {}", ex.getMessage());
		}
	}

	/** Run one ingestion pass across all sources. Returns the number of new events stored. */
	public int ingestOnce() {
		if (sources.isEmpty()) {
			return 0;
		}
		Set<String> held = heldTickers();
		List<RawEvent> raws = new ArrayList<>();
		for (CalendarSource source : sources) {
			try {
				raws.addAll(source.fetch(held));
			} catch (RuntimeException ex) {
				log.warn("Calendar source {} failed: {}", source.name(), ex.getMessage());
			}
		}
		int stored = 0;
		Set<String> seen = new HashSet<>();
		for (RawEvent raw : raws) {
			if (raw.externalId() == null || raw.externalId().isBlank()
					|| !seen.add(raw.source() + ' ' + raw.externalId())) {
				continue;
			}
			try {
				if (store(raw)) {
					stored++;
				}
			} catch (RuntimeException ex) {
				log.warn("Failed to store event {}/{}: {}", raw.source(), raw.externalId(), ex.getMessage());
			}
		}
		log.info("Agent 7 calendar: {} fetched, {} new across {} source(s)", raws.size(), stored, sources.size());
		return stored;
	}

	private boolean store(RawEvent raw) {
		if (events.existsBySourceAndExternalId(raw.source(), raw.externalId())) {
			return false;
		}
		events.save(new CalendarEvent(raw.type(), raw.ticker(), raw.title(), raw.eventDate(),
				raw.source(), raw.externalId()));
		return true;
	}

	private Set<String> heldTickers() {
		Set<String> held = new LinkedHashSet<>();
		for (Position p : positions.findAllByOrderByTickerAsc()) {
			if (p.getTicker() != null) {
				held.add(p.getTicker().trim().toUpperCase());
			}
		}
		return held;
	}
}
