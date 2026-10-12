-- content_hash: SHA-256 of the uploaded bytes, used to reject the same file uploaded twice into one collection.
-- worker: which app instance is processing the document, so a restarted instance can fail its own leftovers
-- without touching documents that another live instance is still processing.
ALTER TABLE documents ADD COLUMN content_hash CHAR(64);
ALTER TABLE documents ADD COLUMN worker VARCHAR(100);

CREATE UNIQUE INDEX documents_collection_hash_uk ON documents (collection_id, content_hash) WHERE content_hash IS NOT NULL;
CREATE INDEX documents_processing_idx ON documents (created_at) WHERE status = 'PROCESSING';
