package com.springrag.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.springrag.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"rag.rate-limit.ask-per-minute=3", "rag.rate-limit.upload-per-minute=2"})
class RateLimitApiTest extends IntegrationTestBase {

    @Test
    void askIsLimitedPerUserAndLimitIsIndependentBetweenUsers() throws Exception {
        String alice = newUserToken();
        String bob = newUserToken();
        long ac = createCollection(alice, "A");
        long bc = createCollection(bob, "B");
        when(chatClient.complete(anyString(), anyString())).thenReturn("x [1]");
        String body = "{\"question\":\"anything\"}";

        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/collections/" + ac + "/ask").header("Authorization", "Bearer " + alice)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        }
        mvc.perform(post("/api/collections/" + ac + "/ask").header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.path").value("/api/collections/" + ac + "/ask"));

        mvc.perform(post("/api/collections/" + bc + "/ask").header("Authorization", "Bearer " + bob)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }

    @Test
    void searchSharesTheAskBudget() throws Exception {
        String token = newUserToken();
        long c = createCollection(token, "S");
        String body = "{\"question\":\"anything\"}";

        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/collections/" + c + "/search").header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        }
        mvc.perform(post("/api/collections/" + c + "/ask").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isTooManyRequests());
    }

    @Test
    void uploadsHaveTheirOwnPerUserLimit() throws Exception {
        String token = newUserToken();
        long c = createCollection(token, "Up");

        assertThat(upload(token, c, "a.txt", "one".getBytes()).getResponse().getStatus()).isEqualTo(202);
        assertThat(upload(token, c, "b.txt", "two".getBytes()).getResponse().getStatus()).isEqualTo(202);
        assertThat(upload(token, c, "c.txt", "three".getBytes()).getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void rejectedRequestsDoNotReachTheModelOrTheEmbeddingProvider() throws Exception {
        String token = newUserToken();
        long c = createCollection(token, "NoCost");
        String body = "{\"question\":\"anything\"}";
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/collections/" + c + "/search").header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).content(body));
        }
        org.mockito.Mockito.clearInvocations(embeddingClient, chatClient);

        mvc.perform(post("/api/collections/" + c + "/ask").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isTooManyRequests());

        org.mockito.Mockito.verifyNoInteractions(embeddingClient, chatClient);
    }
}
