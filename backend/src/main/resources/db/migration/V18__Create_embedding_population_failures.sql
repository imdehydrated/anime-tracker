CREATE TABLE IF NOT EXISTS embedding_population_failures (
    id BIGSERIAL PRIMARY KEY,
    anilist_id INTEGER NOT NULL,
    source VARCHAR(64) NOT NULL,
    failure_reason VARCHAR(64) NOT NULL,
    last_error TEXT,
    attempts INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    last_attempt_at TIMESTAMP NOT NULL DEFAULT NOW(),
    next_retry_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (anilist_id, source)
);

CREATE INDEX IF NOT EXISTS idx_embedding_population_failures_status_retry
    ON embedding_population_failures (status, next_retry_at, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_embedding_population_failures_source_status
    ON embedding_population_failures (source, status);
