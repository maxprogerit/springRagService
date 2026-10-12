package com.springrag.generation;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Writes queries_log. The question is stored for retrieval review; the answer text never is. */
@Repository
public class QueryLog {

    private final JdbcTemplate jdbc;

    public QueryLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(long userId, long collectionId, String question, int topK, long latencyMs,
                       List<Long> chunkIds, boolean answered) {
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO queries_log (user_id, collection_id, question, top_k, latency_ms, retrieved_chunk_ids, answered)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """);
            ps.setLong(1, userId);
            ps.setLong(2, collectionId);
            ps.setString(3, question);
            ps.setInt(4, topK);
            ps.setLong(5, latencyMs);
            ps.setArray(6, con.createArrayOf("bigint", chunkIds.toArray()));
            ps.setBoolean(7, answered);
            return ps;
        });
    }
}
