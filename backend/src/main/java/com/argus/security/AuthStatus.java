package com.argus.security;

/**
 * Auth state for the frontend's initial routing decision.
 *
 * @param authenticated whether the caller presented a valid session
 * @param user          the signed-in Google account, or null when not authenticated
 */
public record AuthStatus(boolean authenticated, UserView user) {

	/** What the frontend actually needs about the signed-in person — never their raw Google sub. */
	public record UserView(String name, String email, String pictureUrl, boolean admin) {

		static UserView from(AppUser u) {
			return new UserView(u.getName(), u.getEmail(), u.getPictureUrl(), u.isAdmin());
		}
	}
}
