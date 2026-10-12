package com.springrag.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RateLimiterTest {

    @Test
    void allowsUpToTheLimitThenRejectsWithRetryAfter() {
        RateLimiter limiter = new RateLimiter();

        for (int i = 0; i < 3; i++) {
            limiter.check("ask:1", 3);
        }
        assertThatThrownBy(() -> limiter.check("ask:1", 3))
                .isInstanceOfSatisfying(RateLimitedException.class, e -> {
                    assertThat(e.retryAfterSeconds()).isBetween(1L, 61L);
                    assertThat(e.code()).isEqualTo("RATE_LIMITED");
                });
    }

    @Test
    void limitsArePerKey() {
        RateLimiter limiter = new RateLimiter();
        limiter.check("ask:1", 1);

        assertThatCode(() -> limiter.check("ask:2", 1)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.check("ask:1", 1)).isInstanceOf(RateLimitedException.class);
    }
}
