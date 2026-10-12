package com.springrag.embedding;

import static org.assertj.core.api.Assertions.assertThatCode;
import com.springrag.support.TestProps;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.springrag.common.RagProperties;
import com.springrag.support.HashingEmbedder;
import com.springrag.support.IntegrationTestBase;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class EmbeddingSchemaCheckTest extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;

    private static RagProperties withDimension(int dimension) {
        return TestProps.custom(dimension, "http://x", "e", 0.0, 3, 10);
    }

    @Test
    void passesWhenConfiguredDimensionMatchesTheColumn() {
        assertThatCode(() -> new EmbeddingSchemaCheck(jdbc, withDimension(HashingEmbedder.DIMENSION)).verify())
                .doesNotThrowAnyException();
    }

    @Test
    void failsFastWithAnActionableMessageOnMismatch() {
        assertThatThrownBy(() -> new EmbeddingSchemaCheck(jdbc, withDimension(1536)).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EMBEDDING_DIMENSION=1536")
                .hasMessageContaining("vector(" + HashingEmbedder.DIMENSION + ")")
                .hasMessageContaining("migration");
    }
}
