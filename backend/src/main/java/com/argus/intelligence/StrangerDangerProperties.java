package com.argus.intelligence;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Stranger Danger Protocol configuration ({@code argus.news.stranger.*}, Story 4.4).
 *
 * @param pollMs                 scan tick interval
 * @param windowMinutes          look-back window over recent articles for coverage detection
 * @param coverageThreshold      minimum article count on a stranger ticker to trigger scrutiny
 * @param requiredAgentConsensus elevated agent-agreement bar (of 7) Agent 5 must clear for a stranger
 */
@ConfigurationProperties("argus.news.stranger")
public record StrangerDangerProperties(
		@DefaultValue("60000") long pollMs,
		@DefaultValue("360") long windowMinutes,
		@DefaultValue("3") int coverageThreshold,
		@DefaultValue("6") int requiredAgentConsensus) {
}
