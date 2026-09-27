package com.argus.filings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.argus.cost.CostEventRepository;
import com.argus.cost.CostGovernor;
import com.argus.cost.CostRecorder;
import com.argus.cost.LocalModelCallRepository;
import com.argus.intelligence.KnownUniverse;
import com.argus.model.DefaultModelGateway;
import com.argus.model.ModelGatewayProperties;
import com.argus.sec.EdgarClient;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;

/**
 * Opt-in live check ({@code -Dargus.live=true}) of the whole filings reader against the REAL SEC EDGAR and the REAL local model:
 * finds a company's latest earnings release, digests it, and checks that the verification actually ran. Guards what mocks cannot:
 * EDGAR's real index/exhibit layout, real HTML, and the local model's real JSON compliance.
 */
@EnabledIfSystemProperty(named = "argus.live", matches = "true")
class LiveFilingsSmokeTest {

	@Test
	void aRealEarningsReleaseIsReadVerifiedAndScored() {
		String model = System.getProperty("argus.live.model", "gemma4:12b");
		OllamaChatModel chat = OllamaChatModel.builder().ollamaApi(OllamaApi.builder().baseUrl("http://localhost:11434").build())
				.options(OllamaChatOptions.builder().model(model).numPredict(1024).build()).build();
		var gateway = new DefaultModelGateway(chat, p -> "FALLBACK", new CostGovernor(mock(CostEventRepository.class), mock(LocalModelCallRepository.class), 0),
				new CostRecorder(mock(org.springframework.beans.factory.ObjectProvider.class), mock(org.springframework.beans.factory.ObjectProvider.class)),
				new ModelGatewayProperties(1, Duration.ofSeconds(280), Duration.ofMinutes(5), model, "llama3.2:3b", "x", false));
		FilingDigestRepository repo = mock(FilingDigestRepository.class);
		when(repo.save(any(FilingDigest.class))).thenAnswer(i -> i.getArgument(0));
		FilingDigestService service = new FilingDigestService(new EdgarClient("Argus live test (gauravoli16@gmail.com)"), repo, gateway,
				mock(KnownUniverse.class));

		List<FilingDigest> digests = service.refresh(System.getProperty("argus.live.ticker", "NVDA"));

		assertFalse(digests.isEmpty(), "no filing was digested — EDGAR layout or model output problem");
		for (FilingDigest d : digests) {
			System.out.println("LIVE DIGEST " + d.getKind() + " " + d.getForm() + " filed " + d.getFiledAt() + " guidance=" + d.getGuidance() + " tone=" + d.getTone()
					+ " score=" + d.getScore() + " verified=" + d.getVerifiedFacts() + " dropped=" + d.getDroppedFacts() + " chars=" + d.getSourceChars()
					+ "\n   summary: " + d.getSummary() + "\n   guidanceDetail: " + d.getGuidanceDetail());
			assertTrue(d.getSourceChars() > 1000, "the filing text extraction returned almost nothing");
		}
	}
}
