package com.argus.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StyleFitTest {

	private static void add(List<StyleFit.ClosedTrade> out, int n, boolean won, String... tokens) {
		for (int i = 0; i < n; i++) out.add(new StyleFit.ClosedTrade(Set.of(tokens), won, won ? BigDecimal.TWO : BigDecimal.ONE.negate()));
	}

	/** NEWS-led: great on high-vol names (10 of 12), poor on low-vol (3 of 12) — 13 of 24 = 54% overall. */
	private static List<StyleFit.ClosedTrade> library() {
		List<StyleFit.ClosedTrade> t = new ArrayList<>();
		add(t, 10, true, "lead=NEWS", "vol=high", "sector=TECH");
		add(t, 2, false, "lead=NEWS", "vol=high", "sector=TECH");
		add(t, 3, true, "lead=NEWS", "vol=low", "sector=ENERGY");
		add(t, 9, false, "lead=NEWS", "vol=low", "sector=ENERGY");
		add(t, 4, true, "lead=DEEP", "vol=high");
		add(t, 3, false, "dir=BULLISH"); // no playbook: ignored
		return t;
	}

	@Test
	void theMatrixCountsEachPlaybookByStyleBucketWithASampleGuard() {
		StyleFit.Matrix m = StyleFit.matrix(library());
		assertEquals("NEWS", m.families().get(0).family());
		assertEquals(24, m.families().get(0).trades());
		assertEquals(54, m.families().get(0).winRatePct());
		StyleFit.Cell highVol = m.cells().stream()
				.filter(c -> c.family().equals("NEWS") && c.dimension().equals("vol") && c.bucket().equals("high")).findFirst().orElseThrow();
		assertEquals(12, highVol.trades());
		assertEquals(83, highVol.winRatePct());
		assertTrue(highVol.enough());
		StyleFit.Cell deep = m.cells().stream().filter(c -> c.family().equals("DEEP")).findFirst().orElseThrow();
		assertFalse(deep.enough(), "4 trades is under the guard");
	}

	@Test
	void aGoodFitSizesUp() {
		StyleFit.Fit f = StyleFit.fitFor(Set.of("lead=NEWS", "vol=high", "sector=TECH"), StyleFit.matrix(library()));
		assertEquals(1.25, f.multiplier());
		assertEquals(29, f.gapPoints());
		assertTrue(f.note().contains("NEWS-led calls win 83% on"), f.note());
		assertTrue(f.note().endsWith("good fit, ×1.25."), f.note());
	}

	@Test
	void aPoorFitSizesDown() {
		StyleFit.Fit f = StyleFit.fitFor(Set.of("lead=NEWS", "vol=low", "sector=ENERGY"), StyleFit.matrix(library()));
		assertEquals(0.75, f.multiplier());
	}

	@Test
	void tooLittleSampleOrNoPlaybookMeansNoTilt() {
		StyleFit.Matrix m = StyleFit.matrix(library());
		assertEquals(1.0, StyleFit.fitFor(Set.of("lead=DEEP", "vol=high"), m).multiplier());
		assertEquals(1.0, StyleFit.fitFor(Set.of("lead=NEWS", "vol=mid"), m).multiplier());
		assertEquals(1.0, StyleFit.fitFor(Set.of("lead=MACRO"), m).multiplier());
		StyleFit.Fit none = StyleFit.fitFor(Set.of("vol=high"), m);
		assertNull(none.family());
		assertEquals(1.0, none.multiplier());
		assertEquals(1.0, StyleFit.fitFor(Set.of("lead=NEWS", "vol=high"), StyleFit.matrix(List.of())).multiplier());
	}
}
