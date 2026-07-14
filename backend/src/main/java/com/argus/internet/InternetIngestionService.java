package com.argus.internet;

import com.argus.internet.InternetSource.HeldCompany;
import com.argus.portfolio.Position;
import com.argus.portfolio.PositionRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Agent 3's internet-ingestion pipeline. On a cadence ({@code argus.internet.poll-ms}, default 6h)
 * it pulls web attention on held companies from every {@link InternetSource} (Hacker News stories,
 * Wikipedia pageviews), dedups against what's stored, and persists each mention.
 *
 * <p>Freezable via {@code argus.internet.enabled} (Fable 5 review item 9): internet buzz is the
 * noisiest, hard-capped-at-0.35 signal, so ingestion can be paused (the deploy sets it off) until a
 * full tuning cycle on clean trade data proves or disproves its value. Existing mentions age out of
 * the 14-day signal window naturally; re-enabling resumes polling without a rebuild.
 */
@Service
public class InternetIngestionService {

	private static final Logger log = LoggerFactory.getLogger(InternetIngestionService.class);

	private final List<InternetSource> sources;
	private final WebMentionRepository mentions;
	private final PositionRepository positions;
	private final boolean enabled;

	public InternetIngestionService(List<InternetSource> sources, WebMentionRepository mentions,
			PositionRepository positions,
			@org.springframework.beans.factory.annotation.Value("${argus.internet.enabled:true}") boolean enabled) {
		this.sources = sources;
		this.mentions = mentions;
		this.positions = positions;
		this.enabled = enabled;
	}

	@Scheduled(fixedDelayString = "${argus.internet.poll-ms:21600000}",
			initialDelayString = "${argus.internet.initial-delay-ms:70000}")
	public void scheduledTick() {
		if (!enabled) {
			log.debug("Internet ingestion frozen (argus.internet.enabled=false) — skipping poll");
			return;
		}
		try {
			ingestOnce();
		}
		catch (RuntimeException ex) {
			log.warn("Internet ingestion cycle failed: {}", ex.getMessage());
		}
	}

	void ingestOnce() {
		// One HeldCompany per ticker, keeping a non-blank company name when any lot has one.
		Map<String, String> nameByTicker = positions.findAllByOrderByTickerAsc().stream()
				.collect(Collectors.toMap(Position::getTicker,
						p -> p.getCompanyName() == null ? "" : p.getCompanyName(), (a, b) -> a.isBlank() ? b : a));
		List<HeldCompany> held = nameByTicker.entrySet().stream()
				.map(e -> new HeldCompany(e.getKey(), e.getValue())).toList();
		if (held.isEmpty() || sources.isEmpty()) {
			return;
		}
		int fetched = 0;
		int saved = 0;
		for (InternetSource source : sources) {
			List<RawWebMention> raw;
			try {
				raw = source.fetch(held);
			}
			catch (RuntimeException ex) {
				log.debug("Internet source {} failed: {}", source.name(), ex.getMessage());
				continue;
			}
			for (RawWebMention m : raw) {
				fetched++;
				if (mentions.existsBySourceAndExternalId(m.source(), m.externalId())) {
					continue;
				}
				mentions.save(new WebMention(m.ticker(), m.source(), m.externalId(), m.title(), m.url(),
						m.score(), m.sentiment(), m.postedAt()));
				saved++;
			}
		}
		if (fetched > 0) {
			log.info("Internet ingestion: {} fetched, {} new across {} source(s)", fetched, saved, sources.size());
		}
	}
}
