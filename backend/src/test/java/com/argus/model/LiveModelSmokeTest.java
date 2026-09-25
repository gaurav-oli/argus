package com.argus.model;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.argus.cost.CostEventRepository;
import com.argus.cost.CostGovernor;
import com.argus.cost.CostRecorder;
import com.argus.cost.LocalModelCallRepository;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;

/**
 * Opt-in smoke test against a REAL local Ollama ({@code -Dargus.live=true}, model via {@code -Dargus.live.model=gemma4:12b}).
 * It guards two failures a mock cannot see, both hit for real while building Agent 11:
 * <ol>
 *   <li>gemma4 spent the whole 1024-token cap on hidden "thinking" and returned {@code ""} (the gateway then paid Haiku), and</li>
 *   <li>a per-call {@code OllamaChatOptions} built from scratch silently replaced the configured model with Spring AI's default
 *       ("mistral") → 404 on every call.</li>
 * </ol>
 * The fallback here is a sentinel string, so a blank/failed local call shows up as a test failure instead of a silent paid call.
 */
@EnabledIfSystemProperty(named = "argus.live", matches = "true")
class LiveModelSmokeTest {

	@Test
	void aBigTierJsonRequestIsAnsweredByTheLocalModelNotTheFallback() {
		String model = System.getProperty("argus.live.model", "gemma4:12b");
		OllamaChatModel chat = OllamaChatModel.builder()
				.ollamaApi(OllamaApi.builder().baseUrl(System.getProperty("argus.live.url", "http://localhost:11434")).build())
				.options(OllamaChatOptions.builder().model(model).numPredict(1024).build()).build();
		ModelGatewayProperties props = new ModelGatewayProperties(1, Duration.ofSeconds(280), Duration.ofMinutes(5), model, "llama3.2:3b", "x", false);
		CostGovernor gov = new CostGovernor(org.mockito.Mockito.mock(CostEventRepository.class), org.mockito.Mockito.mock(LocalModelCallRepository.class), 0);
		CostRecorder rec = new CostRecorder(org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class),
				org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class));
		String prompt = """
				You are a chart technician. The stock closed at 100, above its 20-day average of 98 and 50-day average of 95, RSI 55,
				MACD histogram positive, up-day volume 1.4x down-day volume. Respond with ONLY a JSON object:
				{"stance":"BULLISH|BEARISH|NEUTRAL","strength":0.0,"summary":"2 sentences","keyPoints":["..."],"risks":["..."]}
				""";

		String out = new DefaultModelGateway(chat, p -> "FALLBACK-FIRED", gov, rec, props).generate(prompt);

		assertNotEquals("FALLBACK-FIRED", out, "the local model returned blank or failed, so the paid fallback fired");
		org.junit.jupiter.api.Assertions.assertTrue(out.contains("stance"), out);
	}
}
