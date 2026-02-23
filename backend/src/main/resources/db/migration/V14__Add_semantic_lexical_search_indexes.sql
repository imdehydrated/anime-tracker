-- Semantic lexical retrieval indexing upgrades:
-- 1) pg_trgm extension for fuzzy title similarity
-- 2) GIN trigram index for fast title similarity matches
-- 3) GIN full-text index for title/genres/description lexical ranking

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_anime_embeddings_title_trgm
    ON anime_embeddings USING gin (
        LOWER(COALESCE(title_romaji, '') || ' ' || COALESCE(title_english, '')) gin_trgm_ops
    );

CREATE INDEX IF NOT EXISTS idx_anime_embeddings_lexical_tsv
    ON anime_embeddings USING gin (
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
