-- Migrate legacy recommendation blacklist rows into thumbs-down feedback.
INSERT INTO recommendation_feedback (
    user_id,
    anilist_id,
    signal,
    source_mode,
    query_hash,
    title,
    cover_image,
    created_at,
    updated_at
)
SELECT
    rb.user_id,
    rb.anilist_id,
    'THUMBS_DOWN',
    'legacy_blacklist',
    NULL,
    rb.title,
    rb.cover_image,
    COALESCE(rb.created_at, NOW()),
    COALESCE(rb.created_at, NOW())
FROM recommendation_blacklist rb
ON CONFLICT (user_id, anilist_id) DO UPDATE
SET
    signal = EXCLUDED.signal,
    source_mode = COALESCE(recommendation_feedback.source_mode, EXCLUDED.source_mode),
    title = COALESCE(recommendation_feedback.title, EXCLUDED.title),
    cover_image = COALESCE(recommendation_feedback.cover_image, EXCLUDED.cover_image),
    updated_at = GREATEST(recommendation_feedback.updated_at, EXCLUDED.updated_at);

DROP TABLE IF EXISTS recommendation_blacklist;
