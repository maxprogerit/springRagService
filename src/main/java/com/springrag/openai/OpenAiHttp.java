package com.springrag.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.springrag.common.RagProperties;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** POSTs JSON to OpenAI with retry on 429, 5xx and network errors (exponential backoff with jitter). */
@Component
public class OpenAiHttp {

    /** Replaceable so tests do not really wait. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis);
    }

    /** Internal: a streaming response came back with an error status. */
    private static final class StatusFailure extends RuntimeException {
        final int status;
        final HttpHeaders headers;

        StatusFailure(int status, HttpHeaders headers) {
            super("HTTP " + status, null, false, false);
            this.status = status;
            this.headers = headers;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(OpenAiHttp.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final long BASE_DELAY_MS = 500;
    private static final long MAX_DELAY_MS = 8_000;
    private static final long MAX_RETRY_AFTER_MS = 20_000;

    private final RestClient client;
    private final String apiKey;
    private final int maxAttempts;
    private final Sleeper sleeper;

    @Autowired
    public OpenAiHttp(RestClient openAiRestClient, RagProperties props) {
        this(openAiRestClient, props.openai().apiKey(), props.openai().maxAttempts(), OpenAiHttp::threadSleep);
    }

    public OpenAiHttp(RestClient client, String apiKey, int maxAttempts, Sleeper sleeper) {
        this.client = client;
        this.apiKey = apiKey;
        this.maxAttempts = maxAttempts;
        this.sleeper = sleeper;
    }

    public JsonNode post(String path, Object body) {
        requireKey();
        for (int attempt = 1; ; attempt++) {
            try {
                JsonNode response = client.post().uri(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
                if (response == null) {
                    throw new OpenAiException(0, "OpenAI returned an empty response.");
                }
                return response;
            } catch (RestClientResponseException e) {
                backoffOrThrow(path, attempt, e.getStatusCode().value(), e.getResponseHeaders());
            } catch (ResourceAccessException e) {
                backoffOrThrow(path, attempt, 0, null);
            }
        }
    }

    /**
     * POSTs with {@code "stream": true} semantics and hands every server-sent event payload (the JSON after
     * {@code data:}) to {@code onEvent}. Retries only while nothing has been delivered yet: once tokens reached the
     * caller, a broken connection ends the call with an error instead of repeating the request.
     */
    public void postStream(String path, Object body, Consumer<JsonNode> onEvent) {
        requireKey();
        for (int attempt = 1; ; attempt++) {
            boolean[] delivered = {false};
            try {
                client.post().uri(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .body(body)
                        .exchange((request, response) -> {
                            if (response.getStatusCode().isError()) {
                                throw new StatusFailure(response.getStatusCode().value(), response.getHeaders());
                            }
                            try (BufferedReader reader = new BufferedReader(
                                    new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    if (!line.startsWith("data:")) {
                                        continue;
                                    }
                                    String data = line.substring(5).strip();
                                    if (data.equals("[DONE]")) {
                                        break;
                                    }
                                    delivered[0] = true;
                                    onEvent.accept(MAPPER.readTree(data));
                                }
                            }
                            return null;
                        });
                return;
            } catch (StatusFailure e) {
                backoffOrThrow(path, attempt, e.status, e.headers);
            } catch (ResourceAccessException e) {
                if (delivered[0]) {
                    throw new OpenAiException(0, "The connection to OpenAI was interrupted while the answer was streaming.");
                }
                backoffOrThrow(path, attempt, 0, null);
            }
        }
    }

    private void requireKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new OpenAiException(0, "OPENAI_API_KEY is not configured on the server.");
        }
    }

    /** Sleeps and returns when the failure is retryable and attempts remain; otherwise throws. */
    private void backoffOrThrow(String path, int attempt, int status, HttpHeaders headers) {
        boolean network = status == 0;
        boolean retryable = network || status == 429 || status >= 500;
        if (!retryable || attempt >= maxAttempts) {
            throw new OpenAiException(status, network ? "OpenAI is unreachable or timed out." : describe(status));
        }
        long delay = delayMillis(attempt, headers);
        log.warn("OpenAI {} failed ({}); retry {}/{} in {} ms", path, network ? "network error" : "HTTP " + status,
                attempt, maxAttempts - 1, delay);
        sleeper.sleep(delay);
    }

    static String describe(int status) {
        return switch (status) {
            case 401 -> "OpenAI rejected the API key (HTTP 401).";
            case 429 -> "OpenAI rate limit or quota exceeded (HTTP 429).";
            default -> status >= 500
                    ? "OpenAI server error (HTTP " + status + ")."
                    : "OpenAI request failed (HTTP " + status + ").";
        };
    }

    private static long delayMillis(int attempt, HttpHeaders headers) {
        String retryAfter = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (retryAfter != null) {
            try {
                return Math.min(Long.parseLong(retryAfter.trim()) * 1000, MAX_RETRY_AFTER_MS);
            } catch (NumberFormatException ignored) {
                // Retry-After can also be an HTTP date; fall through to backoff.
            }
        }
        long exponential = Math.min(BASE_DELAY_MS << (attempt - 1), MAX_DELAY_MS);
        return exponential + ThreadLocalRandom.current().nextLong(250);
    }

    private static void threadSleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenAiException(0, "Interrupted while waiting to retry the OpenAI request.");
        }
    }
}
