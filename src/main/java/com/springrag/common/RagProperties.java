package com.springrag.common;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** All application settings, bound from the {@code rag.*} section of application.yml (fed by environment variables). */
@Validated
@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        @Min(1) @Max(50) int topK,
        @Min(1) @Max(500) int maxUploadMb,
        @DecimalMin("-1.0") @DecimalMax("1.0") double minSimilarity,
        boolean logPayloads,
        @Valid @NotNull Chunk chunk,
        @Valid @NotNull Embedding embedding,
        @Valid @NotNull OpenAi openai,
        @Valid @NotNull Jwt jwt,
        @Valid @NotNull RateLimit rateLimit,
        @Valid @NotNull Ingestion ingestion,
        @Valid @NotNull Quota quota,
        @Valid @NotNull QueryLogging queryLog,
        @Valid @NotNull Ocr ocr,
        @Valid @NotNull Auth auth,
        @Valid @NotNull Mail mail,
        @Valid @NotNull Retrieval retrieval) {

    // Upper bound: embedding models accept at most ~8k tokens per input; 8000 characters stays safely below it.
    public record Chunk(@Min(100) @Max(8000) int sizeChars, @Min(0) int overlapChars) {
        @AssertTrue(message = "rag.chunk.overlap-chars must be smaller than rag.chunk.size-chars")
        public boolean isOverlapSmallerThanSize() {
            return overlapChars < sizeChars;
        }
    }

    public record Embedding(@Min(1) int dimension, @Min(1) @Max(2048) int batchSize) {}

    public record OpenAi(
            String apiKey,
            @NotBlank String baseUrl,
            @NotBlank String chatModel,
            @NotBlank String embeddingModel,
            Double chatTemperature,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout,
            @Min(1) @Max(10) int maxAttempts) {

        /** The default record toString would print the API key into logs. */
        @Override
        public String toString() {
            return "OpenAi[apiKey=" + (apiKey == null || apiKey.isBlank() ? "<unset>" : "<redacted>")
                    + ", baseUrl=" + baseUrl + ", chatModel=" + chatModel + ", embeddingModel=" + embeddingModel + "]";
        }
    }

    public record Jwt(@NotBlank @Size(min = 32, message = "JWT_SECRET must be at least 32 characters") String secret,
                      @Min(1) long ttlMinutes) {
        @Override
        public String toString() {
            return "Jwt[secret=<redacted>, ttlMinutes=" + ttlMinutes + "]";
        }
    }

    public record RateLimit(@Min(1) int askPerMinute, @Min(1) int authPerMinute, @Min(1) int uploadPerMinute) {}

    /**
     * @param processingTimeoutMinutes a document still PROCESSING after this long is considered lost
     * @param instanceId               identifies this app instance; on startup its own leftovers are failed at once
     */
    public record Ingestion(@Min(1) int maxPages, @Min(1) int threads, @Min(1) int queueCapacity,
                            @Min(1) int processingTimeoutMinutes, @NotBlank String instanceId) {}

    /** Per-user limits. maxCollectionsPerUser must stay <= 200, the largest page the collections list can return. */
    public record Quota(@Min(1) @Max(200) int maxCollectionsPerUser, @Min(1) int maxDocumentsPerCollection,
                        @Min(1) long maxStorageMbPerUser) {}

    /** @param retentionDays queries_log rows older than this are deleted daily; 0 keeps them forever */
    public record QueryLogging(boolean storeQuestions, @Min(0) int retentionDays) {}

    /**
     * Optional OCR for scanned PDFs and images (needs the Tesseract native library; the Docker image has it).
     *
     * @param dataPath directory with the *.traineddata files; blank = Tesseract's default or TESSDATA_PREFIX
     */
    public record Ocr(boolean enabled, @NotBlank String languages, String dataPath, @Min(72) @Max(600) int dpi,
                      @Min(1) int maxPages) {}

    /**
     * @param maxFailedLogins failed sign-ins per email within lockoutMinutes before the account is temporarily locked
     * @param baseUrl         public address of the app, used in the links of e-mails
     */
    public record Auth(@Min(1) int maxFailedLogins, @Min(1) int lockoutMinutes, boolean requireEmailVerification,
                       @NotBlank String baseUrl) {}

    /**
     * @param exactSearchMaxChunks collections with at most this many chunks are searched exactly (no index, never
     *                             misses a row); bigger ones use the approximate HNSW index. 0 = always HNSW.
     */
    public record Retrieval(@Min(0) int exactSearchMaxChunks) {}

    /** SMTP settings. A blank host disables e-mail (password reset and verification are then unavailable). */
    public record Mail(String host, @Min(1) int port, String username, String password, String from, boolean startTls) {
        public boolean enabled() {
            return host != null && !host.isBlank();
        }

        @Override
        public String toString() {
            return "Mail[host=" + host + ", port=" + port + ", username=" + username + ", password=<redacted>]";
        }
    }

    @AssertTrue(message = "REQUIRE_EMAIL_VERIFICATION=true needs MAIL_HOST to be set")
    public boolean isMailAvailableWhenVerificationRequired() {
        return !auth.requireEmailVerification() || mail.enabled();
    }
}
