package com.argus.technical;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds Agent 10 (Technical Analysis) configuration ({@code argus.technical.*}). */
@Configuration
@EnableConfigurationProperties(TechnicalAnalysisProperties.class)
public class TechnicalConfig {
}
