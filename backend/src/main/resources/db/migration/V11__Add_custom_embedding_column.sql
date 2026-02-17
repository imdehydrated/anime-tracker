-- Add a 384-dim column for custom fine-tuned embeddings (all-MiniLM-L6-v2 based).
-- Coexists with the OpenAI 1536-dim column for gradual migration.

ALTER TABLE anime_embeddings
    ADD COLUMN embedding_custom vector(384);

-- IVFFlat index for the custom embedding column.
-- Fewer lists (50) since we'll start with fewer populated rows.
CREATE INDEX idx_anime_embeddings_custom_vector
    ON anime_embeddings USING ivfflat (embedding_custom vector_cosine_ops) WITH (lists = 50);
