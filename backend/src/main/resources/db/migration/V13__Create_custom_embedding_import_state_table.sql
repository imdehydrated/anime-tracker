-- Tracks the source fingerprint of custom embedding imports so startup sync
-- can skip reimport when anime_embeddings.jsonl content has not changed.

CREATE TABLE custom_embedding_import_state (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    source_path VARCHAR(1024) NOT NULL,
    source_last_modified TIMESTAMP NULL,
    source_size_bytes BIGINT NOT NULL,
    source_sha256 VARCHAR(64) NOT NULL,
    imported_at TIMESTAMP NOT NULL DEFAULT NOW()
);
