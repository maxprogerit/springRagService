package com.springrag.ingestion;

import com.springrag.common.RagProperties;
import com.springrag.document.DocumentRepository;
import com.springrag.embedding.Vectors;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ChunkStore {

    private static final String INSERT = """
            INSERT INTO chunks (document_id, collection_id, page_number, chunk_index, text, embedding)
            VALUES (?, ?, ?, ?, ?, ?::vector)
            """;

    private final JdbcTemplate jdbc;
    private final DocumentRepository documents;
    private final int dimension;

    public ChunkStore(JdbcTemplate jdbc, DocumentRepository documents, RagProperties props) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.dimension = props.embedding().dimension();
    }

    /**
     * Stores all chunks and flips the document to READY in one transaction, so a document is searchable
     * completely or not at all.
     */
    @Transactional
    public void saveAndComplete(long documentId, long collectionId, int pageCount,
                                List<Chunk> chunks, List<float[]> vectors) {
        if (chunks.size() != vectors.size()) {
            throw new IllegalStateException("Got " + vectors.size() + " embeddings for " + chunks.size() + " chunks.");
        }
        String[] literals = new String[chunks.size()];
        for (int i = 0; i < literals.length; i++) {
            if (vectors.get(i).length != dimension) {
                throw new IllegalStateException("Embedding has " + vectors.get(i).length + " values, expected " + dimension);
            }
            literals[i] = Vectors.toLiteral(vectors.get(i));
        }
        jdbc.batchUpdate(INSERT, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Chunk chunk = chunks.get(i);
                ps.setLong(1, documentId);
                ps.setLong(2, collectionId);
                ps.setInt(3, chunk.pageNumber());
                ps.setInt(4, chunk.chunkIndex());
                ps.setString(5, chunk.text());
                ps.setString(6, literals[i]);
            }

            @Override
            public int getBatchSize() {
                return chunks.size();
            }
        });
        documents.markReady(documentId, pageCount);
    }
}
