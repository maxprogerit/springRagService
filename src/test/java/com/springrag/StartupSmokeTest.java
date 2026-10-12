package com.springrag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.springrag.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Boots the whole app: Flyway runs, the pgvector extension exists, health is UP. */
class StartupSmokeTest extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void healthEndpointIsUpAndHidesDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void otherActuatorEndpointsAreNotExposed() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
    }

    @Test
    void pgvectorExtensionIsInstalledByMigration() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void hnswAndGinIndexesExist() {
        var indexes = jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'chunks'", String.class);

        assertThat(indexes).contains("chunks_embedding_hnsw", "chunks_tsv_gin");
    }
}
