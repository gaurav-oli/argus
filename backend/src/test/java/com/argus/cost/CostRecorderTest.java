package com.argus.cost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** Per-call Haiku cost math (Story 7.3): input $1 / output $5 per million tokens. */
class CostRecorderTest {

	@SuppressWarnings("unchecked")
	private static CostRecorder newRecorder() {
		// No persistence in unit tests — the ObjectProvider yields null, so record() stays in-memory.
		return new CostRecorder(mock(ObjectProvider.class));
	}

	@Test
	void computesCostFromTokenUsage() {
		CostRecorder recorder = newRecorder();

		// 1000 input × $1/MTok = $0.001 ; 500 output × $5/MTok = $0.0025 ; total $0.0035
		double cost = recorder.record("claude-haiku-4-5-20251001", 1000, 500);

		assertEquals(0.0035, cost, 1e-9);
		assertEquals(0.0035, recorder.lastCostUsd(), 1e-9);
	}

	@Test
	void accumulatesTotalAcrossCalls() {
		CostRecorder recorder = newRecorder();
		recorder.record("m", 1000, 0); // $0.001
		recorder.record("m", 0, 1000); // $0.005

		assertEquals(0.006, recorder.totalUsd(), 1e-9);
	}
}
