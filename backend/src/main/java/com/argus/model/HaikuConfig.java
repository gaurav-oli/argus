package com.argus.model;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Model;
import com.argus.cost.CostRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@link HaikuFallback} bean (Story 7.3). Key-gated at runtime: with an Anthropic API key
 * it builds the Claude Haiku client manually (Spring AI's Anthropic chat autoconfig is off — see
 * {@code spring.ai.model.chat=ollama}); without a key it returns {@link UnavailableHaikuFallback}
 * so escalation/fallback fails cleanly (503) rather than returning placeholder text.
 */
@Configuration
@EnableConfigurationProperties(HaikuProperties.class)
public class HaikuConfig {

	private static final Logger log = LoggerFactory.getLogger(HaikuConfig.class);

	@Bean
	HaikuFallback haikuFallback(HaikuProperties props, CostRecorder costRecorder) {
		if (props.apiKey() == null || props.apiKey().isBlank()) {
			log.info("Anthropic API key not set — Haiku escalation unavailable; Argus runs fully local.");
			return new UnavailableHaikuFallback();
		}
		AnthropicClient client = AnthropicOkHttpClient.builder().apiKey(props.apiKey()).build();
		AnthropicChatModel chatModel = AnthropicChatModel.builder()
				.anthropicClient(client)
				.options(AnthropicChatOptions.builder()
						.model(Model.of(props.model()))
						.maxTokens(props.maxTokens())
						.temperature(props.temperature())
						.build())
				.build();
		log.info("Anthropic Haiku escalation enabled (model={})", props.model());
		return new AnthropicHaikuFallback(ChatClient.create(chatModel), costRecorder, props.model());
	}
}
