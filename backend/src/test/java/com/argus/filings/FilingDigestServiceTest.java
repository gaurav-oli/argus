package com.argus.filings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.intelligence.KnownUniverse;
import com.argus.model.ModelGateway;
import com.argus.model.ModelTier;
import com.argus.sec.EdgarClient;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class FilingDigestServiceTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private final EdgarClient edgar = mock(EdgarClient.class);
	private final FilingDigestRepository repo = mock(FilingDigestRepository.class);
	private final ModelGateway gateway = mock(ModelGateway.class);
	private final KnownUniverse universe = mock(KnownUniverse.class);
	private final FilingDigestService service = new FilingDigestService(edgar, repo, gateway, universe);

	private static String daysAgo(int n) {
		return LocalDate.now().minusDays(n).toString();
	}

	/** A submissions payload: newest first. Each row: form, daysAgo, accession, primaryDoc, items. */
	private static String submissions(String[]... rows) {
		StringBuilder forms = new StringBuilder(), dates = new StringBuilder(), acc = new StringBuilder(), docs = new StringBuilder(), items = new StringBuilder();
		for (int i = 0; i < rows.length; i++) {
			String sep = i == 0 ? "" : ",";
			forms.append(sep).append('"').append(rows[i][0]).append('"');
			dates.append(sep).append('"').append(daysAgo(Integer.parseInt(rows[i][1]))).append('"');
			acc.append(sep).append('"').append(rows[i][2]).append('"');
			docs.append(sep).append('"').append(rows[i][3]).append('"');
			items.append(sep).append('"').append(rows[i][4]).append('"');
		}
		return "{\"filings\":{\"recent\":{\"form\":[" + forms + "],\"filingDate\":[" + dates + "],\"accessionNumber\":[" + acc + "],\"primaryDocument\":["
				+ docs + "],\"items\":[" + items + "]}}}";
	}

	private static final String PRESS = "<html><body><p>ACME reported revenue of $96.2 billion, up 106% from a year ago. GAAP EPS was $2.46.</p>"
			+ "<p>Outlook: revenue for the third quarter is expected to be $105.0 billion, plus or minus 2%.</p></body></html>";

	private static String extraction(String guidance, String detail, String metricValue, String change, boolean goingConcern) {
		return "{\"summary\":\"Record quarter.\",\"guidance\":\"" + guidance + "\",\"guidanceDetail\":\"" + detail + "\",\"tone\":\"POSITIVE\","
				+ "\"keyMetrics\":[{\"label\":\"Revenue\",\"value\":\"" + metricValue + "\",\"change\":\"" + change + "\"}],"
				+ "\"positives\":[\"a\",\"b\"],\"concerns\":[],\"newRisks\":[],\"goingConcern\":" + goingConcern + "}";
	}

	// ---- reading EDGAR ----

	@Test
	void onlyRecentEarningsReleasesAndPeriodicReportsAreSelectedNewestFirst() {
		String json = submissions(
				new String[] {"8-K", "3", "A3", "d.htm", "8.01"},         // not earnings
				new String[] {"8-K", "20", "A2", "d.htm", "2.02,9.01"},   // earnings release
				new String[] {"10-Q", "22", "A1", "q.htm", ""},
				new String[] {"8-K", "60", "A0", "d.htm", "2.02"},        // older earnings — superseded
				new String[] {"10-K", "400", "AX", "k.htm", ""});         // too old

		List<FilingDigestService.Ref> refs = FilingDigestService.refs(json, LocalDate.now());
		List<FilingDigestService.Ref> pick = FilingDigestService.newestPerKind(refs);

		assertEquals(List.of("A2", "A1", "A0"), refs.stream().map(FilingDigestService.Ref::accession).toList());
		assertEquals(List.of("A2", "A1"), pick.stream().map(FilingDigestService.Ref::accession).toList(), "one earnings release + one periodic report");
	}

	@Test
	void theExhibitIsTheLargestNonCoverHtmlDocument() {
		String index = "{\"directory\":{\"item\":[{\"name\":\"acme-20260826.htm\",\"size\":\"26457\"},{\"name\":\"q2fy27pr.htm\",\"size\":\"341233\"},"
				+ "{\"name\":\"R1.htm\",\"size\":\"9999999\"},{\"name\":\"acme.xsd\",\"size\":\"2125\"},{\"name\":\"0001-index.html\",\"size\":\"5000\"}]}}";

		assertEquals("q2fy27pr.htm", FilingDigestService.pickExhibit(index, "acme-20260826.htm"));
		assertNull(FilingDigestService.pickExhibit("{\"directory\":{\"item\":[{\"name\":\"acme.xsd\"}]}}", "x.htm"));
	}

	// ---- code verifies ----

	private static FilingDigest build(String modelJson) {
		return FilingDigestService.build("ACME", new FilingDigestService.Ref("8-K", LocalDate.now(), "ACC1", "d.htm", "2.02"),
				FilingDigest.Kind.EARNINGS_RELEASE, HtmlText.toText(PRESS), JSON.readTree(modelJson));
	}

	@Test
	void figuresTheModelInventedAreDiscardedAndCounted() {
		FilingDigest d = build(extraction("RAISED", "$999.0 billion", "$96.8 billion", "up 106%", false));

		assertEquals(0, d.getVerifiedFacts());
		assertEquals(2, d.getDroppedFacts(), "the wrong metric and the invented guidance figure were both dropped");
		assertEquals("", d.getGuidanceDetail());
		assertFalse(d.getPayload().contains("96.8"), "the unverified figure never reaches storage");
	}

	@Test
	void figuresThatAreInTheFilingSurviveAndDriveTheScore() {
		FilingDigest d = build(extraction("RAISED", "$105.0 billion", "$96.2 billion", "up 106% from a year ago", false));

		assertEquals(2, d.getVerifiedFacts());
		assertEquals(0, d.getDroppedFacts());
		assertEquals("$105.0 billion", d.getGuidanceDetail());
		assertEquals(0.5 + 0.25 + 0.05 * 2 > 0.15 ? 0.5 + 0.25 + 0.10 : 0, d.getScore(), 1e-9, "raised guidance + positive tone + 2 positives (capped +0.10)");
	}

	@Test
	void aGoingConcernFlagTheFilingDoesNotSupportIsIgnored() {
		FilingDigest d = build(extraction("NONE", "", "$96.2 billion", "up 106%", true));

		assertTrue(d.getScore() > 0, "the model cried going-concern on a record quarter — the filing has no such language, so it is ignored");
		assertTrue(d.getPayload().contains("\"goingConcern\":false"));
	}

	@Test
	void aRealGoingConcernDoubtIsHonouredAndScoredAsSuch() {
		String source = "There is substantial doubt about our ability to continue as a going concern. Revenue $5 million.";
		FilingDigest d = FilingDigestService.build("ACME", new FilingDigestService.Ref("10-Q", LocalDate.now(), "ACC2", "q.htm", ""),
				FilingDigest.Kind.QUARTERLY_REPORT, source, JSON.readTree(extraction("NONE", "", "$5 million", "", true)));

		assertEquals(-1.0, d.getScore(), 1e-9);
	}

	@Test
	void aGuidanceCallWithNoFiguresAndNoGuidanceLanguageIsNotTrusted() {
		FilingDigest d = FilingDigestService.build("ACME", new FilingDigestService.Ref("10-Q", LocalDate.now(), "ACC3", "q.htm", ""),
				FilingDigest.Kind.QUARTERLY_REPORT, "The company held its annual meeting of shareholders.", JSON.readTree(extraction("RAISED", "", "", "", false)));

		assertEquals("NONE", d.getGuidance());
	}

	@Test
	void garbageEnumsFromTheModelFallBackToSafeDefaults() {
		FilingDigest d = build("{\"summary\":\"x\",\"guidance\":\"AMAZING\",\"tone\":\"ECSTATIC\",\"keyMetrics\":[],\"positives\":[],\"concerns\":[],\"newRisks\":[]}");

		assertEquals("NONE", d.getGuidance());
		assertEquals("NEUTRAL", d.getTone());
	}

	// ---- end to end ----

	private void stubEdgar(String submissionsJson) {
		when(edgar.cikOf("ACME")).thenReturn("0000123456");
		when(edgar.fetchText(contains("submissions/CIK0000123456"))).thenReturn(Optional.of(submissionsJson));
		when(edgar.fetchText(contains("/index.json"))).thenReturn(Optional.of("{\"directory\":{\"item\":[{\"name\":\"d.htm\",\"size\":\"100\"},{\"name\":\"ex99.htm\",\"size\":\"5000\"}]}}"));
		when(edgar.fetchText(contains("ex99.htm"))).thenReturn(Optional.of(PRESS));
	}

	@Test
	void refreshDigestsANewEarningsReleaseOnceAndOnlyOnce() {
		stubEdgar(submissions(new String[] {"8-K", "5", "0001234567-26-000001", "d.htm", "2.02,9.01"}));
		when(repo.existsByAccession("0001234567-26-000001")).thenReturn(false, true);
		when(gateway.generate(anyString(), eq(ModelTier.BIG))).thenReturn(extraction("RAISED", "$105.0 billion", "$96.2 billion", "up 106% from a year ago", false));
		when(repo.save(any(FilingDigest.class))).thenAnswer(inv -> inv.getArgument(0));

		List<FilingDigest> first = service.refresh("acme");
		List<FilingDigest> second = service.refresh("ACME");

		assertEquals(1, first.size());
		assertEquals(FilingDigest.Kind.EARNINGS_RELEASE, first.get(0).getKind());
		assertEquals("RAISED", first.get(0).getGuidance());
		assertTrue(second.isEmpty(), "already digested (by accession) → no second model call");
		verify(gateway, times(1)).generate(anyString(), any());
		verify(repo, times(1)).save(any(FilingDigest.class));
	}

	@Test
	void theModelIsToldToExtractOnlyAndNotToRememberTheCompany() {
		stubEdgar(submissions(new String[] {"8-K", "5", "0001234567-26-000001", "d.htm", "2.02"}));
		when(repo.existsByAccession(anyString())).thenReturn(false);
		when(gateway.generate(anyString(), any())).thenReturn(extraction("NONE", "", "$96.2 billion", "up 106%", false));
		when(repo.save(any(FilingDigest.class))).thenAnswer(inv -> inv.getArgument(0));

		service.refresh("ACME");

		org.mockito.ArgumentCaptor<String> prompt = org.mockito.ArgumentCaptor.forClass(String.class);
		verify(gateway).generate(prompt.capture(), any());
		assertTrue(prompt.getValue().contains("Use ONLY the text below") && prompt.getValue().contains("do not use anything you remember"));
		assertTrue(prompt.getValue().contains("Outlook: revenue for the third quarter"), "the outlook paragraph was passed to the model");
	}

	@Test
	void etfsAndUnknownFilersAreSkippedWithoutTouchingEdgarOrTheModel() {
		when(edgar.cikOf("VFV")).thenReturn(null);

		assertTrue(service.refresh("VFV").isEmpty());
		verify(edgar, never()).fetchText(anyString());
		verify(gateway, never()).generate(anyString(), any());
	}

	@Test
	void aModelThatNeverReturnsJsonYieldsNothingRatherThanAGuess() {
		stubEdgar(submissions(new String[] {"8-K", "5", "0001234567-26-000001", "d.htm", "2.02"}));
		when(repo.existsByAccession(anyString())).thenReturn(false);
		when(gateway.generate(anyString(), any())).thenReturn("I think it went well!");

		assertTrue(service.refresh("ACME").isEmpty());
		verify(repo, never()).save(any(FilingDigest.class));
	}

	// ---- reading back ----

	private static FilingDigest stored(FilingDigest.Kind kind, int daysAgo, double score, String guidance) {
		return new FilingDigest("ACME", "ACC-" + kind + daysAgo, kind == FilingDigest.Kind.EARNINGS_RELEASE ? "8-K" : "10-Q", kind,
				LocalDate.now().minusDays(daysAgo), "summary " + kind, guidance, "", "POSITIVE", score, "{}", 2, 0, 1000, "m");
	}

	@Test
	void theViewCombinesTheNewestEarningsAndPeriodicDigestsWithAgeDecay() {
		List<FilingDigest> rows = new ArrayList<>(List.of(stored(FilingDigest.Kind.EARNINGS_RELEASE, 4, 0.8, "RAISED"),
				stored(FilingDigest.Kind.QUARTERLY_REPORT, 6, 0.2, "NONE"), stored(FilingDigest.Kind.EARNINGS_RELEASE, 95, -0.9, "LOWERED")));
		when(repo.findTop12ByTickerOrderByFiledAtDesc("ACME")).thenReturn(rows);

		FilingView v = service.view("acme").orElseThrow();

		assertEquals("RAISED", v.guidance(), "the NEWEST earnings release leads, not the stale one");
		assertTrue(v.score() > 0.4 && v.score() < 0.8, "0.7×fresh 0.8 + 0.3×fresh 0.2, normalised: " + v.score());
		assertEquals(3, v.digests().size());
		assertEquals(4, v.ageDays());
		assertEquals(LocalDate.now().minusDays(4), v.latestEarningsDate());
	}

	@Test
	void noStoredDigestsMeansNoView() {
		when(repo.findTop12ByTickerOrderByFiledAtDesc("ACME")).thenReturn(List.of());

		assertTrue(service.view("ACME").isEmpty());
	}
}
