package com.argus.agent;

/**
 * Base abstraction for an Argus agent. Implement this and register the bean — the
 * {@link AgentRuntime} handles the Redis Streams consumer group, scheduling, and ack.
 * Agents never call each other directly; they communicate via streams.
 */
public interface Agent {

	/** Stable agent name; also the default consumer-group and consumer name. */
	String name();

	/** The Redis Stream key this agent consumes from. */
	String streamKey();

	/** Consumer group for this agent (one group per agent). */
	default String consumerGroup() {
		return name();
	}

	/** Handle a received event. Throwing leaves the message unacknowledged (stays pending). */
	void handle(EventEnvelope event);
}
