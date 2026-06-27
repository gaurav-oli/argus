package com.argus.ops;

import com.argus.common.LivePushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pushes the agent-fleet snapshot to the {@code /topic/agents} STOMP topic on an interval so the
 * Agents view updates live over WebSocket (Story 9.1, "updating live over WebSocket") rather than
 * only on page load. The snapshot is cheap (repository counts + latest timestamps); the interval is
 * configurable via {@code argus.ops.agent-broadcast-ms}.
 */
@Component
public class AgentStatusBroadcaster {

	static final String TOPIC = "/topic/agents";

	private static final Logger log = LoggerFactory.getLogger(AgentStatusBroadcaster.class);

	private final AgentStatusService agents;
	private final LivePushService livePush;

	public AgentStatusBroadcaster(AgentStatusService agents, LivePushService livePush) {
		this.agents = agents;
		this.livePush = livePush;
	}

	@Scheduled(fixedDelayString = "${argus.ops.agent-broadcast-ms:15000}",
			initialDelayString = "${argus.ops.agent-broadcast-ms:15000}")
	public void broadcast() {
		try {
			livePush.publish(TOPIC, agents.snapshot());
		} catch (RuntimeException ex) {
			log.debug("Agent status broadcast failed: {}", ex.getMessage());
		}
	}
}
