package com.argus.recommendation;

/**
 * Agent 5's trust level (Story 6.6, FR-11).
 *
 * <ul>
 *   <li>{@link #SHADOW} — paper-trading only, proving itself; recommendations are not surfaced as actionable.</li>
 *   <li>{@link #PROBATION} — surfaced but carrying an UNVALIDATED badge.</li>
 *   <li>{@link #ACTIVE} — fully trusted.</li>
 *   <li>{@link #FROZEN} — a serious-failure pattern halted it: no new recommendations until reviewed.</li>
 * </ul>
 */
public enum GraduationState {
	SHADOW,
	PROBATION,
	ACTIVE,
	FROZEN;

	/** Whether Agent 5 may surface new recommendations to the user in this state. */
	public boolean canRecommend() {
		return this == PROBATION || this == ACTIVE;
	}

	/** The badge to show on cards from this state, or null when none applies. */
	public String badge() {
		return switch (this) {
			case PROBATION, SHADOW -> "UNPROVEN";
			case FROZEN -> "FROZEN";
			case ACTIVE -> null;
		};
	}
}
