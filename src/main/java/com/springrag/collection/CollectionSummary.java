package com.springrag.collection;

import java.time.Instant;

public record CollectionSummary(long id, String name, Instant createdAt, long documentCount) {
}
