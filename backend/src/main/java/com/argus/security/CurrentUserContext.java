package com.argus.security;

/**
 * The signed-in person's id for the CURRENT thread, or {@code null} when nobody is signed in (a
 * background job acting on its own, not on anyone's behalf). {@link SessionAuthFilter} sets this for
 * the lifetime of every authenticated HTTP request; {@link #runAs} does the same for a scheduled job
 * that must act as one specific person (e.g. generating that person's own morning briefing).
 *
 * <p>This is what {@code PortfolioTenantResolver} reads to scope every {@code @TenantId}-annotated
 * portfolio entity (Phase 2 multi-user) — the one piece of request/job state that makes per-user
 * financial isolation automatic instead of something every query has to remember to do.
 */
public final class CurrentUserContext {

	private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

	private CurrentUserContext() {
	}

	/** Set only by {@link SessionAuthFilter} (request scope) or {@link #runAs} (job scope). */
	static void set(Long userId) {
		CURRENT.set(userId);
	}

	/** Always called from a {@code finally}, by the same two callers as {@link #set}. */
	static void clear() {
		CURRENT.remove();
	}

	/** The current thread's signed-in user id, or {@code null} if none. */
	public static Long get() {
		return CURRENT.get();
	}

	/**
	 * Run {@code action} as if {@code userId} were the signed-in user on this thread, restoring
	 * whatever was there before (nested use, and reuse of a pooled/virtual thread, both work). Use this
	 * for scheduled jobs that must read/write one specific person's portfolio data outside of any HTTP
	 * request — never to bypass isolation for a job that genuinely needs to act across everyone, which
	 * belongs in a native query instead (see {@code PositionRepository.allTickersAcrossAllUsers}).
	 */
	public static void runAs(Long userId, Runnable action) {
		callAs(userId, () -> {
			action.run();
			return null;
		});
	}

	/** Same as {@link #runAs}, for an action that returns a value. */
	public static <T> T callAs(Long userId, java.util.function.Supplier<T> action) {
		Long previous = CURRENT.get();
		set(userId);
		try {
			return action.get();
		} finally {
			if (previous == null) {
				clear();
			} else {
				set(previous);
			}
		}
	}
}
