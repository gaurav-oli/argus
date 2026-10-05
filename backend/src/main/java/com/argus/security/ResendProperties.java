package com.argus.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Resend (resend.com) transactional email, for the admin-sent friend invite ({@code argus.resend.*},
 * set via {@code ARGUS_RESEND_*}). An empty API key is a deliberate default — with none set, sending
 * fails clearly rather than silently no-op-ing or crashing confusingly mid-request.
 *
 * @param apiKey    Resend API key — never logged, never returned to the client
 * @param fromEmail the verified sender; {@code onboarding@resend.dev} works with no domain setup
 */
@ConfigurationProperties("argus.resend")
public record ResendProperties(@DefaultValue("") String apiKey, @DefaultValue("onboarding@resend.dev") String fromEmail) {

	public boolean configured() {
		return !apiKey.isBlank();
	}
}
