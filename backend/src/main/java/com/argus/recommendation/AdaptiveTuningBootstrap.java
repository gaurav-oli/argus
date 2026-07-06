package com.argus.recommendation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Optionally runs the Phase B adaptive-tuning recompute once at startup
 * ({@code argus.adaptive-tuning.recompute-on-boot=true}). Off by default; useful to validate the
 * Analyst/Investor loop without waiting for the nightly job. Kept as a separate bean so the call
 * crosses the proxy and {@link AdaptiveTuningService#recompute()}'s transaction applies.
 */
@Component
public class AdaptiveTuningBootstrap {

	private static final Logger log = LoggerFactory.getLogger(AdaptiveTuningBootstrap.class);

	private final AdaptiveTuningService tuning;
	private final AdaptiveTuningProperties props;

	public AdaptiveTuningBootstrap(AdaptiveTuningService tuning, AdaptiveTuningProperties props) {
		this.tuning = tuning;
		this.props = props;
	}

	@EventListener(ApplicationReadyEvent.class)
	void recomputeOnBoot() {
		if (!props.recomputeOnBoot()) {
			return;
		}
		try {
			tuning.recompute();
			log.info("Adaptive tuning: recompute-on-boot complete");
		} catch (RuntimeException ex) {
			log.warn("Adaptive tuning: recompute-on-boot failed: {}", ex.getMessage());
		}
	}
}
