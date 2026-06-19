package com.argus.model;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Model wiring. Under {@code dev} the gateway's {@link ChatModel} is an in-memory
 * {@link MockChatModel}; under {@code prod} it is the Ollama {@code ChatModel}
 * supplied by Spring AI's Ollama autoconfiguration (which {@code dev} excludes).
 */
@Configuration
@EnableConfigurationProperties(ModelGatewayProperties.class)
public class ModelConfig {

	@Bean
	@Profile("dev")
	ChatModel mockChatModel(ModelGatewayProperties properties) {
		return new MockChatModel(properties.devResponse());
	}
}
