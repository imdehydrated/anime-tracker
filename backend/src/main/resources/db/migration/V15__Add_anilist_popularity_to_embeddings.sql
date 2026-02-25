-- Add AniList popularity metadata so semantic popularity prior can blend
-- quality and popularity instead of score-only fallback.

ALTER TABLE anime_embeddings
    ADD COLUMN IF NOT EXISTS anilist_popularity INTEGER;
