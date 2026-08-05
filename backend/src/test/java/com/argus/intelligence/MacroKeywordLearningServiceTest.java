package com.argus.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.intelligence.MacroKeywordLearningService.MissCluster;
import com.argus.intelligence.MacroKeywordLearningService.Proposal;
import com.argus.intelligence.MacroKeywordLearningService.RawProposal;
import com.argus.intelligence.MacroKeywordLearningService.Result;
import com.argus.model.ModelGateway;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The deterministic gate that decides what actually ships (not the model's say-so) — corroboration
 * counting, the ambiguous-word stoplist, JSON proposal parsing, and headline clustering — plus a
 * mocked-collaborator test of the full {@code review()} orchestration (propose → verify → adopt →
 * reload → log), using a real {@link MacroRelevanceTagger} instance to prove the live tagger genuinely
 * ends up in sync, not just that {@code reload()} in isolation works.
 */
class MacroKeywordLearningServiceTest {

	private static MissCluster miss(String headline, String summary) {
		return new MissCluster(headline, summary);
	}

	// ---- corroboration: the real, code-verified count, independent of what the model claims ----

	@Test
	void corroborationCountsDistinctMatchingClusters() {
		List<MissCluster> clusters = List.of(
				miss("Ruritania seizes foreign assets amid crisis", "Markets react to the news"),
				miss("Ruritania's central bank hikes rates", null),
				miss("AAPL beats earnings estimates", "iPhone sales strong"));

		assertEquals(2, MacroKeywordLearningService.corroboration("ruritania", clusters));
		assertEquals(0, MacroKeywordLearningService.corroboration("nonexistent term", clusters));
	}

	@Test
	void corroborationIsCaseInsensitiveAndWholeWord() {
		List<MissCluster> clusters = List.of(miss("RURITANIA declares emergency", null));
		assertEquals(1, MacroKeywordLearningService.corroboration("ruritania", clusters));
		// Whole-word: "ruritanian" (a different token) must not match a bare "ruritania" search.
		List<MissCluster> notWholeWord = List.of(miss("Ruritanian officials meet", null));
		assertEquals(0, MacroKeywordLearningService.corroboration("ruritania", notWholeWord));
	}

	@Test
	void corroborationChecksBothHeadlineAndSummary() {
		List<MissCluster> clusters = List.of(miss("Markets fall", "Ruritania central bank intervenes"));
		assertEquals(1, MacroKeywordLearningService.corroboration("ruritania", clusters));
	}

	// ---- clustering: one viral story can't masquerade as multiple corroborating misses ----

	@Test
	void clusterDedupesTheSameStoryAcrossSources() {
		List<MacroKeywordMiss> misses = List.of(
				new MacroKeywordMiss(1L, "Ruritania seizes foreign assets!", "wire A"),
				new MacroKeywordMiss(2L, "Ruritania seizes foreign assets", "wire B, same story"),
				new MacroKeywordMiss(3L, "Completely different regional election result", "wire C"));

		List<MissCluster> clusters = MacroKeywordLearningService.cluster(misses);

		assertEquals(2, clusters.size());
	}

	// ---- clearsGate: the actual safety mechanism ----

	@Test
	void gateRejectsBelowMinCorroboration() {
		Proposal p = new Proposal("ruritania", "why", 1);
		assertFalse(MacroKeywordLearningService.clearsGate(p, 2, k -> false));
	}

	@Test
	void gateAcceptsAtOrAboveMinCorroboration() {
		Proposal p = new Proposal("ruritania", "why", 2);
		assertTrue(MacroKeywordLearningService.clearsGate(p, 2, k -> false));
	}

	@Test
	void gateRejectsKnownAmbiguousBareWords() {
		// Corroborated plenty, but "war"/"bank"/"trade" alone are exactly the false-positive risk the
		// stoplist exists for — legitimate in ordinary company news ("price war", "bank earnings").
		assertFalse(MacroKeywordLearningService.clearsGate(new Proposal("war", "why", 10), 1, k -> false));
		assertFalse(MacroKeywordLearningService.clearsGate(new Proposal("bank", "why", 10), 1, k -> false));
		assertFalse(MacroKeywordLearningService.clearsGate(new Proposal("trade", "why", 10), 1, k -> false));
	}

	@Test
	void gateAllowsMultiWordPhrasesContainingStoplistWords() {
		// "trade war" (two words) is a legitimate macro phrase, unlike the bare "war" or "trade".
		assertTrue(MacroKeywordLearningService.clearsGate(new Proposal("trade war", "why", 5), 1, k -> false));
	}

	@Test
	void gateRejectsAlreadyExistingKeywords() {
		assertFalse(MacroKeywordLearningService.clearsGate(new Proposal("tariff", "why", 10), 1, k -> true));
	}

	// ---- parse: defensive JSON extraction, same shape as LogicReviewService.parse ----

	@Test
	void parsesWellFormedProposalArray() {
		List<RawProposal> out = MacroKeywordLearningService.parse(
				"[{\"keyword\":\"Ruritania\",\"why\":\"regional crisis, recurring miss\"}]");
		assertEquals(1, out.size());
		assertEquals("ruritania", out.get(0).keyword()); // normalized to lowercase
	}

	@Test
	void toleratesSurroundingProseAndCodeFences() {
		List<RawProposal> out = MacroKeywordLearningService.parse("""
				Sure! Here are my suggestions:
				```json
				[{"keyword":"regional election", "why": "moved several tickers"}]
				```
				""");
		assertEquals(1, out.size());
		assertEquals("regional election", out.get(0).keyword());
	}

	@Test
	void nonJsonReplyDegradesToEmptyList() {
		assertTrue(MacroKeywordLearningService.parse("[dev-mock] Argus Model Gateway is alive.").isEmpty());
	}

	@Test
	void nullReplyDegradesToEmptyList() {
		assertTrue(MacroKeywordLearningService.parse(null).isEmpty());
	}

	@Test
	void blankKeywordIsSkipped() {
		List<RawProposal> out = MacroKeywordLearningService.parse("[{\"keyword\":\"\",\"why\":\"x\"}]");
		assertTrue(out.isEmpty());
	}

	// ---- full review() orchestration: propose (mocked model) → verify → adopt → reload → log ----

	private final MacroKeywordMissRepository misses = mock(MacroKeywordMissRepository.class);
	private final MacroKeywordRepository keywords = mock(MacroKeywordRepository.class);
	private final MacroRelevanceTagger tagger = new MacroRelevanceTagger(); // real: proves reload() truly lands
	private final ModelGateway gateway = mock(ModelGateway.class);
	private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

	private MacroKeywordLearningService service(MacroKeywordLearningProperties props) {
		return new MacroKeywordLearningService(misses, keywords, tagger, gateway, jdbc, props);
	}

	private static MacroKeywordMiss missRow(String headline, String summary) {
		return new MacroKeywordMiss(1L, headline, summary);
	}

	@Test
	void adoptsACorroboratedKeywordAndTheLiveTaggerPicksItUpImmediately() {
		List<MacroKeywordMiss> unreviewed = List.of(
				missRow("Ruritania seizes foreign-owned assets amid crisis", "Markets react sharply"),
				missRow("Ruritania's central bank hikes rates to defend the currency", null),
				missRow("Regional election result in Ruritania stuns investors", "Upset outcome"),
				missRow("AAPL beats quarterly estimates", "Strong iPhone sales"),
				missRow("Unrelated tech earnings beat", "Nothing macro here"));
		when(misses.findByReviewedFalseOrderByDetectedAtAsc()).thenReturn(unreviewed);
		when(keywords.existsById("ruritania")).thenReturn(false);
		when(gateway.generate(anyString()))
				.thenReturn("[{\"keyword\":\"ruritania\",\"why\":\"recurring crisis coverage missed by the list\"}]");
		MacroKeywordLearningProperties props =
				new MacroKeywordLearningProperties(true, true, 5, 2, "0 0 4 * * SUN");

		assertFalse(tagger.isMacroRelevant("Ruritania seizes foreign-owned assets", null));

		Result result = service(props).review();

		assertEquals(1, result.adopted().size());
		assertEquals("ruritania", result.adopted().get(0).keyword());
		assertEquals(3, result.adopted().get(0).corroboration()); // 3 distinct Ruritania stories, verified in code

		ArgumentCaptor<MacroKeyword> saved = ArgumentCaptor.forClass(MacroKeyword.class);
		verify(keywords).save(saved.capture());
		assertEquals("ruritania", saved.getValue().getKeyword());
		assertEquals("learned", saved.getValue().getSource());

		// The live tagger actually picked it up — this is the real integration point, not just reload().
		assertTrue(tagger.isMacroRelevant("Ruritania seizes foreign-owned assets", null));
		// The old list wasn't lost — reload() merged in, it didn't replace.
		assertTrue(tagger.isMacroRelevant("Trump announces new tariffs", null));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<MacroKeywordMiss>> savedMisses = ArgumentCaptor.forClass(List.class);
		verify(misses).saveAll(savedMisses.capture());
		assertTrue(savedMisses.getValue().stream().allMatch(MacroKeywordMiss::isReviewed));
	}

	@Test
	void insufficientMissesSkipsTheReviewAndAdoptsNothing() {
		when(misses.findByReviewedFalseOrderByDetectedAtAsc())
				.thenReturn(List.of(missRow("Ruritania crisis deepens", null)));
		MacroKeywordLearningProperties props =
				new MacroKeywordLearningProperties(true, true, 5, 2, "0 0 4 * * SUN");

		Result result = service(props).review();

		assertTrue(result.adopted().isEmpty());
		assertTrue(result.proposals().isEmpty());
		verify(gateway, never()).generate(anyString());
		verify(keywords, never()).save(any());
	}

	@Test
	void disabledPropertySkipsEntirelyAndTouchesNothing() {
		MacroKeywordLearningProperties off =
				new MacroKeywordLearningProperties(false, true, 5, 2, "0 0 4 * * SUN");

		Result result = service(off).review();

		assertFalse(result.ran());
		verify(misses, never()).findByReviewedFalseOrderByDetectedAtAsc();
	}

	@Test
	void singleCorroboratingMissDoesNotClearTheGateEvenIfModelProposesIt() {
		// 5 unrelated misses (clears minMisses) but only ONE actually mentions "ruritania" — the model
		// might still propose it, but the code-verified corroboration count must block adoption.
		List<MacroKeywordMiss> unreviewed = List.of(
				missRow("Ruritania crisis deepens", null),
				missRow("AAPL beats estimates", null),
				missRow("MSFT cloud growth slows", null),
				missRow("Regional retailer opens new store", null),
				missRow("Unrelated earnings beat", null));
		when(misses.findByReviewedFalseOrderByDetectedAtAsc()).thenReturn(unreviewed);
		when(gateway.generate(anyString())).thenReturn("[{\"keyword\":\"ruritania\",\"why\":\"single mention\"}]");
		MacroKeywordLearningProperties props =
				new MacroKeywordLearningProperties(true, true, 5, 2, "0 0 4 * * SUN");

		Result result = service(props).review();

		assertTrue(result.adopted().isEmpty());
		assertEquals(1, result.proposals().size()); // the model's suggestion is logged...
		assertEquals(1, result.proposals().get(0).corroboration()); // ...but only 1 real corroborating miss
		verify(keywords, never()).save(any());
		assertFalse(tagger.isMacroRelevant("Ruritania crisis deepens", null)); // never went live
	}

	@Test
	void autoApplyOffLogsButNeverTouchesTheLiveKeywordList() {
		List<MacroKeywordMiss> unreviewed = List.of(
				missRow("Ruritania seizes foreign-owned assets amid crisis", null),
				missRow("Ruritania's central bank hikes rates", null),
				missRow("Regional election result in Ruritania stuns investors", null),
				missRow("AAPL beats estimates", null),
				missRow("Unrelated earnings beat", null));
		when(misses.findByReviewedFalseOrderByDetectedAtAsc()).thenReturn(unreviewed);
		when(keywords.existsById("ruritania")).thenReturn(false);
		when(gateway.generate(anyString())).thenReturn("[{\"keyword\":\"ruritania\",\"why\":\"recurring\"}]");
		MacroKeywordLearningProperties shadowMode =
				new MacroKeywordLearningProperties(true, false, 5, 2, "0 0 4 * * SUN");

		Result result = service(shadowMode).review();

		// "adopted" (per the macro_keyword_review schema) means "cleared the gate", distinct from
		// actually being written live — that's what auto-apply governs, and it's off here.
		assertEquals(1, result.adopted().size());
		assertEquals("ruritania", result.adopted().get(0).keyword());
		verify(keywords, never()).save(any());
		assertFalse(tagger.isMacroRelevant("Ruritania seizes foreign-owned assets", null));
	}
}
