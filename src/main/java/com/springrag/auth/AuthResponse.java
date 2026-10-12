package com.springrag.auth;

/**
 * @param token                the access token; absent when the e-mail address must be verified first
 * @param verificationRequired true (only then present) when a verification e-mail was sent and sign-in is blocked until it is used
 */
public record AuthResponse(String token, String email, Boolean verificationRequired) {

    static AuthResponse signedIn(String token, String email) {
        return new AuthResponse(token, email, null);
    }
}
