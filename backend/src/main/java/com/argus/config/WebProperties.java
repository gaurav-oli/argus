package com.argus.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Web/origin configuration ({@code argus.web.*}).
 *
 * @param allowedOrigins browser origins permitted to call the API (CORS). Defaults to the local
 *                       Next dev server. On the Mini (single-origin behind Tailscale serve) set
 *                       this to the tailnet host, e.g. {@code https://<mini>.<tailnet>.ts.net} —
 *                       Spring sees same-origin browser POSTs as cross-origin behind the reverse
 *                       proxy, so the deployed origin must be listed or they're rejected 403.
 *                       Comma-separated via {@code ARGUS_WEB_ALLOWED_ORIGINS}.
 */
@ConfigurationProperties("argus.web")
public record WebProperties(
		@DefaultValue("http://localhost:3000") List<String> allowedOrigins) {
}
