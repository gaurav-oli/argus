package com.argus.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Agent runtime configuration ({@code argus.agent.*}).
 *
 * @param pollIntervalMs scheduled poll interval in milliseconds
 * @param readCount      max records read per agent per poll
 */
@ConfigurationProperties("argus.agent")
public record AgentProperties(
		@DefaultValue("500") long pollIntervalMs,
		@DefaultValue("10") int readCount) {
}
