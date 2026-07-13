package com.argus.recommendation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@code argus.*} recommendation configuration (Phase B adaptive tuning + logic review). */
@Configuration
@EnableConfigurationProperties({AdaptiveTuningProperties.class, LogicReviewProperties.class})
public class RecommendationConfig {
}
