package com.argus.portfolio;

import java.util.List;

/**
 * Response of a statement upload (Story 3.1): the staged import's id + status, an optional
 * top-level note, and the parsed holdings preview. The holdings are NOT yet persisted as positions
 * — the user confirms the batch to commit them.
 */
public record ImportPreview(
		long importId,
		String filename,
		String status,
		String message,
		List<ParsedHolding> holdings,
		/** False when {@link HoldingSanity} dropped or flagged anything — then it is never auto-applied. */
		boolean checksPassed) {

	public ImportPreview(long importId, String filename, String status, String message, List<ParsedHolding> holdings) {
		this(importId, filename, status, message, holdings, true);
	}
}
