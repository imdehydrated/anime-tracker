CREATE TABLE IF NOT EXISTS anime_catalog (
    id BIGSERIAL PRIMARY KEY,
    anilist_id INTEGER NOT NULL UNIQUE,
    title_romaji VARCHAR(500),
    title_english VARCHAR(500),
    title_native VARCHAR(500),
    cover_image VARCHAR(500),
    genres TEXT,
    description TEXT,
    average_score INTEGER,
    anilist_popularity INTEGER,
    status VARCHAR(30),
    episodes INTEGER,
    format VARCHAR(32),
    season VARCHAR(16),
    season_year INTEGER,
    is_adult BOOLEAN,
    metadata_json TEXT,
    metadata_refreshed_at TIMESTAMP,
    metadata_fingerprint VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_anime_catalog_anilist_id
    ON anime_catalog(anilist_id);

CREATE INDEX IF NOT EXISTS idx_anime_catalog_format
    ON anime_catalog(format);

CREATE INDEX IF NOT EXISTS idx_anime_catalog_season_year
    ON anime_catalog(season_year);

CREATE INDEX IF NOT EXISTS idx_anime_catalog_is_adult
    ON anime_catalog(is_adult);

CREATE INDEX IF NOT EXISTS idx_anime_catalog_popularity
    ON anime_catalog(anilist_popularity);

CREATE INDEX IF NOT EXISTS idx_anime_catalog_title_trgm
    ON anime_catalog USING gin (
        LOWER(COALESCE(title_romaji, '') || ' ' || COALESCE(title_english, '')) gin_trgm_ops
    );

CREATE INDEX IF NOT EXISTS idx_anime_catalog_lexical_tsv
    ON anime_catalog USING gin (
        to_tsvector(
            'simple',
            LOWER(
                COALESCE(title_romaji, '') || ' ' ||
                COALESCE(title_english, '') || ' ' ||
                COALESCE(genres, '') || ' ' ||
                COALESCE(description, '')
            )
        )
    );
