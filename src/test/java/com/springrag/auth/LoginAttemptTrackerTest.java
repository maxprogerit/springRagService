package com.springrag.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.springrag.common.RateLimitedException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LoginAttemptTrackerTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T10:00:00Z"));
    private final LoginAttemptTracker tracker = new LoginAttemptTracker(3, Duration.ofMinutes(15), now::get);

    @Test
    void locksAfterTheConfiguredNumberOfFailuresThenUnlocksWhenTheWindowPasses() {
        tracker.recordFailure("a@x.com");
        tracker.recordFailure("a@x.com");
        assertThatCode(() -> tracker.checkNotLocked("a@x.com")).doesNotThrowAnyException();

        tracker.recordFailure("a@x.com");

        assertThatThrownBy(() -> tracker.checkNotLocked("a@x.com")).isInstanceOfSatisfying(RateLimitedException.class, e -> {
            assertThat(e.code()).isEqualTo("ACCOUNT_LOCKED");
            assertThat(e.retryAfterSeconds()).isBetween(14L * 60, 15L * 60 + 1);
        });
        now.set(now.get().plus(Duration.ofMinutes(16)));
        assertThatCode(() -> tracker.checkNotLocked("a@x.com")).doesNotThrowAnyException();
    }

    @Test
    void failuresOutsideTheWindowDoNotAddUp() {
        tracker.recordFailure("a@x.com");
        tracker.recordFailure("a@x.com");
        now.set(now.get().plus(Duration.ofMinutes(20)));
        tracker.recordFailure("a@x.com");
        tracker.recordFailure("a@x.com");

        assertThatCode(() -> tracker.checkNotLocked("a@x.com")).doesNotThrowAnyException();
    }

    @Test
    void accountsAreIndependentAndSuccessClearsTheCount() {
        for (int i = 0; i < 3; i++) {
            tracker.recordFailure("a@x.com");
        }

        assertThatCode(() -> tracker.checkNotLocked("b@x.com")).doesNotThrowAnyException();
        tracker.clear("a@x.com");
        assertThatCode(() -> tracker.checkNotLocked("a@x.com")).doesNotThrowAnyException();
    }
}
