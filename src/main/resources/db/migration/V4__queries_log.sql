-- Note: stores the question text (needed to review retrieval quality) but never the answer.
CREATE TABLE queries_log (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    collection_id       BIGINT      NOT NULL REFERENCES collections (id) ON DELETE CASCADE,
    question            TEXT        NOT NULL,
    top_k               INTEGER     NOT NULL,
    latency_ms          BIGINT      NOT NULL,
    retrieved_chunk_ids BIGINT[]    NOT NULL DEFAULT '{}',
    answered            BOOLEAN     NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX queries_log_collection_idx ON queries_log (collection_id, created_at DESC);
