-- Stores precomputed OpenAI embeddings for anime, used by the semantic recommendation engine.
-- Each row holds denormalized anime metadata + a 1536-dim vector (text-embedding-3-small).

CREATE TABLE anime_embeddings (
    id BIGSERIAL PRIMARY KEY,
    anilist_id INTEGER NOT NULL UNIQUE,
    title_romaji VARCHAR(500),
    title_english VARCHAR(500),
    cover_image VARCHAR(500),
    genres TEXT,
    description TEXT,
    average_score INTEGER,
    status VARCHAR(30),
    episodes INTEGER,
    embedding_text TEXT,
    embedding vector(1536) NOT NULL,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- IVFFlat index for fast cosine similarity queries.
-- lists=100 is tuned for ~10-20k rows.
CREATE INDEX idx_anime_embeddings_vector
    ON anime_embeddings USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

CREATE INDEX idx_anime_embeddings_anilist_id
    ON anime_embeddings (anilist_id);
