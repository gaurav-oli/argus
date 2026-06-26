package com.argus.agent;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;

/** Enables scheduling (agents poll via {@code @Scheduled}) and agent config binding. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(AgentProperties.class)
public class AgentConfig {

	/**
	 * Virtual-thread scheduler for {@code @Scheduled} agent polling. Defined explicitly because
	 * enabling WebSocket/STOMP registers a {@code messageBrokerTaskScheduler} (a platform-thread
	 * {@link TaskScheduler}), which otherwise causes Boot's virtual-thread scheduler to back off
	 * and {@code @Scheduled} to run on broker threads. Named {@code taskScheduler} so the
	 * scheduling infrastructure selects it.
	 */
	@Bean
	TaskScheduler taskScheduler() {
		SimpleAsyncTaskScheduler scheduler = new SimpleAsyncTaskScheduler();
		scheduler.setVirtualThreads(true);
		scheduler.setThreadNamePrefix("agent-scheduler-");
		return scheduler;
	}
}
