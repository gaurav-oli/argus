package com.argus.marketdata;

import jakarta.annotation.PostConstruct;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fallback-provider toggle for when Finnhub is unavailable (Story 4.5, GAP-4). The fallback
 * (Alpha Vantage / Yahoo) is a documented seam, not yet implemented: this surfaces the configured
 * provider ({@code argus.finnhub.resilience.fallback-provider}) and reports its status at startup so
 * the wiring point is explicit. Switching it on without an implementation is a no-op (logged), so the
 * toggle can be flipped ahead of the provider being built. See docs/finnhub-resilience.md.
 */
@Component
public class FinnhubFallback {

	private static final Logger log = LoggerFactory.getLogger(FinnhubFallback.class);

	/** Recognized fallback providers. {@link #NONE} = no fallback (Finnhub failures degrade to free sources). */
	public enum Provider {
		NONE, ALPHA_VANTAGE, YAHOO;

		static Provider from(String raw) {
			try {
				return valueOf(raw.trim().toUpperCase(Locale.ROOT));
			} catch (RuntimeException ex) {
				return NONE;
			}
		}
	}

	private final Provider provider;

	public FinnhubFallback(FinnhubResilienceProperties props) {
		this.provider = Provider.from(props.fallbackProvider());
	}

	@PostConstruct
	void announce() {
		if (provider == Provider.NONE) {
			log.info("Finnhub fallback: none configured — failures degrade to free sources (GDELT/RSS).");
		} else {
			log.warn("Finnhub fallback '{}' selected but not yet implemented (stub) — calls will not fall over.",
					provider);
		}
	}

	public Provider provider() {
		return provider;
	}

	/** True once a fallback provider is selected (an implementation is still pending). */
	public boolean isConfigured() {
		return provider != Provider.NONE;
	}
}
