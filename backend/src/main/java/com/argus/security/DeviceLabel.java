package com.argus.security;

/**
 * Derives a friendly device label from a User-Agent for the active-sessions list (Story 2.7).
 * Best-effort and coarse — enough to tell an iPhone from a Mac in the session manager.
 */
public final class DeviceLabel {

	private DeviceLabel() {
	}

	public static String from(String userAgent) {
		if (userAgent == null || userAgent.isBlank()) {
			return "Unknown device";
		}
		String ua = userAgent;
		if (ua.contains("iPhone")) return "iPhone";
		if (ua.contains("iPad")) return "iPad";
		if (ua.contains("Android")) return "Android";
		if (ua.contains("Macintosh") || ua.contains("Mac OS")) return "Mac";
		if (ua.contains("Windows")) return "Windows";
		if (ua.contains("Linux")) return "Linux";
		return "Unknown device";
	}
}
