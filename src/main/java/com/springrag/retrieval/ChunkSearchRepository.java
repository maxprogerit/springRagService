package com.springrag.retrieval;

import com.springrag.common.RagProperties;
import com.springrag.embedding.Vectors;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ChunkSearchRepository {

    // The inner query keeps the ORDER BY ... LIMIT directly on chunks so the planner can use the HNSW index;
    // documents is joined afterwards, only for the filename. 1 - cosine distance = cosine similarity.
    private static final String VECTOR_SQL = """
            SELECT c.id, c.document_id, d.filename, c.page_number, c.chunk_index, c.text, c.sim
            FROM (SELECT id, document_id, page_number, chunk_index, text, 1 - (embedding <=> ?::vector) AS sim
                  FROM chunks
                  WHERE collection_id = ?
                  ORDER BY embedding <=> ?::vector
                  LIMIT ?) c
            JOIN documents d ON d.id = c.document_id
            ORDER BY c.sim DESC, c.id
            """;

    // Exact variant for small collections: the CTE is read through the collection_id index and sorted by true distance,
    // so no approximate index is involved and no row can be missed.
    private static final String EXACT_VECTOR_SQL = """
            WITH scope AS MATERIALIZED (
                SELECT id, document_id, page_number, chunk_index, text, embedding FROM chunks WHERE collection_id = ?
            )
            SELECT c.id, c.document_id, d.filename, c.page_number, c.chunk_index, c.text, c.sim
            FROM (SELECT id, document_id, page_number, chunk_index, text, 1 - (embedding <=> ?::vector) AS sim
                  FROM scope
                  ORDER BY embedding <=> ?::vector
                  LIMIT ?) c
            JOIN documents d ON d.id = c.document_id
            ORDER BY c.sim DESC, c.id
            """;

    private static final String KEYWORD_SQL = """
            SELECT c.id, c.document_id, d.filename, c.page_number, c.chunk_index, c.text, r.rank
            FROM (SELECT id, document_id, page_number, chunk_index, text, ts_rank_cd(tsv, q.query) AS rank
                  FROM chunks, (SELECT to_tsquery('simple', ?) AS query) q
                  WHERE collection_id = ? AND tsv @@ q.query
                  ORDER BY rank DESC, id
                  LIMIT ?) r
            JOIN chunks c ON c.id = r.id
            JOIN documents d ON d.id = c.document_id
            ORDER BY r.rank DESC, c.id
            """;

    private static final RowMapper<ScoredChunk> MAPPER = (rs, i) -> new ScoredChunk(
            rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getString(6),
            rs.getDouble(7));

    private final JdbcTemplate jdbc;
    private final int exactSearchMaxChunks;

    public ChunkSearchRepository(JdbcTemplate jdbc, RagProperties props) {
        this.jdbc = jdbc;
        this.exactSearchMaxChunks = props.retrieval().exactSearchMaxChunks();
    }

    public int countChunks(long collectionId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM chunks WHERE collection_id = ?", Integer.class, collectionId);
        return count == null ? 0 : count;
    }

    /**
     * Nearest chunks by cosine similarity within one collection. HNSW is an approximate index that normally filters
     * after scanning; with pgvector >= 0.8 iterative scan keeps scanning until enough rows pass the collection
     * filter, so a small collection inside a large table still returns a full result.
     */
    @Transactional(readOnly = true)
    public List<ScoredChunk> vectorSearch(long collectionId, float[] query, int limit) {
        String literal = Vectors.toLiteral(query);
        if (countChunks(collectionId) <= exactSearchMaxChunks) {
            return jdbc.query(EXACT_VECTOR_SQL, MAPPER, collectionId, literal, literal, limit);
        }
        // SET LOCAL cannot take bind parameters; limit is an int computed by us, never user text.
        jdbc.execute("SET LOCAL hnsw.iterative_scan = strict_order");
        jdbc.execute("SET LOCAL hnsw.ef_search = " + Math.max(100, limit));
        return jdbc.query(VECTOR_SQL, MAPPER, literal, collectionId, literal, limit);
    }

    /** Chunks matching any term of the tsquery, best ts_rank_cd first. {@code tsQuery} comes from KeywordQuery. */
    @Transactional(readOnly = true)
    public List<ScoredChunk> keywordSearch(long collectionId, String tsQuery, int limit) {
        return jdbc.query(KEYWORD_SQL, MAPPER, tsQuery, collectionId, limit);
    }
}
