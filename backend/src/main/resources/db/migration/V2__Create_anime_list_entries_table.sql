-- V2: Create the anime_list_entries table
-- This table stores each anime in a user's personal list

CREATE TABLE anime_list_entries (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    anilist_id INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'plan_to_watch',
    score INTEGER CHECK (score >= 1 AND score <= 10),
    episodes_watched INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    -- Foreign key: links user_id to the users table
    CONSTRAINT fk_user
        FOREIGN KEY (user_id)
        REFERENCES users(id)
        ON DELETE CASCADE,

    -- Prevent duplicate entries: same user can't add same anime twice
    CONSTRAINT unique_user_anime
        UNIQUE (user_id, anilist_id)
);

-- Index for faster lookups when fetching a user's list
CREATE INDEX idx_entries_user_id ON anime_list_entries(user_id);
