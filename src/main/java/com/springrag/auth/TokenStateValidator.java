package com.springrag.auth;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * A valid signature is not enough: the user must still exist, be verified, and the token must carry the user's
 * current token version. This is what makes "sign out everywhere", password changes and resets take effect at once.
 * It costs one primary-key lookup per request.
 */
class TokenStateValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2TokenValidatorResult REVOKED = OAuth2TokenValidatorResult.failure(
            new OAuth2Error("invalid_token", "The token is no longer valid.", null));

    private final JdbcTemplate jdbc;

    TokenStateValidator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        long userId;
        try {
            userId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            return REVOKED;
        }
        Object claim = jwt.getClaim(JwtService.VERSION_CLAIM);
        if (!(claim instanceof Number number)) {
            return REVOKED;
        }
        long version = number.longValue();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT token_version, email_verified FROM users WHERE id = ?", userId);
        if (rows.isEmpty()) {
            return REVOKED;
        }
        Map<String, Object> row = rows.get(0);
        boolean sameVersion = ((Number) row.get("token_version")).longValue() == version;
        boolean verified = Boolean.TRUE.equals(row.get("email_verified"));
        return sameVersion && verified ? OAuth2TokenValidatorResult.success() : REVOKED;
    }
}
