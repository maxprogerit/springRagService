package com.springrag.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.springrag.support.IntegrationTestBase;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.ResultActions;

class AuthApiTest extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtEncoder encoder;

    private ResultActions register(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    @Test
    void registerThenLoginReturnsUsableToken() throws Exception {
        String email = uniqueEmail();
        register(email, "correct-horse-battery").andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty()).andExpect(jsonPath("$.email").value(email));

        String token = body(login(email, "correct-horse-battery").andExpect(status().isOk()).andReturn()).get("token").asText();

        mvc.perform(get("/api/collections").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    @Test
    void passwordIsStoredAsBcryptHashNeverPlain() throws Exception {
        String email = uniqueEmail();
        register(email, "correct-horse-battery").andExpect(status().isCreated());

        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);

        assertThat(hash).startsWith("$2").doesNotContain("correct-horse-battery");
    }

    @Test
    void emailIsCaseInsensitiveAndDuplicatesAreRejected() throws Exception {
        String email = uniqueEmail();
        register(email, "correct-horse-battery").andExpect(status().isCreated());

        register(email.toUpperCase(), "another-password-1").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        login(email.toUpperCase(), "correct-horse-battery").andExpect(status().isOk());
    }

    @Test
    void wrongPasswordAndUnknownUserGetTheSameGenericAnswer() throws Exception {
        String email = uniqueEmail();
        register(email, "correct-horse-battery").andExpect(status().isCreated());

        String wrong = body(login(email, "wrong-password-xx").andExpect(status().isUnauthorized()).andReturn())
                .get("message").asText();
        String unknown = body(login(uniqueEmail(), "wrong-password-xx").andExpect(status().isUnauthorized()).andReturn())
                .get("message").asText();

        assertThat(wrong).isEqualTo(unknown).isEqualTo("Invalid email or password.");
    }

    @Test
    void validationErrorsUseTheStandardErrorFormatWithoutEchoingValues() throws Exception {
        register("not-an-email", "short").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.path").value("/api/auth/register"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("not-an-email"))));
    }

    @Test
    void overlongPasswordsAreRejectedBecauseBcryptTruncatesAt72Bytes() throws Exception {
        register(uniqueEmail(), "p".repeat(73)).andExpect(status().isBadRequest());
    }

    @Test
    void protectedEndpointsRequireAToken() throws Exception {
        mvc.perform(get("/api/collections")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.path").value("/api/collections"));
        mvc.perform(get("/api/collections").header("Authorization", "Bearer garbage"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void expiredAndForeignSignedTokensAreRejected() throws Exception {
        Instant now = Instant.now();
        JwtClaimsSet expired = JwtClaimsSet.builder().issuer("spring-rag-service").subject("1")
                .issuedAt(now.minusSeconds(7200)).expiresAt(now.minusSeconds(3600)).claim("role", "USER").build();
        String expiredToken = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), expired))
                .getTokenValue();
        mvc.perform(get("/api/collections").header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized());

        JwtClaimsSet wrongIssuer = JwtClaimsSet.builder().issuer("someone-else").subject("1")
                .issuedAt(now).expiresAt(now.plusSeconds(3600)).claim("role", "USER").build();
        String wrongIssuerToken = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), wrongIssuer))
                .getTokenValue();
        mvc.perform(get("/api/collections").header("Authorization", "Bearer " + wrongIssuerToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthAndStaticAreOpenButApiIsNot() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/api/anything")).andExpect(status().isUnauthorized());
    }

    @Test
    void securityHeadersArePresent() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Content-Type-Options", "nosniff"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Security-Policy", org.hamcrest.Matchers.containsString("default-src 'self'")));
    }
}
