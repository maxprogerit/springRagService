package com.springrag.embedding;

import com.springrag.common.RagProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Fails the application start when the database cannot serve the configured embeddings:
 * the vector column size must equal EMBEDDING_DIMENSION, and pgvector must support filtered HNSW search.
 */
@Component
@Order(0)
public class EmbeddingSchemaCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingSchemaCheck.class);
    private static final int[] MIN_PGVECTOR = {0, 8, 0};

    private final JdbcTemplate jdbc;
    private final RagProperties props;

    public EmbeddingSchemaCheck(JdbcTemplate jdbc, RagProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        verify();
        if (props.openai().apiKey() == null || props.openai().apiKey().isBlank()) {
            log.warn("OPENAI_API_KEY is not set: uploads will fail at the embedding step and /ask will not work.");
        }
    }

    public void verify() {
        List<Integer> dims = jdbc.queryForList(
                "SELECT atttypmod FROM pg_attribute WHERE attrelid = 'chunks'::regclass AND attname = 'embedding'",
                Integer.class);
        if (dims.isEmpty()) {
            throw new IllegalStateException("Column chunks.embedding does not exist; Flyway migrations did not run.");
        }
        int dbDimension = dims.get(0);
        int configured = props.embedding().dimension();
        if (dbDimension != configured) {
            throw new IllegalStateException("Embedding dimension mismatch: EMBEDDING_DIMENSION=" + configured
                    + " but chunks.embedding is vector(" + dbDimension + "). Add a Flyway migration that changes the "
                    + "column (and re-embed existing documents), or fix EMBEDDING_DIMENSION.");
        }
        List<String> versions = jdbc.queryForList(
                "SELECT extversion FROM pg_extension WHERE extname = 'vector'", String.class);
        if (versions.isEmpty() || !atLeast(versions.get(0), MIN_PGVECTOR)) {
            throw new IllegalStateException("pgvector >= 0.8.0 is required (found "
                    + (versions.isEmpty() ? "none" : versions.get(0)) + ").");
        }
        log.info("Embedding schema OK: vector({}), pgvector {}", dbDimension, versions.get(0));
    }

    static boolean atLeast(String version, int[] min) {
        String[] parts = version.split("\\.");
        for (int i = 0; i < min.length; i++) {
            int actual = i < parts.length ? Integer.parseInt(parts[i].replaceAll("\\D.*$", "")) : 0;
            if (actual != min[i]) {
                return actual > min[i];
            }
        }
        return true;
    }
}
