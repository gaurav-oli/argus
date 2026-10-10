package com.argus.recommendation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@code argus.*} recommendation configuration (Phase B adaptive tuning + logic review + S-B2 trust bar). */
@Configuration
@EnableConfigurationProperties({AdaptiveTuningProperties.class, LogicReviewProperties.class, TrustBarProperties.class})
public class RecommendationConfig {
}
