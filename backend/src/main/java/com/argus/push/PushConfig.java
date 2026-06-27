package com.argus.push;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Binds {@link PushProperties} and wires the {@link PushSender} (Epic 8, FR-17). */
@Configuration
@EnableConfigurationProperties(PushProperties.class)
public class PushConfig {

	@Bean
	PushSender pushSender(PushProperties props) {
		return new WebPushSender(props);
	}
}
