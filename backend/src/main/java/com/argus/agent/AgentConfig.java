package com.argus.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Enables scheduling (agents poll via {@code @Scheduled}) and agent config binding. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(AgentProperties.class)
public class AgentConfig {

	/**
	 * Thread pool for {@code @Scheduled} agent polling. Defined explicitly for two reasons:
	 * <ul>
	 * <li>Enabling WebSocket/STOMP registers a {@code messageBrokerTaskScheduler}; without an
	 * explicit bean named {@code taskScheduler} the scheduling infrastructure may bind {@code @Scheduled}
	 * to the broker's scheduler.</li>
	 * <li>A <em>pool</em> (not a single timing thread) so the ingestion agents — News, Social, SEC,
	 * Internet — run concurrently. With a single-threaded scheduler a slow tick (e.g. a GDELT connect
	 * timeout, or SEC loading the 10k ticker map) blocks every other agent's next fire, which delayed
	 * Agent 3's first cycle by minutes. The pool is sized to cover all scheduled methods at once.
	 * </ul>
	 */
	@Bean
	TaskScheduler taskScheduler(@Value("${argus.agent.scheduler-pool-size:8}") int poolSize) {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(poolSize);
		scheduler.setThreadNamePrefix("agent-scheduler-");
		scheduler.setRemoveOnCancelPolicy(true);
		return scheduler;
	}
}
