# AniRec

AniRec is a full-stack anime tracking and recommendation app.
It combines a Spring Boot API, React frontend, PostgreSQL + pgvector, and a Python ML sidecar for semantic and collaborative recommendations.
Recommendation features require the ML sidecar to be enabled and healthy at startup.

## What It Does

- User auth with JWT (register/login)
- Personal anime list management (status, score, progress)
- AniList search + details
- Search filters for format/safety controls (extra seasons, movies, ONA/OVA/specials, music, adult)
- AI recommendation modes:
  - Smart Search (semantic text query + optional taste blending when logged in)
  - Similar Shows (seed-based similarity)
  - For You (collaborative filtering)
- Recommendation explanations (single-sentence reason text)
- Recommendation feedback support (thumbs up / thumbs down) with backend persistence
  - thumbs-up and thumbs-down signals contribute to logged-in taste personalization
  - thumbs-down is no longer a hard block; it acts as a negative taste signal
- Population failure tracking and retry tooling for embedding sync
- Typed population failure reasons with reason-aware retry/dead-letter backoff policy
- Local-search metadata quality guardrails and UI cover fallbacks for incomplete AniList rows
- Global recommendation controls (extra seasons, movies, ONA/OVA/specials, music, adult toggle, popularity attenuation)
  - shared toggle-card UI across SmartRec, For You, and Search for consistent filter behavior
  - recommendation pages include a dedicated advanced popularity-attenuation selector with helper guidance

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
  - optional sidecar reranking on semantic mode when custom vectors are enabled
  - sidecar rerank now emits `query_adherence_score` and backend prioritizes it as the query-first relevance signal
  - optional deterministic second-pass query expansion for broad or underspecified queries
  - popularity-aware prior blended from AniList score + AniList popularity (guardrailed by query relevance)
  - explicit query-first scoring policy:
    - logged-in: `0.70*query + 0.20*taste + 0.10*popularity`
    - logged-out: `0.85*query + 0.15*popularity`
  - semantic response cache (in-memory LRU, 6-hour TTL) for faster repeat queries
  - semantic dedupe pass reduces same-franchise season/special clutter in top results
  - per-query score calibration to reduce overconfident outliers
  - metadata freshness is handled by tiered AniList sync jobs (hot popular window, daily active rotation, weekly deep sweep) with persisted cursor state and adaptive page budgets
  - embedding population failures are tracked with reason codes and retry schedules (`OPEN`, `DEAD_LETTER`, `RESOLVED`)
  - metadata fingerprinting prevents unnecessary re-embedding; vectors are refreshed only when embedding-relevant metadata changes
- local metadata search now performs a one-call AniList fallback merge when critical fields are missing, backfills improved metadata, and can run a bounded per-item ID hydration pass for still-incomplete rows
  - `/api/anime/search` supports additive format/safety filters and caches by `(query + filter fingerprint)` so toggles return consistent results
- CF mode predicts unwatched anime using a trained autoencoder.
- Global controls are applied consistently across semantic, similar, and CF result sets.
- Adult safety filtering is default-on (`includeAdult=false`) and can be explicitly opted into.
- CF ranking uses a separate popularity attenuation curve (tunable via dedicated CF env vars).
- Underfilled-result safeguard expands candidate fetch and applies safe format-relax fallback (adult filter still enforced) when default controls would return too few items.
- Recommendation reason codes and one-sentence explanations are generated from active mode signals (query/seed/taste).
- Frontend renders recommendation reason text from backend metadata.

## Run Locally

1. Create `.env` with at least:

```bash
JWT_SECRET=your-secret
ML_SIDECAR_ENABLED=true
ML_SIDECAR_URL=http://ml-sidecar:5000
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
- canonical semantic artifact refresh is script-based via `notebooks/export_semantic_embeddings.py`
- Eval scripts auto-prune old snapshot JSONs by retention defaults (tunable flags in `DEV-COMMANDS.md`)
- Eval A/B comparison tooling includes `notebooks/eval_leaderboard.py` for ranking snapshot runs
- Promotion gating for model updates uses `notebooks/promotion_gate.py` (commands in `DEV-COMMANDS.md`)
- Direct semantic query testing uses `notebooks/semantic_query_tests.py` with a curated benchmark set
  - benchmark title resolution includes alias mapping for common English/romanized title variants
- Production-path semantic query benchmarking is available via `notebooks/semantic_query_api_tests.py`
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
