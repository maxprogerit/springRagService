package com.springrag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.springrag.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * With exact-search-max-chunks=0 every search uses the approximate HNSW index, exercising pgvector's iterative scan:
 * a small collection next to a big one must still get a full result.
 */
@TestPropertySource(properties = "rag.retrieval.exact-search-max-chunks=0")
class HnswFilteredSearchTest extends IntegrationTestBase {

    @Test
    void filteredHnswSearchStillFillsTheCandidatePoolWhenAnotherCollectionDominatesTheIndex() throws Exception {
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
        JsonNode r = body(mvc.perform(post("/api/collections/" + small + "/search")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"quarterly budget planning forecasts\",\"topK\":5}"))
                .andExpect(status().isOk()).andReturn());

        assertThat(r.get("vectorHits")).hasSize(6);
        assertThat(r.get("vectorHits")).allSatisfy(h -> assertThat(h.get("filename").asText()).isEqualTo("small.txt"));
    }
}
