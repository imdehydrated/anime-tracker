-- Persists cursor and adaptive budget state for tiered AniList sync jobs.
-- Used to support resume-safe daily/weekly catalog rotation with rate-limit-aware pacing.

CREATE TABLE anilist_sync_state (
    source_key VARCHAR(64) PRIMARY KEY,
    next_page INTEGER NOT NULL DEFAULT 1,
    last_success_at TIMESTAMP NULL,
    last_error TEXT NULL,
    last_run_at TIMESTAMP NULL,
    budget_state VARCHAR(256) NULL
);
