package com.argus.research;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Agent 9 configuration ({@code argus.research.*}).
 *
 * @param maxConcurrentJobs how many research jobs can run at once — bounded so a burst of requests
 *                          doesn't starve the local model's BIG-tier semaphore that Agents 1-8 also share
 * @param dataWindowDays    how far back each data-gathering step looks (news/social/insider/web)
 */
@ConfigurationProperties("argus.research")
public record ResearchJobProperties(
		@DefaultValue("2") int maxConcurrentJobs,
		@DefaultValue("30") int dataWindowDays) {
}
