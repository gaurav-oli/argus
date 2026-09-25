package com.argus.deepanalysis;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds Agent 11 (Deep Analyst) configuration ({@code argus.deep-analysis.*}). */
@Configuration
@EnableConfigurationProperties(DeepAnalysisProperties.class)
public class DeepAnalysisConfig {
}
