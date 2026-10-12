package com.springrag.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.springrag.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** STORE_QUESTIONS=false keeps the audit rows (counts, latency, chunk ids) but never the question text. */
@TestPropertySource(properties = "rag.query-log.store-questions=false")
class QueryLogPolicyTest extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    QueryLogRetention retention;

    @Test
    void questionTextIsNotStoredWhenDisabled() throws Exception {
        String token = newUserToken();
        long collection = createCollection(token, "Private");
        uploadAndWait(token, collection, "faq.txt", "Refunds are possible within 14 days.".getBytes());
        when(chatClient.complete(anyString(), anyString())).thenReturn("14 days [1].");

        mvc.perform(post("/api/collections/" + collection + "/ask").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"SENSITIVE refunds days\"}")).andExpect(status().isOk());

        var rows = jdbc.queryForList("SELECT question, answered, latency_ms FROM queries_log WHERE collection_id = ?", collection);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("question")).isEqualTo("");
        assertThat(rows.get(0).get("answered")).isEqualTo(true);
    }

    @Test
    void rowsOlderThanTheRetentionPeriodAreDeleted() throws Exception {
        String token = newUserToken();
        long collection = createCollection(token, "Retention");
        long user = jdbc.queryForObject("SELECT owner_id FROM collections WHERE id = ?", Long.class, collection);
        jdbc.update("INSERT INTO queries_log (user_id, collection_id, question, top_k, latency_ms, answered, created_at) "
                + "VALUES (?, ?, 'old', 5, 1, true, now() - interval '200 days')", user, collection);
        jdbc.update("INSERT INTO queries_log (user_id, collection_id, question, top_k, latency_ms, answered, created_at) "
                + "VALUES (?, ?, 'recent', 5, 1, true, now() - interval '10 days')", user, collection);

        int deleted = retention.purge();

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForList("SELECT question FROM queries_log WHERE collection_id = ?", String.class, collection))
                .containsExactly("recent");
    }
}
