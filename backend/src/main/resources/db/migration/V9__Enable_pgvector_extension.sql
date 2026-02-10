-- Enable the pgvector extension for vector similarity search.
-- Requires the pgvector/pgvector Docker image (not plain postgres).
CREATE EXTENSION IF NOT EXISTS vector;
