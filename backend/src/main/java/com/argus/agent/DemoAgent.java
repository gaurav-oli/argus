package com.argus.agent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sample agent proving the runtime end-to-end (Story 1.5). Consumes {@code argus:stream:demo}
 * and records what it received (plus whether it ran on a virtual thread) for verification.
 * Real agents (news, calendar, recommendations) follow this same shape.
 */
@Component
public class DemoAgent implements Agent {

	public static final String STREAM_KEY = "argus:stream:demo";

	private static final Logger log = LoggerFactory.getLogger(DemoAgent.class);

	private final CountDownLatch latch = new CountDownLatch(1);
	private final AtomicReference<EventEnvelope> lastEvent = new AtomicReference<>();
	private volatile boolean handledOnVirtualThread;

	@Override
	public String name() {
		return "demo-agent";
	}

	@Override
	public String streamKey() {
		return STREAM_KEY;
	}

	@Override
	public void handle(EventEnvelope event) {
		this.handledOnVirtualThread = Thread.currentThread().isVirtual();
		this.lastEvent.set(event);
		log.info("DemoAgent received event {} (type={}, virtualThread={})",
				event.eventId(), event.type(), this.handledOnVirtualThread);
		this.latch.countDown();
	}

	CountDownLatch latch() {
		return this.latch;
	}

	EventEnvelope lastEvent() {
		return this.lastEvent.get();
	}

	boolean handledOnVirtualThread() {
		return this.handledOnVirtualThread;
	}
}
