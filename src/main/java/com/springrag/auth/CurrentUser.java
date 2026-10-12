package com.springrag.auth;

import org.springframework.security.oauth2.jwt.Jwt;

/** Reads the authenticated user id from the validated JWT subject. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static long id(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
