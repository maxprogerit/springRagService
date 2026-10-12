-- Baseline: extensions only. Tables are added by later migrations, one per stage.
-- Requires the pgvector extension to be installed on the server (pgvector/pgvector image does this).
CREATE EXTENSION IF NOT EXISTS vector;
