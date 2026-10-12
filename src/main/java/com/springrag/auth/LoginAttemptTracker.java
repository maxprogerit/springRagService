package com.springrag.auth;

import com.springrag.common.RagProperties;
import com.springrag.common.RateLimitedException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Temporarily locks an account after too many failed sign-ins (per e-mail, also for unknown e-mails so the lock
 * does not reveal which accounts exist). In memory, bounded; a restart clears it.
 */
@Component
public class LoginAttemptTracker {

    private static final int MAX_KEYS = 10_000;

    private static final class State {
        int failures;
        Instant windowStart;
        Instant lockedUntil;
    }

    private final int maxFailures;
    private final Duration window;
    private final Supplier<Instant> clock;
    private final Map<String, State> states = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, State> eldest) {
            return size() > MAX_KEYS;
        }
    });

    @Autowired
    public LoginAttemptTracker(RagProperties props) {
        this(props.auth().maxFailedLogins(), Duration.ofMinutes(props.auth().lockoutMinutes()), Instant::now);
    }

    LoginAttemptTracker(int maxFailures, Duration window, Supplier<Instant> clock) {
        this.maxFailures = maxFailures;
        this.window = window;
        this.clock = clock;
    }

    /** Throws 429 ACCOUNT_LOCKED while the account is locked. */
    public void checkNotLocked(String email) {
        State state = states.get(email);
        if (state == null) {
            return;
        }
        synchronized (state) {
            Instant now = clock.get();
            if (state.lockedUntil != null && state.lockedUntil.isAfter(now)) {
                long seconds = Math.max(1, Duration.between(now, state.lockedUntil).toSeconds() + 1);
                throw new RateLimitedException("ACCOUNT_LOCKED",
                        "Too many failed sign-in attempts. Try again in " + Math.max(1, seconds / 60) + " minute(s).", seconds);
            }
        }
    }

    public void recordFailure(String email) {
        State state = states.computeIfAbsent(email, k -> new State());
        synchronized (state) {
            Instant now = clock.get();
            if (state.windowStart == null || state.windowStart.plus(window).isBefore(now)) {
                state.windowStart = now;
                state.failures = 0;
                state.lockedUntil = null;
            }
            state.failures++;
            if (state.failures >= maxFailures) {
                state.lockedUntil = now.plus(window);
            }
        }
    }

    public void clear(String email) {
        states.remove(email);
    }
}
