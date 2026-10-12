package com.springrag.openai;

import static org.assertj.core.api.Assertions.assertThat;
import com.springrag.support.TestProps;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.springrag.common.RagProperties;
import com.springrag.embedding.OpenAiEmbeddingClient;
import com.springrag.generation.OpenAiChatClient;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** HTTP behaviour of the OpenAI clients against a mock server. No network, no real API. */
class OpenAiClientsTest {

    private static final String BASE = "https://api.test/v1";
    private static final int DIM = 3;

    private MockRestServiceServer server;
    private OpenAiHttp http;
    private final List<Long> sleeps = new ArrayList<>();

    private static RagProperties props(String embeddingModel, Double temperature) {
        return TestProps.custom(DIM, BASE, embeddingModel, temperature, 4, 10);
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        http = new OpenAiHttp(builder.build(), "sk-test", 4, sleeps::add);
    }

    private static String embeddingResponse(int... indexesInResponseOrder) {
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int i = 0; i < indexesInResponseOrder.length; i++) {
            int idx = indexesInResponseOrder[i];
            sb.append(i > 0 ? "," : "").append("{\"index\":").append(idx).append(",\"embedding\":[")
                    .append(idx).append(".5,0,1]}");
        }
        return sb.append("]}").toString();
    }

    @Test
    void embeddingsAreSentInBatchesWithAuthHeaderAndReturnedInInputOrder() {
        server.expect(requestTo(BASE + "/embeddings")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk-test"))
                .andExpect(jsonPath("$.model").value("text-embedding-3-small"))
                .andExpect(jsonPath("$.dimensions").value(DIM))
                .andExpect(jsonPath("$.input.length()").value(2))
                .andRespond(withSuccess(embeddingResponse(1, 0), MediaType.APPLICATION_JSON)); // out of order on purpose
        server.expect(requestTo(BASE + "/embeddings"))
                .andExpect(jsonPath("$.input.length()").value(1))
                .andRespond(withSuccess(embeddingResponse(0), MediaType.APPLICATION_JSON));

        List<float[]> vectors = new OpenAiEmbeddingClient(http, props("text-embedding-3-small", 0.0))
                .embed(List.of("a", "b", "c"));

        assertThat(vectors).hasSize(3);
        assertThat(vectors.get(0)[0]).isEqualTo(0.5f);   // input "a" -> response index 0
        assertThat(vectors.get(1)[0]).isEqualTo(1.5f);   // input "b" -> response index 1
        assertThat(vectors.get(2)[0]).isEqualTo(0.5f);   // second batch
        server.verify();
    }

    @Test
    void dimensionsParameterIsNotSentToOtherModels() {
        server.expect(requestTo(BASE + "/embeddings"))
                .andExpect(jsonPath("$.dimensions").doesNotExist())
                .andRespond(withSuccess(embeddingResponse(0), MediaType.APPLICATION_JSON));

        new OpenAiEmbeddingClient(http, props("text-embedding-ada-002", 0.0)).embed(List.of("a"));

        server.verify();
    }

    @Test
    void retriesOn429And5xxWithBackoffThenSucceeds() {
        server.expect(times(1), requestTo(BASE + "/embeddings")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(times(1), requestTo(BASE + "/embeddings")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(times(1), requestTo(BASE + "/embeddings"))
                .andRespond(withSuccess(embeddingResponse(0), MediaType.APPLICATION_JSON));

        List<float[]> vectors = new OpenAiEmbeddingClient(http, props("m", 0.0)).embed(List.of("a"));

        assertThat(vectors).hasSize(1);
        assertThat(sleeps).hasSize(2);
        assertThat(sleeps.get(1)).isGreaterThan(sleeps.get(0) - 250); // backoff grows (jitter < 250 ms)
        server.verify();
    }

    @Test
    void honoursRetryAfterHeader() {
        server.expect(requestTo(BASE + "/embeddings"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "3"));
        server.expect(requestTo(BASE + "/embeddings"))
                .andRespond(withSuccess(embeddingResponse(0), MediaType.APPLICATION_JSON));

        new OpenAiEmbeddingClient(http, props("m", 0.0)).embed(List.of("a"));

        assertThat(sleeps).containsExactly(3000L);
    }

    @Test
    void givesUpAfterMaxAttemptsWithASafeMessage() {
        server.expect(times(4), requestTo(BASE + "/embeddings")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> new OpenAiEmbeddingClient(http, props("m", 0.0)).embed(List.of("a")))
                .isInstanceOfSatisfying(OpenAiException.class, e -> {
                    assertThat(e.providerStatus()).isEqualTo(503);
                    assertThat(e.getMessage()).doesNotContain("sk-test");
                });
        assertThat(sleeps).hasSize(3);
        server.verify();
    }

    @Test
    void doesNotRetryClientErrors() {
        server.expect(times(1), requestTo(BASE + "/embeddings")).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"message\":\"Incorrect API key sk-test-secret\"}}"));

        assertThatThrownBy(() -> new OpenAiEmbeddingClient(http, props("m", 0.0)).embed(List.of("a")))
                .isInstanceOfSatisfying(OpenAiException.class, e -> {
                    assertThat(e.providerStatus()).isEqualTo(401);
                    assertThat(e.getMessage()).doesNotContain("sk-test");
                });
        assertThat(sleeps).isEmpty();
        server.verify();
    }

    @Test
    void retriesNetworkErrors() {
        server.expect(requestTo(BASE + "/embeddings")).andRespond(request -> {
            throw new IOException("connection reset");
        });
        server.expect(requestTo(BASE + "/embeddings"))
                .andRespond(withSuccess(embeddingResponse(0), MediaType.APPLICATION_JSON));

        assertThat(new OpenAiEmbeddingClient(http, props("m", 0.0)).embed(List.of("a"))).hasSize(1);
        assertThat(sleeps).hasSize(1);
    }

    @Test
    void wrongEmbeddingSizeIsReportedClearly() {
        server.expect(requestTo(BASE + "/embeddings")).andRespond(withSuccess(
                "{\"data\":[{\"index\":0,\"embedding\":[1,2]}]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> new OpenAiEmbeddingClient(http, props("m", 0.0)).embed(List.of("a")))
                .isInstanceOf(OpenAiException.class).hasMessageContaining("EMBEDDING_DIMENSION");
    }

    @Test
    void missingApiKeyFailsWithoutCallingTheServer() {
        OpenAiHttp noKey = new OpenAiHttp(RestClient.builder().baseUrl(BASE).build(), " ", 4, sleeps::add);

        assertThatThrownBy(() -> noKey.post("/embeddings", java.util.Map.of()))
                .isInstanceOf(OpenAiException.class).hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    void chatSendsSystemAndUserMessagesAndReturnsContent() {
        server.expect(requestTo(BASE + "/chat/completions"))
                .andExpect(header("Authorization", "Bearer sk-test"))
                .andExpect(jsonPath("$.model").value("chat-model"))
                .andExpect(jsonPath("$.temperature").value(0.0))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value("be strict"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value("question"))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"answer [1]\"}}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(new OpenAiChatClient(http, props("m", 0.0)).complete("be strict", "question")).isEqualTo("answer [1]");
    }

    @Test
    void chatOmitsTemperatureWhenNotConfigured() {
        server.expect(requestTo(BASE + "/chat/completions"))
                .andExpect(jsonPath("$.temperature").doesNotExist())
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}", MediaType.APPLICATION_JSON));

        new OpenAiChatClient(http, props("m", null)).complete("s", "u");

        server.verify();
    }

    @Test
    void chatWithoutContentIsAnError() {
        server.expect(requestTo(BASE + "/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> new OpenAiChatClient(http, props("m", 0.0)).complete("s", "u"))
                .isInstanceOf(OpenAiException.class);
    }

    @Test
    void propertiesToStringDoesNotLeakSecrets() {
        RagProperties p = props("m", 0.0);

        assertThat(p.openai().toString()).doesNotContain("sk-test").contains("<redacted>");
        assertThat(p.jwt().toString()).doesNotContain("xxxxxxxx");
        assertThat(p.toString()).doesNotContain("sk-test").doesNotContain("xxxxxxxx");
    }

    // ----- streaming -----

    private static String sse(String... deltas) {
        StringBuilder sb = new StringBuilder(": keep-alive\n\n");
        for (String d : deltas) {
            sb.append("data: {\"choices\":[{\"delta\":{\"content\":\"").append(d).append("\"}}]}\n\n");
        }
        return sb.append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").toString();
    }

    @Test
    void chatStreamDeliversDeltasInOrderAndReturnsTheFullText() {
        server.expect(requestTo(BASE + "/chat/completions"))
                .andExpect(header("Authorization", "Bearer sk-test"))
                .andExpect(header("Accept", "text/event-stream"))
                .andExpect(jsonPath("$.stream").value(true))
                .andRespond(withSuccess(sse("Hel", "lo ", "[1]"), MediaType.TEXT_EVENT_STREAM));
        List<String> pieces = new ArrayList<>();

        String full = new OpenAiChatClient(http, props("m", 0.0)).stream("sys", "user", pieces::add);

        assertThat(pieces).containsExactly("Hel", "lo ", "[1]");
        assertThat(full).isEqualTo("Hello [1]");
        server.verify();
    }

    @Test
    void streamRetriesWhenTheServerRefusesBeforeAnythingWasSent() {
        server.expect(times(1), requestTo(BASE + "/chat/completions")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(times(1), requestTo(BASE + "/chat/completions"))
                .andRespond(withSuccess(sse("ok"), MediaType.TEXT_EVENT_STREAM));
        List<String> pieces = new ArrayList<>();

        new OpenAiChatClient(http, props("m", 0.0)).stream("sys", "user", pieces::add);

        assertThat(pieces).containsExactly("ok");
        assertThat(sleeps).hasSize(1);
    }

    @Test
    void streamGivesUpOnClientErrorsWithoutRetrying() {
        server.expect(times(1), requestTo(BASE + "/chat/completions")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> new OpenAiChatClient(http, props("m", 0.0)).stream("s", "u", x -> { }))
                .isInstanceOfSatisfying(OpenAiException.class, e -> assertThat(e.providerStatus()).isEqualTo(401));
        assertThat(sleeps).isEmpty();
    }

    @Test
    void anEmptyStreamIsAnError() {
        server.expect(requestTo(BASE + "/chat/completions"))
                .andRespond(withSuccess("data: [DONE]\n\n", MediaType.TEXT_EVENT_STREAM));

        assertThatThrownBy(() -> new OpenAiChatClient(http, props("m", 0.0)).stream("s", "u", x -> { }))
                .isInstanceOf(OpenAiException.class).hasMessageContaining("no answer");
    }

    @Test
    void aConsumerThatAbortsStopsTheStreamImmediately() {
        server.expect(requestTo(BASE + "/chat/completions"))
                .andRespond(withSuccess(sse("a", "b", "c"), MediaType.TEXT_EVENT_STREAM));
        List<String> pieces = new ArrayList<>();

        assertThatThrownBy(() -> new OpenAiChatClient(http, props("m", 0.0)).stream("s", "u", piece -> {
            pieces.add(piece);
            throw new java.util.concurrent.CancellationException("client gone");
        })).isInstanceOf(java.util.concurrent.CancellationException.class);

        assertThat(pieces).containsExactly("a");
    }
}
