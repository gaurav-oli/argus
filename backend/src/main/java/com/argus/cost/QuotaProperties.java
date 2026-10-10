package com.argus.cost;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S-C3 — soft daily caps per person on the paths that spend the shared model budget. A cap of 0 or less turns
 * that cap off. The admin is exempt by default ({@code adminExempt}); otherwise they get {@code adminMultiplier}×
 * every cap. Days roll over at midnight America/Toronto.
 */
@ConfigurationProperties("argus.quota")
public record QuotaProperties(Boolean enabled, Integer askAi, Integer research, Integer deepAnalysis, Integer debate,
		Integer imports, Boolean adminExempt, Integer adminMultiplier) {

	public QuotaProperties {
		enabled = enabled == null ? Boolean.TRUE : enabled;
		askAi = askAi == null ? 40 : askAi;
		research = research == null ? 5 : research;
		deepAnalysis = deepAnalysis == null ? 10 : deepAnalysis;
		debate = debate == null ? 10 : debate;
		imports = imports == null ? 10 : imports;
		adminExempt = adminExempt == null ? Boolean.TRUE : adminExempt;
		adminMultiplier = adminMultiplier == null || adminMultiplier < 1 ? 5 : adminMultiplier;
	}

	public int capFor(UsageQuota.Kind kind) {
		return switch (kind) {
			case ASK_AI -> askAi;
			case RESEARCH -> research;
			case DEEP_ANALYSIS -> deepAnalysis;
			case DEBATE -> debate;
			case IMPORT -> imports;
		};
	}
}
