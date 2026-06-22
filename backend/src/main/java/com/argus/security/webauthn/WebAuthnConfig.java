package com.argus.security.webauthn;

import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import java.util.LinkedHashSet;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Builds the Yubico {@link RelyingParty} from {@link WebAuthnProperties} (Story 2.2). */
@Configuration
@EnableConfigurationProperties(WebAuthnProperties.class)
public class WebAuthnConfig {

	@Bean
	RelyingParty relyingParty(WebAuthnProperties props, ArgusCredentialRepository credentials) {
		RelyingPartyIdentity identity = RelyingPartyIdentity.builder()
				.id(props.rpId())
				.name(props.rpName())
				.build();
		return RelyingParty.builder()
				.identity(identity)
				.credentialRepository(credentials)
				.origins(new LinkedHashSet<>(props.origins()))
				.build();
	}
}
