CREATE TABLE documents (
    id            BIGSERIAL PRIMARY KEY,
    collection_id BIGINT       NOT NULL REFERENCES collections (id) ON DELETE CASCADE,
    filename      VARCHAR(255) NOT NULL,
    content_type  VARCHAR(100) NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    page_count    INTEGER,
    status        VARCHAR(20)  NOT NULL CHECK (status IN ('PROCESSING', 'READY', 'FAILED')),
    error_message VARCHAR(500),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX documents_collection_idx ON documents (collection_id);

-- collection_id is denormalized on purpose: the vector query filters by it without joining documents,
-- which lets the planner use the HNSW index and pgvector's iterative scan for filtered search.
CREATE TABLE chunks (
    id            BIGSERIAL PRIMARY KEY,
    document_id   BIGINT  NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    collection_id BIGINT  NOT NULL REFERENCES collections (id) ON DELETE CASCADE,
    page_number   INTEGER NOT NULL,
    chunk_index   INTEGER NOT NULL,
    text          TEXT    NOT NULL,
    -- ${embeddingDimension} comes from EMBEDDING_DIMENSION (Flyway placeholder). The app verifies it at startup.
    embedding     vector(${embeddingDimension}) NOT NULL,
    -- 'simple' config: no stemming and no stop words, so it is language-neutral. Stop words are dropped in the query builder.
    tsv           tsvector GENERATED ALWAYS AS (to_tsvector('simple', text)) STORED,
    CONSTRAINT chunks_document_index_uk UNIQUE (document_id, chunk_index)
);
CREATE INDEX chunks_collection_idx ON chunks (collection_id);

-- HNSW rather than IVFFlat: it needs no training step (IVFFlat must be built on existing data to pick good lists),
-- gives better recall/latency at this scale and keeps working while the table grows.
-- Cost: slower inserts and more memory. Limit: pgvector HNSW supports at most 2000 dimensions.
CREATE INDEX chunks_embedding_hnsw ON chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX chunks_tsv_gin ON chunks USING gin (tsv);
