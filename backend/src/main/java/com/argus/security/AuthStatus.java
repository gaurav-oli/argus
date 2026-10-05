package com.argus.security;

/**
 * Auth state for the frontend's initial routing decision. {@code pinSet}/{@code passkeyEnrolled}/
 * the lockout fields are the retired PIN/WebAuthn flow (kept only so that still-compiling dead code
 * doesn't need touching yet — Google Sign-In is the only flow actually reachable from the UI now);
 * {@code user} is the real multi-user signal and is what the frontend acts on.
 *
 * @param pinSet                  retired — always reflects whatever PIN state happens to exist
 * @param authenticated           whether the caller presented a valid session
 * @param passkeyEnrolled         retired
 * @param fullyLocked             retired
 * @param lockoutSecondsRemaining retired
 * @param user                    the signed-in Google account, or null when not authenticated
 */
public record AuthStatus(
		boolean pinSet,
		boolean authenticated,
		boolean passkeyEnrolled,
		boolean fullyLocked,
		long lockoutSecondsRemaining,
		UserView user) {

	/** What the frontend actually needs about the signed-in person — never their raw Google sub. */
	public record UserView(String name, String email, String pictureUrl, boolean admin) {

		static UserView from(AppUser u) {
			return new UserView(u.getName(), u.getEmail(), u.getPictureUrl(), u.isAdmin());
		}
	}
}
