ALTER TABLE anime_catalog
    ADD COLUMN IF NOT EXISTS mal_id INTEGER;

CREATE INDEX IF NOT EXISTS idx_anime_catalog_mal_id
    ON anime_catalog(mal_id);

-- Backfill from stored metadata payload when present.
-- Uses regex extraction to avoid hard-failing on rare non-JSON legacy payloads.
UPDATE anime_catalog
SET mal_id = ((regexp_match(metadata_json, '"idMal"\s*:\s*([0-9]+)'))[1])::integer
WHERE mal_id IS NULL
  AND metadata_json IS NOT NULL
  AND metadata_json ~ '"idMal"\s*:\s*[0-9]+';
