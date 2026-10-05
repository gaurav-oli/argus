package com.argus.security;

/** Verified identity claims from a Google ID token — only ever constructed after
 * {@link GoogleOAuthService#verify} has checked the signature, issuer, audience and expiry. */
public record GoogleIdentity(String sub, String email, boolean emailVerified, String name, String pictureUrl) {
}
