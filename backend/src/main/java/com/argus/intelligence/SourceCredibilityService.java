package com.argus.intelligence;

import com.argus.agent.AgentEventPublisher;
import com.argus.notification.NotificationStream;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Source Credibility Engine (Story 4.3, FR-9). Tracks every news source's 0–100 score: unknown
 * sources are registered at {@link SourceCredibility#UNKNOWN_START}, signal outcomes move the score
 * (+2 correct / −3 incorrect), and a source falling below {@link SourceCredibility#BLOCK_THRESHOLD}
 * is auto-blocked — emitting a one-time notification so the user is told. Downstream stages consult
 * {@link #isBlocked(String)} before trusting a source's signals.
 */
@Service
public class SourceCredibilityService {

	static final String EVENT_AUTO_BLOCKED = "source.auto_blocked";

	private static final Logger log = LoggerFactory.getLogger(SourceCredibilityService.class);

	private final SourceCredibilityRepository repository;
	private final AgentEventPublisher events;

	public SourceCredibilityService(SourceCredibilityRepository repository, AgentEventPublisher events) {
		this.repository = repository;
		this.events = events;
	}

	/** Get the source's record, registering it at the unknown baseline (35) on first sighting. */
	@Transactional
	public SourceCredibility register(String source) {
		return repository.findBySource(source)
				.orElseGet(() -> repository.save(SourceCredibility.unknown(source)));
	}

	/**
	 * Record a resolved signal outcome for {@code source} and persist the new score/tier/blocked
	 * state. When this resolution auto-blocks the source, a {@code source.auto_blocked} notification
	 * is emitted exactly once.
	 */
	@Transactional
	public SourceCredibility recordOutcome(String source, boolean correct) {
		SourceCredibility cred = register(source);
		boolean newlyBlocked = cred.recordOutcome(correct);
		repository.save(cred);
		if (newlyBlocked) {
			log.warn("Source '{}' auto-blocked (score {})", source, cred.getScore());
			events.publish(NotificationStream.KEY, EVENT_AUTO_BLOCKED, Map.of(
					"source", source,
					"score", cred.getScore(),
					"tier", cred.getTier().name()));
		}
		return cred;
	}

	/** Whether a source is currently below the block threshold (its signals must be excluded). */
	@Transactional(readOnly = true)
	public boolean isBlocked(String source) {
		return repository.findBySource(source).map(SourceCredibility::isBlocked).orElse(false);
	}
}
