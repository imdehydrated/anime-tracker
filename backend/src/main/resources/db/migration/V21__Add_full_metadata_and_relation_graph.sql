ALTER TABLE anime_embeddings
    ADD COLUMN IF NOT EXISTS format VARCHAR(32) NULL,
    ADD COLUMN IF NOT EXISTS season VARCHAR(16) NULL,
    ADD COLUMN IF NOT EXISTS season_year INTEGER NULL,
    ADD COLUMN IF NOT EXISTS is_adult BOOLEAN NULL,
    ADD COLUMN IF NOT EXISTS metadata_json TEXT NULL;

CREATE INDEX IF NOT EXISTS idx_anime_embeddings_format ON anime_embeddings(format);
CREATE INDEX IF NOT EXISTS idx_anime_embeddings_season_year ON anime_embeddings(season_year);
CREATE INDEX IF NOT EXISTS idx_anime_embeddings_is_adult ON anime_embeddings(is_adult);

CREATE TABLE IF NOT EXISTS anime_relation_graph (
    anime_id INTEGER NOT NULL,
    related_anime_id INTEGER NOT NULL,
    relation_type VARCHAR(40) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (anime_id, related_anime_id, relation_type)
);

CREATE INDEX IF NOT EXISTS idx_anime_relation_graph_anime_id
    ON anime_relation_graph(anime_id);

CREATE INDEX IF NOT EXISTS idx_anime_relation_graph_related_id
    ON anime_relation_graph(related_anime_id);

CREATE INDEX IF NOT EXISTS idx_anime_relation_graph_relation_type
    ON anime_relation_graph(relation_type);
