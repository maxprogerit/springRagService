package com.springrag.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.springrag.openai.OpenAiException;
import com.springrag.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Streaming needs a real server (an SSE response is written while the request is still open), so this uses a random port. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AskStreamTest extends IntegrationTestBase {

    record Event(String name, JsonNode data) {
    }

    @Autowired
    TestRestTemplate rest;

    String token;
    long collection;

    @BeforeEach
    void setUp() throws Exception {
        token = newUserToken();
        collection = createCollection(token, "Stream");
        uploadAndWait(token, collection, "faq.txt", "Annual subscriptions can be refunded within 14 days.".getBytes());
    }

    private ResponseEntity<String> stream(String bearer, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return rest.exchange("/api/collections/" + collection + "/ask/stream", HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    }

    private List<Event> events(String body) throws Exception {
        List<Event> events = new ArrayList<>();
        for (String block : body.split("\n\n")) {
            String name = null;
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) {
                    name = line.substring(6).strip();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).strip());
                }
            }
            if (name != null) {
                events.add(new Event(name, json.readTree(data.toString())));
            }
        }
        return events;
    }

    @Test
    void tokensArriveFirstThenOneValidatedFinalEventReplacesThem() throws Exception {
        when(chatClient.stream(anyString(), anyString(), any())).thenAnswer(inv -> {
            Consumer<String> sink = inv.getArgument(2);
            sink.accept("Refunds take ");
            sink.accept("14 days [1] and 30 [7].");
            return "Refunds take 14 days [1] and 30 [7].";
        });

        ResponseEntity<String> response = stream(token, "{\"question\":\"refund annual subscriptions days\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/event-stream");
        List<Event> events = events(response.getBody());
        assertThat(events).extracting(Event::name).containsExactly("token", "token", "final");
        assertThat(events.get(0).data().get("text").asText()).isEqualTo("Refunds take ");
        JsonNode finalAnswer = events.get(2).data();
        assertThat(finalAnswer.get("answer").asText()).isEqualTo("Refunds take 14 days [1] and 30."); // invalid [7] dropped
        assertThat(finalAnswer.get("citations")).hasSize(1);
        assertThat(finalAnswer.get("citations").get(0).get("filename").asText()).isEqualTo("faq.txt");
    }

    @Test
    void whenNothingRelevantIsFoundOnlyTheNotFoundFinalEventIsSent() throws Exception {
        ResponseEntity<String> response = stream(token, "{\"question\":\"quantum chromodynamics lagrangian\"}");

        List<Event> events = events(response.getBody());
        assertThat(events).extracting(Event::name).containsExactly("final");
        assertThat(events.get(0).data().get("answer").asText()).isEqualTo("I could not find this in the uploaded documents.");
        assertThat(events.get(0).data().get("citations")).isEmpty();
    }

    @Test
    void aFailureAfterTheStreamStartedArrivesAsAnErrorEvent() throws Exception {
        when(chatClient.stream(anyString(), anyString(), any())).thenAnswer(inv -> {
            Consumer<String> sink = inv.getArgument(2);
            sink.accept("Partial ");
            throw new OpenAiException(0, "The connection to OpenAI was interrupted while the answer was streaming.");
        });

        List<Event> events = events(stream(token, "{\"question\":\"refund annual subscriptions\"}").getBody());

        assertThat(events).extracting(Event::name).containsExactly("token", "error");
        assertThat(events.get(1).data().get("code").asText()).isEqualTo("UPSTREAM_ERROR");
        assertThat(events.get(1).data().get("status").asInt()).isEqualTo(502);
        assertThat(events.get(1).data().get("message").asText()).contains("interrupted");
    }

    @Test
    void streamingRequiresAnAccountAndOwnershipJustLikeAsk() throws Exception {
        assertThat(stream(null, "{\"question\":\"q\"}").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(stream(newUserToken(), "{\"question\":\"q\"}").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(stream(token, "{\"question\":\"\"}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void streamedAnswersAreLoggedLikeNormalOnes() throws Exception {
        when(chatClient.stream(anyString(), anyString(), any())).thenAnswer(inv -> "Refunds take 14 days [1].");

        stream(token, "{\"question\":\"refund annual subscriptions\"}");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM queries_log WHERE collection_id = ? AND answered", Integer.class, collection))
                .isEqualTo(1);
    }

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;
}
