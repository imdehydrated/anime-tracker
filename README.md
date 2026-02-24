# AniRec

AniRec is a full-stack anime tracking and recommendation app.
It combines a Spring Boot API, React frontend, PostgreSQL + pgvector, and a Python ML sidecar for semantic and collaborative recommendations.

## What It Does

- User auth with JWT (register/login)
- Personal anime list management (status, score, progress)
- AniList search + details
- AI recommendation modes:
  - Smart Search (semantic text query + optional taste blending when logged in)
  - Similar Shows (seed-based similarity)
  - For You (collaborative filtering)
- Recommendation explanations (single-sentence reason text)
- Recommendation blacklist support

## Architecture at a Glance

- `backend/` (Spring Boot): API, auth, recommendation orchestration
- `frontend/` (React): app UI and routes
- `db/` (PostgreSQL + pgvector): application data + vector retrieval
- `ml-sidecar/` (FastAPI): semantic model serving + CF inference
- `notebooks/`: model data prep, training, evaluation, and export

For deeper design details, see `ARCHITECTURE.md`.

## Core Recommendation Flow

- Semantic and Similar modes retrieve vector candidates and can blend with user taste profile.
- Smart Search query text is normalized (cleanup + shorthand expansion) before semantic embedding.
- Smart Search now uses hybrid semantic retrieval:
  - vector candidates from pgvector
  - indexed lexical candidates (full-text + trigram over title/genres/description)
  - vector + lexical candidates merged with reciprocal-rank-fusion policy
  - optional sidecar reranking on semantic mode when custom vectors are enabled
  - optional RSS-style graph reranking in sidecar when `ml-models/semantic_graph.npz` is available
  - semantic dedupe pass reduces same-franchise season/special clutter in top results
  - per-query score calibration to reduce overconfident outliers
- CF mode predicts unwatched anime using a trained autoencoder.
- CF ranking uses a mild popularity attenuation by default (tunable via env vars).
- Fusion can dynamically shift semantic-vs-CF weight based on how rich a user profile is.
- Fusion scoring combines available signals into a normalized rank score.
- Recommendation reason codes are contribution-aware, so explanations reflect which signal actually drove ranking.
- Frontend renders recommendation reason text from backend metadata.

## Run Locally

1. Create `.env` with at least:

```bash
JWT_SECRET=your-secret
ML_SIDECAR_ENABLED=true
RECOMMENDATIONS_USE_CUSTOM_VECTORS=true
# Optional hosted LLM rewrite for recommendation reasons (OpenAI example)
RECOMMENDATIONS_EXPLANATIONS_LLM_ENABLED=false
RECOMMENDATIONS_EXPLANATIONS_PROVIDER=openai
RECOMMENDATIONS_EXPLANATIONS_OPENAI_API_KEY=sk-...
RECOMMENDATIONS_EXPLANATIONS_OPENAI_MODEL=gpt-4o-mini
RECOMMENDATIONS_EXPLANATIONS_LLM_CACHE_SIZE=2000
RECOMMENDATIONS_EXPLANATIONS_LLM_MAX_REWRITES_PER_REQUEST=5
```

2. Start services:

```bash
docker-compose up --build
```

3. Access:

- Frontend: `http://localhost:3000`
- Backend: `http://localhost:8080`
- Sidecar health: `http://localhost:5000/health`

## Model Training and Ops

This README is intentionally overview-only.
Use these docs for details:

- `DEV-COMMANDS.md` for setup, notebook run order, eval commands, and troubleshooting
- `ARCHITECTURE.md` for design and scoring behavior
- Eval scripts auto-prune old snapshot JSONs by retention defaults (tunable flags in `DEV-COMMANDS.md`)
- Eval A/B comparison tooling includes `notebooks/eval_leaderboard.py` for ranking snapshot runs
- Promotion gating for model updates uses `notebooks/promotion_gate.py` (commands in `DEV-COMMANDS.md`)
- Direct semantic query testing uses `notebooks/semantic_query_tests.py` with a curated benchmark set
  - benchmark title resolution includes alias mapping for common English/romanized title variants
- Production-path semantic query benchmarking is available via `notebooks/semantic_query_api_tests.py`
- Offline graph artifact generation is available via `notebooks/build_semantic_graph.py`
- Embedding metadata backfill utility is available via `notebooks/enrich_embeddings_metadata.py`
- AniList graph-metadata refresh utility is available via `notebooks/backfill_anilist_graph_metadata.py`
- Semantic experiment runs support optional hard-neighbor refresh across epochs (documented in `DEV-COMMANDS.md`)

## Project Structure

```text
animetracker/
|-- backend/
|-- frontend/
|-- ml-sidecar/
|-- notebooks/
|-- ml-models/
|-- ARCHITECTURE.md
|-- DEV-COMMANDS.md
`-- docker-compose.yml
```
