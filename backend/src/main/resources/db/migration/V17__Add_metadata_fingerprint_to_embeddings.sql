ALTER TABLE anime_embeddings
    ADD COLUMN IF NOT EXISTS metadata_refreshed_at TIMESTAMP NULL,
    ADD COLUMN IF NOT EXISTS metadata_fingerprint VARCHAR(64) NULL;
