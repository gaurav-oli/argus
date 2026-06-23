package com.argus.model;

/**
 * Which model tier a caller wants (Decision 1, tiered model governance).
 *
 * <ul>
 *   <li>{@link #SMALL} — the always-resident high-frequency model (Gemma E4B / Llama 3.2 3B) used by
 *       Agents 1/2/3. Not serialized and never falls back to paid Haiku, so high-volume calls stay
 *       cheap and parallel; the caller handles failures.</li>
 *   <li>{@link #BIG} — the on-demand 26B workhorse (Ask AI, Personas, Agents 4/5/6). Serialized at
 *       concurrency 1 with a Haiku fallback on failure.</li>
 * </ul>
 */
public enum ModelTier {
	SMALL,
	BIG
}
