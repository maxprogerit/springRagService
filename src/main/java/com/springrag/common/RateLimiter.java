package com.springrag.common;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * In-memory token buckets, one per key (for example "ask:42" or "auth:203.0.113.5").
 * State is per JVM and lost on restart. The map is bounded so unknown keys cannot grow it forever;
 * an evicted key simply starts with a full bucket again.
 */
@Component
public class RateLimiter {

    private static final int MAX_KEYS = 10_000;

    private final Map<String, Bucket> buckets = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                    return size() > MAX_KEYS;
                }
            });

    /** Takes one token or throws {@link RateLimitedException} with the number of seconds to wait. */
    public void check(String key, int perMinute) {
        Bucket bucket = buckets.computeIfAbsent(key + "/" + perMinute, k -> Bucket.builder()
                .addLimit(limit -> limit.capacity(perMinute).refillGreedy(perMinute, Duration.ofMinutes(1)))
                .build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long seconds = Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds() + 1);
            throw new RateLimitedException(seconds);
        }
    }
}
