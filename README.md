# AniRec

[Live app](https://d2twcwm8eoud49.cloudfront.net/)

AniRec is a full-stack anime tracking and recommendation app.
It combines a Spring Boot API, React + Vite frontend, PostgreSQL + pgvector, and a Python ML sidecar for semantic and collaborative recommendations.
Recommendation features require the ML sidecar to be enabled and healthy at startup.

## What It Does

- User auth with JWT (register/login)
  - logged-in UI personalization uses JWT subject (`username`) for greeting text in navbar/home
- Personal anime list management (status, score, progress)
- Username-based list import endpoints (AniList + MAL)
  - add-only behavior: existing list entries are preserved; missing entries are imported with score/progress/status
- AniList search + details
- Interactive home entrypoints for Smart Search / Similar / For You routing
- Anime detail page safety/UX upgrades:
  - description rendering is normalized/safe (no raw HTML injection)
  - long descriptions use show-more/show-less
  - relation-driven series navigation links are shown from catalog metadata or local relation graph edges
- Search filters for format/safety controls (extra seasons, movies, ONA/OVA/specials, music, adult)
- AI recommendation modes:
  - Smart Search (semantic text query + optional taste blending when logged in)
  - Similar Shows (seed-based similarity)
  - For You (collaborative filtering)
- Recommendation explanations (single-sentence reason text)
- Recommendation cards support expandable descriptions (`Show more` / `Show less`) for longer synopsis text
- SmartRec and Search UI now guard against stale async responses during rapid mode/filter changes
- Recommendation feedback support (thumbs up / thumbs down) with backend persistence
  - thumbs-up and thumbs-down signals contribute to logged-in taste personalization
  - thumbs-down is no longer a hard block; it acts as a negative taste signal
- Population failure tracking and retry tooling for embedding sync
- Full-catalog scrape and repopulation path (`populate-full-catalog`) for deployment-time metadata completeness
  - AniList scrape queries now request a broad media payload (metadata beyond current runtime usage) and persist it in `metadata_json` for forward-compatible feature work.
  - scraped AniList metadata is now persisted in a dedicated `anime_catalog` table, decoupled from vector storage in `anime_embeddings`
  - manual full-catalog runs are resumable (cursor persisted in `anilist_sync_state`) and can stop early on stable unchanged windows to reduce repeat scan cost
  - relation graph can be rebuilt directly from local catalog metadata via ops endpoint (`rebuild-relation-graph`) without hitting AniList
- Typed population failure reasons with reason-aware retry/dead-letter backoff policy
- Local-search metadata quality guardrails and UI cover fallbacks for incomplete AniList rows
- Global recommendation controls (extra seasons, movies, ONA/OVA/specials, music, adult toggle, popularity attenuation)
  - shared toggle-card UI across SmartRec, For You, and Search for consistent filter behavior
  - recommendation pages include a dedicated advanced popularity-attenuation selector with helper guidance
  - Smart Search and Similar Shows include advanced `Use List Personalization` switch controls for logged-in users

## Architecture at a Glance

- `backend/` (Spring Boot): API, auth, recommendation orchestration
- `frontend/` (React + Vite): app UI and routes
- `db/` (PostgreSQL + pgvector): application data + vector retrieval
- `ml-sidecar/` (FastAPI): semantic model serving + CF inference
- `notebooks/`: model data prep, training, evaluation, and export

For deeper design details, see `ARCHITECTURE.md`.

## Core Recommendation Flow

- Semantic and Similar modes retrieve vector candidates and can blend with user taste profile.
- Paged recommendation responses support lazy loading up to 100 ranked items per request context.
- Smart Search query text is normalized (cleanup + shorthand expansion) before semantic embedding.
- Smart Search now uses hybrid semantic retrieval:
  - vector candidates from pgvector
  - indexed lexical candidates (full-text + trigram over title/genres/description)
  - optional sidecar reranking on semantic mode when custom vectors are enabled
  - sidecar rerank now emits `query_adherence_score` and backend prioritizes it as the query-first relevance signal
  - optional deterministic second-pass query expansion for broad or underspecified queries
  - bounded local catalog seeding can embed missing catalog hits on-demand (local DB only) when semantic candidate pools underfill
  - popularity-aware prior blended from AniList score + AniList popularity (guardrailed by query relevance)
  - explicit query-first scoring policy:
    - logged-in: `0.70*query + 0.20*taste + 0.10*popularity`
    - logged-out: `0.85*query + 0.15*popularity`
  - semantic response cache (in-memory LRU, 6-hour TTL) for faster repeat queries
  - semantic dedupe pass reduces same-franchise season/special clutter in top results
  - per-query score calibration to reduce overconfident outliers
  - metadata freshness is handled by tiered AniList sync jobs (hot popular window, daily active rotation, weekly deep sweep) with persisted cursor state and adaptive page budgets
  - optional weekly full-catalog rolling refresh and weekly local relation-graph rebuild can run automatically under metadata-sync config
  - embedding population failures are tracked with reason codes and retry schedules (`OPEN`, `DEAD_LETTER`, `RESOLVED`)
  - metadata fingerprinting prevents unnecessary re-embedding; vectors are refreshed only when embedding-relevant metadata changes
- local metadata search now reads from `anime_catalog` first (falls back to `anime_embeddings` only for compatibility)
- recommendation metadata hydration for result cards is catalog-first (`anime_catalog`) so lazy-loaded pages keep cover/description/title fields populated without relying on live AniList calls
  - runtime search/recommendation flows are local-DB only (no AniList HTTP fallback calls)
  - `/api/anime/search` supports additive format/safety filters and caches by `(query + filter fingerprint)` so toggles return consistent results
  - extra-season exclusion and entrypoint remap are relation-graph-first (`anime_relation_graph`) with no title-pattern fallback
- CF mode predicts unwatched anime using a trained autoencoder.
  - if a user has too little rating history, CF now falls back to local popularity-ranked recommendations (still filtered by your active controls)
- Global controls are applied consistently across semantic, similar, and CF result sets.
- Adult safety filtering is default-on (`includeAdult=false`) and can be explicitly opted into.
- CF ranking uses a separate popularity attenuation curve (tunable via dedicated CF env vars).
- Underfilled-result safeguard expands candidate fetch and applies safe format-relax fallback (adult filter still enforced) when default controls would return too few items.
- Control filtering uses adaptive metadata-hydration budget per response (based on candidate pool + strict toggles) so `includeMovies=false` and season/special exclusion remain reliable in larger CF/semantic pools.
- Recommendation reason codes and one-sentence explanations are generated from active mode signals (query/seed/taste).
- Deterministic explanation generation now uses evidence slots (query themes, seed anchors, taste overlap) for all returned items before any optional LLM rewrite.
- Frontend renders recommendation reason text from backend metadata.
- Frontend uses `/api/users/recommendations/semantic/scored` as the canonical semantic endpoint.

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
RECOMMENDATIONS_EXPLANATIONS_LLM_MAX_REWRITES_PER_REQUEST=10
# Filter-hydration safety knobs (helps prevent AniList rate-limit spikes in For You mode)
RECOMMENDATIONS_FILTERS_ENTRYPOINT_REMAP_MAX_HYDRATIONS=8
RECOMMENDATIONS_FILTERS_ENTRYPOINT_REMAP_MAX_HYDRATIONS_HARD_CAP=12
RECOMMENDATIONS_FILTERS_ENTRYPOINT_REMAP_MAX_HYDRATIONS_CF=3
RECOMMENDATIONS_FILTERS_ENTRYPOINT_REMAP_FAILURE_CIRCUIT_THRESHOLD=3
ANILIST_SEARCH_METADATA_HYDRATION_MAX=25
RECOMMENDATIONS_SEMANTIC_CATALOG_SEEDING_ENABLED=true
RECOMMENDATIONS_SEMANTIC_CATALOG_SEEDING_MAX_PER_QUERY=12
RECOMMENDATIONS_SIMILAR_CATALOG_SEEDING_MAX_PER_REQUEST=12
RECOMMENDATIONS_SEMANTIC_QUALITY_GATE_ENABLED=true
RECOMMENDATIONS_SEMANTIC_QUALITY_GATE_MIN_SCORE=65
RECOMMENDATIONS_SEMANTIC_QUALITY_GATE_MIN_POPULARITY=2000
RECOMMENDATIONS_CF_POPULAR_FALLBACK_ENABLED=true
RECOMMENDATIONS_CF_POPULAR_FALLBACK_MIN_RATED_ITEMS=3
RECOMMENDATIONS_CF_POPULAR_FALLBACK_CANDIDATE_LIMIT=100
RECOMMENDATIONS_OPS_MANUAL_ENDPOINTS_ENABLED=false
# Optional second-factor header token for manual ops endpoints
RECOMMENDATIONS_OPS_TOKEN=
# Required for MAL username import endpoint (/api/users/list/import/mal)
MAL_CLIENT_ID=your-mal-client-id
# Optional (not required for username list reads; kept for future OAuth flows)
MAL_CLIENT_SECRET=your-mal-client-secret
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
- SP11 catalog-aligned notebook flow:
  - export snapshot: `scripts/export_catalog_snapshot.ps1`
  - materialize notebook metadata bridge: `notebooks/materialize_snapshot_metadata.py`
  - export script emits reproducibility manifest (`*_manifest.json`) with input/output hashes
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
|-- infra/
|-- ARCHITECTURE.md
|-- DEV-COMMANDS.md
`-- docker-compose.yml
```

## Deployment Overview (AWS)

We deployed AniRec using a container-first AWS setup:

- CloudFront is the public entrypoint in front of ALB (HTTPS redirect + security headers + WAF).
- ALB is origin-only and should return `403` for direct internet requests (CloudFront header-gated).
- Backend and ML sidecar run together in one ECS/Fargate task.
- Frontend runs as a separate ECS/Fargate service behind Nginx.
- An Application Load Balancer routes `/api/*` to the API service and all other routes to the web service.
- PostgreSQL runs in RDS behind RDS Proxy for connection stability under ECS scale, and the app uses local catalog + embeddings data from that database.
- Runtime secrets are stored in AWS Secrets Manager.
- Container images are built locally/CI, pushed to ECR, then rolled out by updating ECS task definitions/services.
- GitHub Actions workflows are active under `.github/workflows` for security scan + API deploy + web deploy.
  - Deploy workflows use AWS OIDC role assumption (no static AWS keys).
  - Workflow files contain placeholders only; all environment-specific values are read from GitHub Variables/Secrets.
  - Manual ECR/ECS deployment remains documented as fallback in `DEPLOYMENT.md`.
- Backend uses layered rate limiting (edge + app-level) and in-memory caches; shared Redis cache is the next scale step for multi-task consistency.

Infra templates and task definitions are stored under `infra/`.
Detailed step-by-step deployment and operations instructions are in `DEPLOYMENT.md`.
