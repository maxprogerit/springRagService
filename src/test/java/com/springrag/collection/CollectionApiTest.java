package com.springrag.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.springrag.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class CollectionApiTest extends IntegrationTestBase {

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    void createListAndDelete() throws Exception {
        String token = newUserToken();
        long id = createCollection(token, "Contracts");

        JsonNode list = body(mvc.perform(get("/api/collections").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("name").asText()).isEqualTo("Contracts");
        assertThat(list.get(0).get("documentCount").asLong()).isZero();
        assertThat(list.get(0).get("createdAt").asText()).isNotBlank();

        mvc.perform(delete("/api/collections/" + id).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());
        assertThat(body(mvc.perform(get("/api/collections").header("Authorization", bearer(token))).andReturn())).isEmpty();
    }

    @Test
    void listShowsOnlyMyCollectionsWithDocumentCounts() throws Exception {
        String alice = newUserToken();
        String bob = newUserToken();
        long aliceCollection = createCollection(alice, "Alice docs");
        createCollection(bob, "Bob docs");
        uploadAndWait(alice, aliceCollection, "a.txt", "alpha content".getBytes());
        uploadAndWait(alice, aliceCollection, "b.txt", "beta content".getBytes());

        JsonNode aliceList = body(mvc.perform(get("/api/collections").header("Authorization", bearer(alice))).andReturn());
        JsonNode bobList = body(mvc.perform(get("/api/collections").header("Authorization", bearer(bob))).andReturn());

        assertThat(aliceList).hasSize(1);
        assertThat(aliceList.get(0).get("documentCount").asLong()).isEqualTo(2);
        assertThat(bobList).hasSize(1);
        assertThat(bobList.get(0).get("name").asText()).isEqualTo("Bob docs");
        assertThat(bobList.get(0).get("documentCount").asLong()).isZero();
    }

    @Test
    void otherUsersCollectionLooksNonExistentOnEveryEndpoint() throws Exception {
        String alice = newUserToken();
        String mallory = newUserToken();
        long id = createCollection(alice, "Private");
        String base = "/api/collections/" + id;
        String h = bearer(mallory);

        mvc.perform(delete(base).header("Authorization", h)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get(base + "/documents").header("Authorization", h)).andExpect(status().isNotFound());
        mvc.perform(get(base + "/stats").header("Authorization", h)).andExpect(status().isNotFound());
        mvc.perform(post(base + "/search").header("Authorization", h).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"anything\"}")).andExpect(status().isNotFound());
        mvc.perform(post(base + "/ask").header("Authorization", h).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"anything\"}")).andExpect(status().isNotFound());
        assertThat(upload(mallory, id, "x.txt", "text".getBytes()).getResponse().getStatus()).isEqualTo(404);

        // The owner's collection is untouched by the failed attempts.
        mvc.perform(get(base + "/documents").header("Authorization", bearer(alice))).andExpect(status().isOk());
    }

    @Test
    void duplicateNamesAreRejectedPerOwnerButAllowedAcrossOwners() throws Exception {
        String alice = newUserToken();
        String bob = newUserToken();
        createCollection(alice, "Same name");

        mvc.perform(post("/api/collections").header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" Same name \"}"))
                .andExpect(status().isConflict());
        createCollection(bob, "Same name");
    }

    @Test
    void blankAndOverlongNamesAreRejected() throws Exception {
        String token = newUserToken();
        for (String name : new String[] {"", "   ", "x".repeat(121)}) {
            mvc.perform(post("/api/collections").header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    @Test
    void deletingACollectionRemovesItsDocumentsAndChunks() throws Exception {
        String token = newUserToken();
        long id = createCollection(token, "To delete");
        uploadAndWait(token, id, "doc.txt", "some searchable content here".getBytes());

        mvc.perform(delete("/api/collections/" + id).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/collections/" + id + "/documents").header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonNumericIdIsABadRequestInStandardFormat() throws Exception {
        String token = newUserToken();

        mvc.perform(delete("/api/collections/abc").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }
}
