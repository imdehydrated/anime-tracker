-- Allow rows that only have custom 384-dim embeddings.
-- This supports importing from ml-models/anime_embeddings.jsonl without requiring
-- an OpenAI 1536-dim embedding for every anime row.

ALTER TABLE anime_embeddings
    ALTER COLUMN embedding DROP NOT NULL;

