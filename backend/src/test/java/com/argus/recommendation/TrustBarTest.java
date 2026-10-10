package com.argus.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TrustBarTest {

	private static final TrustBarProperties BAR = new TrustBarProperties("ACTIVE", 50, 55, 0.22);

	private static TrustBar.View eval(String state, long closed, Integer winRate, Double brier) {
		return TrustBar.evaluate(BAR, new TrustBar.Inputs(state, closed, winRate, brier));
	}

	@Test
	void clearsOnlyWhenEveryCheckPasses() {
		TrustBar.View v = eval("ACTIVE", 60, 61, 0.19);
		assertThat(v.cleared()).isTrue();
		assertThat(v.passed()).isEqualTo(4);
		assertThat(v.headline()).isEqualTo(TrustBar.CLEARED).contains("never places orders");
	}

	@Test
	void notClearedSaysPaperValidationOnly() {
		TrustBar.View v = eval("PROBATION", 60, 61, 0.19);
		assertThat(v.cleared()).isFalse();
		assertThat(v.passed()).isEqualTo(3);
		assertThat(v.headline()).isEqualTo("Paper validation only — trust bar not cleared.");
		assertThat(v.checks()).filteredOn(c -> !c.pass()).extracting(TrustBar.Check::key).containsExactly("state");
	}

	@Test
	void aFrozenOrUnprovenAgentNeverClears() {
		assertThat(eval("FROZEN", 500, 90, 0.05).cleared()).isFalse();
		assertThat(eval("SHADOW", 500, 90, 0.05).cleared()).isFalse();
		assertThat(eval(null, 500, 90, 0.05).checks().get(0).actual()).isEqualTo("UNKNOWN");
	}

	@Test
	void thresholdsAreInclusive() {
		TrustBar.View v = eval("ACTIVE", 50, 55, 0.22);
		assertThat(v.cleared()).isTrue();
		assertThat(eval("ACTIVE", 49, 55, 0.22).cleared()).isFalse();
		assertThat(eval("ACTIVE", 50, 54, 0.22).cleared()).isFalse();
		assertThat(eval("ACTIVE", 50, 55, 0.2201).cleared()).isFalse();
	}

	@Test
	void missingNumbersFailTheirCheckRatherThanPassing() {
		TrustBar.View v = eval("ACTIVE", 0, null, null);
		assertThat(v.cleared()).isFalse();
		assertThat(v.passed()).isEqualTo(1); // only the state check
		assertThat(v.checks()).extracting(TrustBar.Check::actual).containsExactly("ACTIVE", "0", "—", "—");
	}

	@Test
	void stateMatchIgnoresCaseAndThresholdsAreEchoed() {
		assertThat(eval("active", 60, 61, 0.19).cleared()).isTrue();
		assertThat(eval("ACTIVE", 60, 61, 0.19).thresholds()).isEqualTo(BAR);
	}
}
