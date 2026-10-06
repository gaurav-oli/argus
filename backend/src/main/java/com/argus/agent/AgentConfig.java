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
	TaskScheduler taskScheduler(org.springframework.beans.factory.ObjectProvider<com.argus.ops.ScheduledRunLedger> ledger) {
		// Every clock-scheduled (cron) job is stamped in the run ledger as it completes, so a slot missed
		// while the host was down can be detected and caught up at the next start (MissedRunCatchUp).
		// The task's toString is Spring's "fully.qualified.Class.method" for an @Scheduled method.
		SimpleAsyncTaskScheduler scheduler = new SimpleAsyncTaskScheduler() {
			@Override
			public java.util.concurrent.ScheduledFuture<?> schedule(Runnable task, org.springframework.scheduling.Trigger trigger) {
				String job = task.toString();
				return super.schedule(() -> {
					task.run();
					com.argus.ops.ScheduledRunLedger l = ledger.getIfAvailable();
					if (l != null) {
						l.recordRun(job);
					}
				}, trigger);
			}
		};
		scheduler.setVirtualThreads(true);
		scheduler.setThreadNamePrefix("agent-scheduler-");
		return scheduler;
	}
}
