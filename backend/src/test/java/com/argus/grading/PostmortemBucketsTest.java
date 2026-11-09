package com.argus.grading;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.argus.grading.PostmortemBuckets.Bucket;
import org.junit.jupiter.api.Test;

class PostmortemBucketsTest {

	@Test
	void lostLessThanTheMarketIsAWin() {
		// The trade lost 3% while SPY lost 8% → +5 vs SPY → a win.
		assertEquals(Bucket.GOOD_RESULT, PostmortemBuckets.classify(5.0, null, true, false));
	}

	@Test
	void winOnWrongReasonsIsLucky() {
		assertEquals(Bucket.LUCKY_WIN, PostmortemBuckets.classify(2.0, 3.0, false, false));
	}

	@Test
	void winWithUngradedReasonsIsNotCalledLucky() {
		assertEquals(Bucket.GOOD_RESULT, PostmortemBuckets.classify(2.0, null, null, false));
	}

	@Test
	void holdingWouldHaveWonMeansBadExecution() {
		assertEquals(Bucket.BAD_EXECUTION, PostmortemBuckets.classify(-2.0, 4.0, true, true));
	}

	@Test
	void shockLossIsBadLuck() {
		assertEquals(Bucket.BAD_LUCK, PostmortemBuckets.classify(-2.0, -5.0, true, true));
	}

	@Test
	void everythingElseIsABadIdea() {
		assertEquals(Bucket.BAD_IDEA, PostmortemBuckets.classify(-2.0, -5.0, true, false));
		assertEquals(Bucket.BAD_IDEA, PostmortemBuckets.classify(0.0, null, null, false));
	}
}
