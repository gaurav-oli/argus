package com.argus.research;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds Agent 9 (on-demand research) configuration ({@code argus.research.*}). */
@Configuration
@EnableConfigurationProperties(ResearchJobProperties.class)
public class ResearchConfig {
}
