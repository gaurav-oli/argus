package com.argus.intelligence;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds Agent 1 (news intelligence) configuration ({@code argus.news.*}, Story 4.1). */
@Configuration
@EnableConfigurationProperties(NewsIngestionProperties.class)
public class IntelligenceConfig {
}
