package com.springrag.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * One-time tokens for e-mail verification and password reset. The raw token only ever exists in the e-mail;
 * the database holds its SHA-256, so a leaked database cannot be used to take over accounts.
 */
@Service
public class AuthTokenService {

    public enum Type { VERIFY_EMAIL, RESET_PASSWORD }

    static final Duration VERIFY_TTL = Duration.ofHours(24);
    static final Duration RESET_TTL = Duration.ofMinutes(30);

    private final SecureRandom random = new SecureRandom();
    private final JdbcTemplate jdbc;

    public AuthTokenService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates a token and invalidates the user's earlier unused tokens of the same type. */
    public String create(long userId, Type type) {
        jdbc.update("UPDATE auth_tokens SET used_at = now() WHERE user_id = ? AND type = ? AND used_at IS NULL",
                userId, type.name());
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Duration ttl = type == Type.VERIFY_EMAIL ? VERIFY_TTL : RESET_TTL;
        jdbc.update("INSERT INTO auth_tokens (user_id, type, token_hash, expires_at) VALUES (?, ?, ?, now() + make_interval(secs => ?))",
                userId, type.name(), hash(token), (double) ttl.toSeconds());
        return token;
    }

    /** Atomically marks a valid, unexpired, unused token as used and returns its user id. */
    public Optional<Long> consume(String token, Type type) {
        List<Long> ids = jdbc.queryForList("""
                UPDATE auth_tokens SET used_at = now()
                WHERE token_hash = ? AND type = ? AND used_at IS NULL AND expires_at > now()
                RETURNING user_id""", Long.class, hash(token), type.name());
        return ids.stream().findFirst();
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
