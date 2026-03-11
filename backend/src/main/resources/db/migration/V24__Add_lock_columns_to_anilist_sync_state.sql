ALTER TABLE anilist_sync_state
    ADD COLUMN IF NOT EXISTS lock_owner VARCHAR(128) NULL,
    ADD COLUMN IF NOT EXISTS lock_until TIMESTAMP NULL;

CREATE INDEX IF NOT EXISTS idx_anilist_sync_state_lock_until
    ON anilist_sync_state(lock_until);
