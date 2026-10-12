package com.springrag.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.springrag.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "rag.rate-limit.auth-per-minute=4")
class AuthRateLimitApiTest extends IntegrationTestBase {

    @Test
    void loginAttemptsAreLimitedPerClientAddress() throws Exception {
        String payload = "{\"email\":\"nobody@example.com\",\"password\":\"wrong-password-1\"}";
        int tooMany = 0;
        for (int i = 0; i < 8; i++) {
            int code = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(payload))
                    .andReturn().getResponse().getStatus();
            if (code == 429) {
                tooMany++;
            } else {
                assertThat(code).isEqualTo(401);
            }
        }
        assertThat(tooMany).isEqualTo(4);
    }
}
