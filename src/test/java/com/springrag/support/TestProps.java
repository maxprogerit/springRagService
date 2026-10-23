package com.springrag.support;

import com.springrag.common.RagProperties;
import java.time.Duration;

/** Builds RagProperties for plain unit tests (integration tests use the real application.yml). */
public final class TestProps {

    private TestProps() {
    }

    public static RagProperties defaults() {
        return custom(8, "http://x", "text-embedding-3-small", 0.0, 3, 10);
    }

    public static RagProperties custom(int dimension, String baseUrl, String embeddingModel, Double temperature,
                                       int attempts, int maxPages) {
        return new RagProperties(5, 20, 0.2, false,
                new RagProperties.Chunk(1000, 150),
                new RagProperties.Embedding(dimension, 2),
                new RagProperties.OpenAi("sk-test", baseUrl, "chat-model", embeddingModel, temperature,
                        Duration.ofSeconds(1), Duration.ofSeconds(1), attempts),
                new RagProperties.Jwt("x".repeat(32), 60),
                new RagProperties.RateLimit(10, 10, 10),
                new RagProperties.Ingestion(maxPages, 1, 1, 15, "test-instance"),
                new RagProperties.Quota(100, 500, 1000),
                new RagProperties.QueryLogging(true, 90),
                new RagProperties.Ocr(false, "eng", "", 200, 50),
                new RagProperties.Auth(5, 15, false, "http://localhost:8080"),
                new RagProperties.Mail("", 587, "", "", "noreply@example.com", true),
                new RagProperties.Retrieval(3000));
    }
}
