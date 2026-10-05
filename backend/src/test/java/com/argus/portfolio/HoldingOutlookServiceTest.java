package com.argus.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.argus.deepanalysis.DeepAnalysisService;
import com.argus.recommendation.ProbabilityScore;
import com.argus.recommendation.Recommendation;
import com.argus.recommendation.RecommendationAction;
import com.argus.recommendation.RecommendationPolicy;
import com.argus.recommendation.RecommendationRepository;
import com.argus.technical.ChartStudyService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Reframes the EXISTING recommendation machinery against what this person actually owns — no new
 * model calls, no new persistence; see {@link com.argus.recommendation.HoldingOutlookTest} for the
 * pure classification logic itself. */
class HoldingOutlookServiceTest {

	private final PositionRepository positions = mock(PositionRepository.class);
	private final AccountMetaRepository accountMeta = mock(AccountMetaRepository.class);
	private final RecommendationRepository recommendations = mock(RecommendationRepository.class);
	private final DeepAnalysisService deepAnalyses = mock(DeepAnalysisService.class);
	private final ChartStudyService charts = mock(ChartStudyService.class);
	private final LivePortfolioService livePrices = mock(LivePortfolioService.class);
	private final HoldingOutlookService service =
			new HoldingOutlookService(positions, accountMeta, recommendations, deepAnalyses, charts, livePrices);

	private static Recommendation coreHoldRecommendation() {
		ProbabilityScore score = new ProbabilityScore(0.62, 0.38, 0.55, 1.8, 1.1, List.of());
		Recommendation r = new Recommendation("AAPL", score, List.of(), null, "9m");
		RecommendationPolicy.Verdict verdict = new RecommendationPolicy.Verdict(RecommendationAction.BUY, 80, 180,
				"9 months", "Long-term compounder", List.of(), List.of(), null, Set.of(), List.of());
		r.applyVerdict(verdict, null);
		return r;
	}

	@Test
	void registeredAccountHoldingGetsTheCraCautionNote() {
		Position p = new Position("AAPL", "Apple", BigDecimal.TEN, BigDecimal.valueOf(1000), "USD",
				LocalDate.now(), false, "manual");
		p.setBankAccount("RBC", "12345 TFSA");
		when(positions.findAllByOrderByTickerAsc()).thenReturn(List.of(p));
		when(accountMeta.findAll())
				.thenReturn(List.of(new AccountMeta("RBC", "12345 TFSA", "Solo", "Me", "TFSA")));
		when(recommendations.findFirstByTickerOrderByCreatedAtDescIdDesc("AAPL")).thenReturn(Optional.empty());

		HoldingOutlookService.HoldingOutlookView view = service.outlooks().get(0);

		assertTrue(view.registeredAccount());
		assertNotNull(view.registeredAccountNote());
		assertTrue(view.registeredAccountNote().contains("TFSA"), view.registeredAccountNote());
	}

	@Test
	void cashAccountHoldingGetsNoRegisteredAccountNote() {
		Position p = new Position("AAPL", "Apple", BigDecimal.TEN, BigDecimal.valueOf(1000), "USD",
				LocalDate.now(), false, "manual");
		p.setBankAccount("RBC", "12345 Cash");
		when(positions.findAllByOrderByTickerAsc()).thenReturn(List.of(p));
		when(accountMeta.findAll())
				.thenReturn(List.of(new AccountMeta("RBC", "12345 Cash", "Solo", "Me", "Cash")));
		when(recommendations.findFirstByTickerOrderByCreatedAtDescIdDesc("AAPL")).thenReturn(Optional.empty());

		HoldingOutlookService.HoldingOutlookView view = service.outlooks().get(0);

		assertFalse(view.registeredAccount());
		assertNull(view.registeredAccountNote());
	}

	@Test
	void noRecommendationYetIsNotEnoughDataWithNoNudge() {
		Position p = new Position("ZZZZ", null, BigDecimal.TEN, BigDecimal.TEN, "USD", LocalDate.now(), false, "manual");
		when(positions.findAllByOrderByTickerAsc()).thenReturn(List.of(p));
		when(recommendations.findFirstByTickerOrderByCreatedAtDescIdDesc("ZZZZ")).thenReturn(Optional.empty());

		HoldingOutlookService.HoldingOutlookView view = service.outlooks().get(0);

		assertEquals("NOT_ENOUGH_DATA", view.outlook());
		assertNull(view.updateNudge(), "no 'keep' thesis yet, so nothing to nudge about");
	}

	@Test
	void aStaleOldRecommendationIsTreatedAsNotEnoughDataRatherThanLeanedOn() {
		Position p = new Position("AAPL", "Apple", BigDecimal.TEN, BigDecimal.TEN, "USD", LocalDate.now(), false, "manual");
		when(positions.findAllByOrderByTickerAsc()).thenReturn(List.of(p));
		Recommendation stale = coreHoldRecommendation(); // createdAt defaults to "now" at construction...
		setCreatedAt(stale, Instant.now().minus(java.time.Duration.ofDays(45))); // ...then backdated past STALE_AFTER
		when(recommendations.findFirstByTickerOrderByCreatedAtDescIdDesc("AAPL")).thenReturn(Optional.of(stale));

		HoldingOutlookService.HoldingOutlookView view = service.outlooks().get(0);

		assertEquals("NOT_ENOUGH_DATA", view.outlook());
	}

	@Test
	void aLongHeldKeepPositionNotTouchedInAWhileGetsAnUpdateNudge() {
		Position p = new Position("AAPL", "Apple", BigDecimal.TEN, BigDecimal.valueOf(1000), "USD",
				LocalDate.now().minusMonths(6), false, "manual");
		setUpdatedAt(p, Instant.now().minus(java.time.Duration.ofDays(30)));
		when(positions.findAllByOrderByTickerAsc()).thenReturn(List.of(p));
		when(recommendations.findFirstByTickerOrderByCreatedAtDescIdDesc("AAPL"))
				.thenReturn(Optional.of(coreHoldRecommendation()));
		when(deepAnalyses.viewFor(anyString())).thenReturn(Optional.empty());
		when(charts.studyFor(anyString())).thenReturn(Optional.empty());
		when(livePrices.latestPrice(anyString())).thenReturn(Optional.of(BigDecimal.valueOf(150)));

		HoldingOutlookService.HoldingOutlookView view = service.outlooks().get(0);

		assertEquals("KEEP", view.outlook());
		assertNotNull(view.updateNudge());
		assertTrue(view.updateNudge().contains("AAPL"), view.updateNudge());
	}

	@Test
	void aFreshlyUpdatedKeepPositionGetsNoNudgeYet() {
		Position p = new Position("AAPL", "Apple", BigDecimal.TEN, BigDecimal.valueOf(1000), "USD",
				LocalDate.now(), false, "manual"); // updatedAt defaults to "now"
		when(positions.findAllByOrderByTickerAsc()).thenReturn(List.of(p));
		when(recommendations.findFirstByTickerOrderByCreatedAtDescIdDesc("AAPL"))
				.thenReturn(Optional.of(coreHoldRecommendation()));
		when(deepAnalyses.viewFor(anyString())).thenReturn(Optional.empty());
		when(charts.studyFor(anyString())).thenReturn(Optional.empty());
		when(livePrices.latestPrice(anyString())).thenReturn(Optional.of(BigDecimal.valueOf(150)));

		HoldingOutlookService.HoldingOutlookView view = service.outlooks().get(0);

		assertEquals("KEEP", view.outlook());
		assertNull(view.updateNudge());
	}

	@Test
	void holdingTheSameTickerAcrossTwoAccountsOnlyReadsTheRecommendationOnce() {
		Position a = new Position("AAPL", "Apple", BigDecimal.ONE, BigDecimal.TEN, "USD", LocalDate.now(), false, "manual");
		a.setBankAccount("RBC", "111 TFSA");
		Position b = new Position("AAPL", "Apple", BigDecimal.ONE, BigDecimal.TEN, "USD", LocalDate.now(), false, "manual");
		b.setBankAccount("TD", "222 Cash");
		when(positions.findAllByOrderByTickerAsc()).thenReturn(List.of(a, b));
		when(recommendations.findFirstByTickerOrderByCreatedAtDescIdDesc("AAPL")).thenReturn(Optional.empty());

		List<HoldingOutlookService.HoldingOutlookView> views = service.outlooks();

		assertEquals(2, views.size());
		org.mockito.Mockito.verify(recommendations, org.mockito.Mockito.times(1))
				.findFirstByTickerOrderByCreatedAtDescIdDesc("AAPL");
	}

	private static void setCreatedAt(Recommendation r, Instant at) {
		setField(r, "createdAt", at);
	}

	private static void setUpdatedAt(Position p, Instant at) {
		setField(p, "updatedAt", at);
	}

	private static void setField(Object target, String field, Object value) {
		try {
			var f = target.getClass().getDeclaredField(field);
			f.setAccessible(true);
			f.set(target, value);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
