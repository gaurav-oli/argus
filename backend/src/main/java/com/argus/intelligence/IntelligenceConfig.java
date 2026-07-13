package com.argus.intelligence;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds Agent 1 (news intelligence) configuration ({@code argus.news.*}, Stories 4.1 & 4.4). */
@Configuration
@EnableConfigurationProperties({ NewsIngestionProperties.class, StrangerDangerProperties.class,
		BreakingNewsProperties.class })
public class IntelligenceConfig {
}
