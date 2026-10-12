package com.springrag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.springrag.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class RetrievalApiTest extends IntegrationTestBase {

    private JsonNode search(String token, long collection, String question, Integer topK) throws Exception {
        String payload = "{\"question\":\"" + question + "\"" + (topK == null ? "" : ",\"topK\":" + topK) + "}";
        return body(mvc.perform(post("/api/collections/" + collection + "/search")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andReturn());
    }

    private static List<String> fusedFiles(JsonNode result) {
        List<String> files = new ArrayList<>();
        result.get("fused").forEach(h -> files.add(h.get("filename").asText() + "#" + h.get("page").asInt()));
        return files;
    }

    @Test
    void hybridSearchFindsTheRightPageUsingBothRetrievers() throws Exception {
        String token = newUserToken();
        long c = createCollection(token, "Hybrid");
        uploadAndWait(token, c, "pricing.txt", ("Starter plan costs 19 dollars per user.\fTeam plan costs 49 dollars "
                + "and includes dashboard sharing.\fEnterprise pricing is custom.").getBytes());
        uploadAndWait(token, c, "security.txt", "Passwords must contain fourteen characters and MFA is mandatory.".getBytes());

        JsonNode r = search(token, c, "dashboard sharing price", null);

        assertThat(fusedFiles(r).get(0)).isEqualTo("pricing.txt#2");
        JsonNode top = r.get("fused").get(0);
        assertThat(top.get("vectorRank").asInt()).isEqualTo(1);
        assertThat(top.get("keywordRank").asInt()).isEqualTo(1);
        assertThat(top.get("vectorSimilarity").asDouble()).isBetween(0.0, 1.0);
        assertThat(top.get("keywordScore").asDouble()).isPositive();
        assertThat(top.get("score").asDouble()).isCloseTo(2.0 / 61, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(r.get("vectorHits").size()).isGreaterThan(0);
        assertThat(r.get("keywordHits").size()).isGreaterThan(0);
        assertThat(r.get("latencyMs").asLong()).isNotNegative();
    }

    @Test
    void keywordRetrieverFindsRareExactTermsAndReportsNoKeywordRankForOthers() throws Exception {
        String token = newUserToken();
        long c = createCollection(token, "Keywords");
        uploadAndWait(token, c, "api.txt", "Send the signature in the X-Orbit-Signature header using HMAC.".getBytes());
        uploadAndWait(token, c, "other.txt", "Completely unrelated text about vacations and travel budgets.".getBytes());

        JsonNode r = search(token, c, "X-Orbit-Signature", null);

        assertThat(r.get("keywordHits")).hasSize(1);
        assertThat(r.get("keywordHits").get(0).get("filename").asText()).isEqualTo("api.txt");
        assertThat(fusedFiles(r).get(0)).startsWith("api.txt");
    }

    @Test
    void resultsNeverLeakAcrossCollectionsOrUsers() throws Exception {
        String alice = newUserToken();
        String bob = newUserToken();
        long ac = createCollection(alice, "Alice");
        long bc = createCollection(bob, "Bob");
        uploadAndWait(alice, ac, "secret.txt", "The launch codename is Zanzibar-Seven.".getBytes());
        uploadAndWait(bob, bc, "bob.txt", "Bob keeps his notes about gardening here.".getBytes());

        JsonNode bobResult = search(bob, bc, "launch codename Zanzibar-Seven", null);
        JsonNode aliceResult = search(alice, ac, "launch codename Zanzibar-Seven", null);

        assertThat(bobResult.toString()).doesNotContain("Zanzibar").doesNotContain("secret.txt");
        assertThat(aliceResult.get("fused").get(0).get("filename").asText()).isEqualTo("secret.txt");
    }

    @Test
    void topKLimitsFusedButNotTheCandidatePools() throws Exception {
        String token = newUserToken();
        long c = createCollection(token, "TopK");
        StringBuilder pages = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            pages.append("Shared topic paragraph number ").append(i).append(" mentions budget planning.").append('\f');
        }
        uploadAndWait(token, c, "many.txt", pages.toString().getBytes());

        JsonNode r = search(token, c, "budget planning", 3);

        assertThat(r.get("fused")).hasSize(3);
        assertThat(r.get("vectorHits").size()).isGreaterThan(3);
        assertThat(r.get("topK").asInt()).isEqualTo(3);
    }

    @Test
    void emptyCollectionGivesEmptyResultsNotAnError() throws Exception {
        String token = newUserToken();
        long c = createCollection(token, "Empty");

        JsonNode r = search(token, c, "anything at all", null);

        assertThat(r.get("fused")).isEmpty();
        assertThat(r.get("vectorHits")).isEmpty();
        assertThat(r.get("keywordHits")).isEmpty();
    }

    @Test
    void questionWithOnlyStopWordsStillRunsVectorSearch() throws Exception {
        String token = newUserToken();
        long c = createCollection(token, "Stop");
        uploadAndWait(token, c, "a.txt", "Some content to index.".getBytes());

        JsonNode r = search(token, c, "what is the", null);

        assertThat(r.get("keywordHits")).isEmpty();
        assertThat(r.get("vectorHits")).hasSize(1);
    }

    @Test
    void specialCharactersInTheQuestionCannotBreakTheTsquery() throws Exception {
        String token = newUserToken();
        long c = createCollection(token, "Special");
        uploadAndWait(token, c, "a.txt", "Plain content about tables and chunks.".getBytes());

        JsonNode r = search(token, c, "tables & !chunks | (x) <-> y:* ' ;--", null);

        assertThat(r.get("fused").size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void validatesInput() throws Exception {
        String token = newUserToken();
        long c = createCollection(token, "Validate");
        String url = "/api/collections/" + c + "/search";

        mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"  \"}")).andExpect(status().isBadRequest());
        mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"ok\",\"topK\":0}")).andExpect(status().isBadRequest());
        mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"ok\",\"topK\":21}")).andExpect(status().isBadRequest());
        mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"" + "q".repeat(2001) + "\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("not json")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void filteredVectorSearchStillFillsTopKWhenOtherCollectionsDominateTheIndex() throws Exception {
        // HNSW filters after scanning; iterative scan must keep going until the collection filter is satisfied.
        String token = newUserToken();
        long big = createCollection(token, "Big neighbour");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            many.append("Neighbour page ").append(i).append(" talks about quarterly budget planning and forecasts.\f");
        }
        uploadAndWait(token, big, "big.txt", many.toString().getBytes());
        long small = createCollection(token, "Small");
        StringBuilder few = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            few.append("Zebra document ").append(i).append(" explains stripes and savanna.\f");
        }
        uploadAndWait(token, small, "small.txt", few.toString().getBytes());

        // The query is much closer to the big collection's vectors than to anything in the small one.
        JsonNode r = search(token, small, "quarterly budget planning forecasts", 5);

        assertThat(r.get("vectorHits").size()).isEqualTo(6);
        assertThat(r.get("vectorHits")).allSatisfy(h -> assertThat(h.get("filename").asText()).isEqualTo("small.txt"));
    }
}
