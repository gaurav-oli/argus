package com.argus.filings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** HTML stripping, section extraction, fact verification and scoring — the deterministic half of the filings reader. */
class FilingsUtilitiesTest {

	// ---- HtmlText ----

	@Test
	void htmlBecomesReadablePlainText() {
		String html = "<html><head><title>x</title><style>p{color:red}</style></head><body><script>var a=1;</script>"
				+ "<p>Revenue was <b>$96.2&nbsp;billion</b>, up 106%&#160;from a year ago.</p><p>It&rsquo;s a record &amp; growing.</p></body></html>";

		String t = HtmlText.toText(html);

		assertTrue(t.contains("Revenue was $96.2 billion, up 106% from a year ago."), t);
		assertTrue(t.contains("It’s a record & growing."), t);
		assertFalse(t.contains("var a=1") || t.contains("color:red") || t.contains("<"), "scripts, styles and tags are gone");
	}

	@Test
	void numericEntitiesAndEmptyInputAreHandled() {
		assertEquals("A—B", HtmlText.toText("A&#8212;B"));
		assertEquals("é", HtmlText.toText("&#xE9;"));
		assertEquals("", HtmlText.toText(null));
		assertEquals("", HtmlText.toText(""));
	}

	// ---- FilingExtractor ----

	@Test
	void aPressReleaseKeepsTheHeadlineResultsAndTheOutlookWindow() {
		String opening = "Company announces record results. Revenue $10.0 billion. ".repeat(200); // ~11k chars
		String filler = "Segment detail and tables. ".repeat(1500);
		String text = opening + filler + "Outlook: For the third quarter, revenue is expected to be $11.0 billion, plus or minus 2%. " + "More text. ".repeat(50);

		String out = FilingExtractor.pressRelease(text, 12_000);

		assertTrue(out.startsWith("Company announces record results."));
		assertTrue(out.contains("Outlook: For the third quarter, revenue is expected to be $11.0 billion"), "the guidance far down the release is included");
		assertTrue(out.length() <= 12_500);
	}

	@Test
	void aShortPressReleaseIsReturnedWhole() {
		assertEquals("Short release. Revenue $1 million.", FilingExtractor.pressRelease("Short release. Revenue $1 million.", 12_000));
	}

	@Test
	void theMdnaIsTheRealSectionNotTheTableOfContentsMention() {
		String toc = "TABLE OF CONTENTS Item 2. Management’s Discussion and Analysis of Financial Condition and Results of Operations 20 "
				+ "Item 3. Quantitative and Qualitative Disclosures About Market Risk 30 ";
		String body = "Item 2. Management’s Discussion and Analysis of Financial Condition and Results of Operations Overview. "
				+ "Revenue increased due to data center demand. ".repeat(80) + " Item 3. Quantitative and Qualitative Disclosures About Market Risk None.";

		String out = FilingExtractor.mdna(toc + "Financial statements ... " + body, 9_000);

		assertTrue(out.contains("Revenue increased due to data center demand"), "picked the long real section");
		assertFalse(out.contains("Item 3. Quantitative"), "stops before the next item");
	}

	@Test
	void riskFactorsAreSkippedWhenThereWereNoMaterialChanges() {
		String same = "Item 1A. Risk Factors There have been no material changes to the risk factors disclosed in our Annual Report. " + "Filler. ".repeat(400)
				+ " Item 2. Unregistered Sales of Equity Securities";
		String changed = "Item 1A. Risk Factors We now depend on a single foundry for advanced packaging. " + "Filler. ".repeat(400) + " Item 2. Unregistered Sales";

		assertEquals("", FilingExtractor.riskFactors(same, 3_000));
		assertTrue(FilingExtractor.riskFactors(changed, 3_000).contains("single foundry"));
		assertEquals("", FilingExtractor.mdna("nothing relevant here", 9_000));
	}

	// ---- FactVerifier ----

	private static final String SOURCE = "NVIDIA reported revenue of $96.2 billion, up 106% from a year ago. GAAP EPS was $2.46. "
			+ "Third-quarter revenue is expected to be $105,000 million, plus or minus 2%.";

	@Test
	void everyNumberInAClaimMustAppearInTheSource() {
		FactVerifier v = new FactVerifier(SOURCE);

		assertTrue(v.supports("$96.2 billion"));
		assertTrue(v.supports("up 106%"));
		assertTrue(v.supports("$105,000 million"), "thousands separators don't matter");
		assertFalse(v.supports("$96.8 billion"), "a transposed digit is caught");
		assertFalse(v.supports("$96.2 billion, up 160%"), "one bad number sinks the whole claim");
		assertFalse(v.supports("record revenue"), "a claim with no number cannot be verified");
	}

	@Test
	void aMetricNeedsItsValueAndItsChangeToBeInTheSource() {
		FactVerifier v = new FactVerifier(SOURCE);

		assertTrue(v.supportsMetric("$96.2 billion", "up 106% from a year ago"));
		assertFalse(v.supportsMetric("$96.2 billion", "up 88% from a year ago"));
		assertTrue(v.supportsMetric("$2.46", ""), "a metric with no stated change is judged on its value");
	}

	@Test
	void goingConcernIsOnlyHonouredWhenTheFilingSaysSo() {
		assertFalse(new FactVerifier(SOURCE).confirmsGoingConcern());
		assertTrue(new FactVerifier("There is substantial doubt about the Company's ability to continue.").confirmsGoingConcern());
		assertTrue(new FactVerifier("...raise substantial doubt...").confirmsGoingConcern());
	}

	@Test
	void guidanceLanguageIsDetected() {
		assertTrue(new FactVerifier(SOURCE).hasGuidanceLanguage());
		assertFalse(new FactVerifier("The company held its annual meeting.").hasGuidanceLanguage());
	}

	// ---- FilingScorer ----

	private static FilingScorer.Fields f(String guidance, String tone, boolean gc, int pos, int con, int risks, boolean earnings) {
		return new FilingScorer.Fields(guidance, tone, gc, pos, con, risks, earnings);
	}

	@Test
	void guidanceAndToneDriveTheScoreByFixedRules() {
		assertEquals(0.75, FilingScorer.score(f("RAISED", "POSITIVE", false, 0, 0, 0, true)), 1e-9);
		assertEquals(-0.9, FilingScorer.score(f("LOWERED", "NEGATIVE", false, 0, 0, 0, true)), 1e-9);
		assertEquals(0.1, FilingScorer.score(f("MAINTAINED", "NEUTRAL", false, 0, 0, 0, true)), 1e-9);
		assertEquals(0.25, FilingScorer.score(f("RAISED", "POSITIVE", false, 0, 0, 0, false)), 1e-9, "a periodic report has no guidance term");
		assertEquals(0.0, FilingScorer.score(f("NONE", "NEUTRAL", false, 0, 0, 0, true)), 1e-9);
	}

	@Test
	void aVerifiedGoingConcernOverridesEverything() {
		assertEquals(-1.0, FilingScorer.score(f("RAISED", "POSITIVE", true, 9, 0, 0, true)), 1e-9);
	}

	@Test
	void concernsBalanceAndNewRisksAreBoundedAndTheScoreNeverLeavesTheRange() {
		assertEquals(0.15, FilingScorer.score(f("NONE", "NEUTRAL", false, 10, 0, 0, true)), 1e-9, "positives count at most +0.15");
		assertEquals(-0.25, FilingScorer.score(f("NONE", "NEUTRAL", false, 0, 10, 4, true)), 1e-9, "-0.15 concerns, -0.1 for 3+ new risks");
		assertTrue(FilingScorer.score(f("LOWERED", "NEGATIVE", false, 0, 10, 5, true)) >= -1.0);
	}

	@Test
	void recentEarningsCountMostAndEverythingFadesWithAge() {
		double fresh = FilingScorer.combined(List.of(new FilingScorer.Aged(0.8, true, 2)));
		double stale = FilingScorer.combined(List.of(new FilingScorer.Aged(0.8, true, 50)));
		double gone = FilingScorer.combined(List.of(new FilingScorer.Aged(0.8, true, 90)));

		assertTrue(fresh > stale && stale > gone);
		assertEquals(0.0, gone, 1e-9, "an earnings release older than its 60-day window carries no weight");
		assertEquals(0.0, FilingScorer.combined(List.of()), 1e-9);
	}
}
