CREATE TABLE IF NOT EXISTS recommendation_feedback (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    anilist_id INTEGER NOT NULL,
    signal VARCHAR(32) NOT NULL,
    source_mode VARCHAR(32),
    query_hash VARCHAR(64),
    title VARCHAR(255),
    cover_image VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, anilist_id)
);

CREATE INDEX IF NOT EXISTS idx_recommendation_feedback_user_signal
    ON recommendation_feedback (user_id, signal, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_recommendation_feedback_user_updated
    ON recommendation_feedback (user_id, updated_at DESC);
