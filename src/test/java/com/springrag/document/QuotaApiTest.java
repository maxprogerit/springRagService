package com.springrag.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.springrag.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

@TestPropertySource(properties = {"rag.quota.max-collections-per-user=2", "rag.quota.max-documents-per-collection=2",
        "rag.quota.max-storage-mb-per-user=1"})
class QuotaApiTest extends IntegrationTestBase {

    @Test
    void collectionCountIsLimitedPerUser() throws Exception {
        String token = newUserToken();
        createCollection(token, "one");
        createCollection(token, "two");

        mvc.perform(post("/api/collections").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"three\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"));
        // another user is not affected
        createCollection(newUserToken(), "mine");
    }

    @Test
    void documentCountIsLimitedPerCollection() throws Exception {
        String token = newUserToken();
        long collection = createCollection(token, "docs");
        uploadAndWait(token, collection, "a.txt", "first document".getBytes());
        uploadAndWait(token, collection, "b.txt", "second document".getBytes());

        MvcResult third = upload(token, collection, "c.txt", "third document".getBytes());

        assertThat(third.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(third).get("code").asText()).isEqualTo("QUOTA_EXCEEDED");
    }

    @Test
    void storageIsLimitedPerUserAcrossCollections() throws Exception {
        String token = newUserToken();
        long a = createCollection(token, "a");
        long b = createCollection(token, "b");
        byte[] almostOneMb = filled(900 * 1024);
        assertThat(upload(token, a, "big.txt", almostOneMb).getResponse().getStatus()).isEqualTo(202);

        MvcResult second = upload(token, b, "more.txt", filled(200 * 1024));

        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(second).get("message").asText()).contains("storage limit");
    }

    /** Readable filler text (words, not one endless token) so chunking stays cheap. */
    private static byte[] filled(int size) {
        StringBuilder sb = new StringBuilder(size + 16);
        while (sb.length() < size) {
            sb.append("lorem ipsum dolor sit amet. ");
        }
        return sb.substring(0, size).getBytes();
    }
}
