package com.argus.recommendation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@code argus.*} recommendation configuration (Phase B adaptive tuning). */
@Configuration
@EnableConfigurationProperties(AdaptiveTuningProperties.class)
public class RecommendationConfig {
}
