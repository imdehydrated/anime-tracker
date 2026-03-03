# Architecture Guide

## Purpose

This document explains how AniRec is structured, why it is structured this way, and how the major subsystems interact. It is written for contributors making changes across backend, frontend, sidecar, and ML tooling.

## System Overview

AniRec is a four-service system:

1. `frontend/` (React SPA)
2. `backend/` (Spring Boot API)
3. `db/` (PostgreSQL + pgvector)
4. `ml-sidecar/` (FastAPI model-serving runtime)

The separation is intentional:
- Backend owns API contracts, auth, policy, and orchestration.
- Sidecar owns model inference concerns (embedding/rerank/CF prediction).
- Database owns durable state and vector/lexical retrieval indexes.
- Frontend owns user interaction and view-state behavior.

This keeps model iteration decoupled from core API lifecycle and allows independent scaling/failure handling.

## Repository Layout

Top-level:
- `backend/`: Spring Boot code and DB migrations
- `frontend/`: React app
- `ml-sidecar/`: FastAPI semantic + CF model serving
- `ml-models/`: exported model artifacts mounted into sidecar
- `notebooks/`: data prep, training, export, eval, promotion gate tooling
- `scripts/`: operational helpers (for example session preflight)

## Backend Design (`backend/`)

Backend package structure:
- `config/`: security and JWT plumbing
- `controller/`: HTTP request/response boundary
- `service/`: orchestration and business logic
- `repository/`: DB access (JPA + native SQL)
- `entity/`: relational persistence model
- `dto/`: API and integration payload shapes
- `exception/`: domain exceptions and API error mapping

### Why this layering

- Controllers stay thin so API contracts stay readable and stable.
- Services centralize behavior and policy, avoiding controller/repository coupling.
- Repositories isolate SQL/index specifics and keep retrieval optimizations local.
- DTO/entity split avoids leaking persistence details to API clients.

## Backend Core Services

### `SemanticRecommendationService`

Primary recommendation orchestrator. It:
- routes by mode (`semantic`, `similar`, `cf`)
- enforces mode semantics (`semantic` ignores seeds, `similar` requires seeds)
- builds semantic candidates
- applies explicit mode scoring (`query/seed relevance + taste + popularity`)
- applies dedupe/score calibration/reason metadata

Design reason:
- one orchestrator simplifies cross-signal policy and makes contract behavior consistent across endpoints.

### `FusionScoringService`

Provides shared normalization/clamp utilities and score record types reused by recommendation code.

Design reason:
- score math utilities stay centralized and deterministic.

### `AniListService`

Owns all AniList GraphQL calls:
- search-by-query
- fetch-by-id
- popularity paging
- active-catalog paging

It includes request pacing, retries, and short-lived cache.
Search also applies a metadata quality gate on local-first results: when critical card fields are missing (for example cover/title/genres), it performs one upstream search fetch, merges missing fields before returning/caching, backfills improved metadata into the local catalog, and applies a bounded per-item ID hydration pass for remaining incomplete rows.
`/api/anime/search` additionally applies the same format/safety controls used by recommendation filtering (`includeExtraSeasons`, `includeMovies`, `includeOnasOvasSpecials`, `includeMusic`, `includeAdult`), and cache keys include both query and filter fingerprint so toggles do not reuse stale cached result sets.

Design reason:
- external API reliability policy belongs in one integration service, not spread across features.
- local-first search keeps latency/rate usage low, and one-call fallback merge prevents visibly broken cards without forcing per-item upstream hydration.

### `AniListMetadataSyncScheduler`

Runs tiered metadata refresh jobs:
- hot popular window (6h cadence)
- daily active-catalog rotation (cursor-based)
- weekly deep sweep (resume-safe)

State is persisted in `anilist_sync_state` so jobs resume across restarts and avoid restarting from page 1 each run.
Scheduler adjusts page budgets downward when AniList rate-limit pressure is detected (429/retry-heavy windows), then recovers gradually on clean runs.

Design reason:
- freshness needs to be operationally safe under AniList limits; schedule + cursor + adaptive budget is more stable than ad-hoc manual full refreshes.

### `MlSidecarService`

Backend HTTP client for sidecar endpoints:
- `/embed`
- `/semantic/rerank`
- `/cf/recommend`
- `/health`

Design reason:
- explicit boundary for sidecar transport, timeout, and fallback behavior.
- semantic rerank responses can include `query_adherence_score`, allowing backend to keep query adherence as the primary ranking signal while still blending taste/popularity secondarily.
- sidecar is treated as required runtime infrastructure; startup validates sidecar enablement and health to avoid partial/half-working recommendation behavior.

### `AnimeEmbeddingPopulatorService`

Generates embeddings by pulling AniList metadata and calling sidecar embedding:
- legacy popularity-based population
- active-catalog population by format filters

Design reason:
- long-running embedding expansion workflow stays out of request path and remains operationally triggerable.

### `CustomEmbeddingImportService`

Imports exported JSONL embeddings into DB custom-vector column with fingerprint-based startup sync.

Design reason:
- deterministic artifact import path supports reproducible model promotion and rollback.

## API Surface

Primary API families:

Auth and users:
- `POST /api/users/register`
- `POST /api/users/login`

Anime list:
- `GET /api/users/list`
- `POST /api/users/list`
- `PUT /api/users/list/{id}`
- `DELETE /api/users/list/{id}`

Anime lookup:
- `GET /api/anime/search`
- `GET /api/anime/{id}`

Recommendations:
- `POST /api/users/recommendations/semantic` (legacy payload shape)
- `POST /api/users/recommendations/semantic/scored` (scored payload)
- `POST /api/users/recommendations/feedback`
- `GET /api/users/recommendations/feedback`
- `DELETE /api/users/recommendations/feedback/{id}`
- `POST /api/users/recommendations/custom-embeddings/import`
- `POST /api/users/recommendations/custom-embeddings/populate-active-catalog`
- `GET /api/users/recommendations/custom-embeddings/population-failures`
- `POST /api/users/recommendations/custom-embeddings/population-failures/retry`

Health:
- `GET /api/health`

Contract choice:
- both legacy and scored recommendation endpoints are kept to avoid breaking existing frontend/backward callers during iterative ranking upgrades.
- recommendation payloads may include optional scoring diagnostics (for example query relevance, taste overlap score, popularity prior, and guardrail flag) without breaking existing clients.
- feedback signals are backend-owned and persisted; frontend acts as a thin signal client.
- request payload can include additive global controls under `filters`:
  - `includeExtraSeasons`
  - `includeMovies`
  - `includeOnasOvasSpecials`
  - `includeMusic`
  - `includeAdult`
  - `popularityAttenuation` (`low|medium|high`)

## Recommendation Modes and Behavior

All recommendation requests use the same DTO with `mode`.

### `semantic` mode

Query-first semantic retrieval pipeline:
1. Normalize query text and expand shorthand terms.
2. Embed query through sidecar custom model.
3. Retrieve vector candidates from pgvector (`default pool=140`).
4. Retrieve lexical candidates from full-text + trigram indexes (`default pool=60`).
5. Merge candidates with reciprocal-rank-fusion (`default merged pool=140`).
6. Optionally rerank top candidates in sidecar.
7. Optionally run deterministic second-pass lexical expansion for broad/ambiguous queries (RAG-lite).
8. Apply score calibration.
9. Apply explicit semantic score blend:
   - logged-in: `0.70*query + 0.20*taste + 0.10*popularity`
   - logged-out: `0.85*query + 0.15*popularity`
10. Apply relevance guardrail (suppress taste and cap popularity for low-relevance hits).
11. Apply global controls filter pass (adult safety + format controls + popularity attenuation).
    - candidate pool sizing is automatically overfetched when restrictive controls are active.
    - if default controls underfill results, backend applies a safe fallback that relaxes format filters only (adult safety remains enforced).
12. Apply franchise/special dedupe.
13. Build explanation metadata.
14. Cache successful semantic responses in backend in-memory LRU (6h TTL) using a fingerprinted key:
   - mode, normalized query, limit, top-k, auth state, user profile fingerprint, model fingerprint, embeddings fingerprint.
   - controls fingerprint (prevents stale cached responses when filter toggles change).

Design reason:
- hybrid lexical + vector retrieval handles both intent semantics and exact entity mentions.

### `similar` mode

Seed-first similarity retrieval:
- build centroid from seed embeddings
- optional user-taste scoring contribution (no CF overlap blend)
- retrieve + rerank similar items

Design reason:
- explicit seed workflow has a different user intent than free-form semantic query; keeping it separate avoids ambiguous behavior.

### `cf` mode

Collaborative filtering only:
- sidecar predicts scores for unseen items from user ratings
- backend hydrates metadata

Design reason:
- pure behavior mode simplifies debugging CF quality independent of semantic retrieval.

### Feedback Signals (Thumbs)

Thumbs feedback is stored in backend (`recommendation_feedback`) and consumed server-side:
- thumbs-down is treated as immediate exclusion signal in candidate filtering.
- thumbs-up is stored for later rerank/personalization feature use.

Design reason:
- keeping feedback in backend avoids client-only state drift, supports multi-device consistency, and enables scalable downstream use (analytics/training features) without frontend coupling.

## Hybrid Scoring and Guardrails

Semantic scoring uses a lightweight hybrid blend rather than graph/PageRank:
1. Query relevance from semantic retrieval/rerank remains the primary signal.
   - sidecar emits `query_adherence_score` and backend uses it before fallback relevance fields.
2. User taste signal is blended when an authenticated profile has usable ratings/history.
3. Popularity prior is blended conservatively using metadata coverage-safe fallback:
   - `0.55 * anilist_score_norm + 0.45 * anilist_popularity_norm`
   - fallback to score-only when popularity is unavailable.
4. Guardrails reduce or suppress taste/popularity boosts when query relevance is low.
5. Query expansion stage is deterministic and bounded (token caps + confidence gate), not LLM-dependent.
6. Semantic and similar paths do not blend with CF overlap in the current runtime policy.

Design reason:
- this keeps ranking transparent and tunable while avoiding heavy graph artifact dependencies.

## Sidecar Design (`ml-sidecar/`)

Files:
- `main.py`: app startup and health
- `routes.py`: API contracts
- `semantic_model.py`: embedding + semantic rerank logic
- `cf_model.py`: CF prediction logic

Health endpoint includes:
- model load state

Design reason:
- operational visibility on model readiness is required for safe rollout/promotion.

## Data Layer and Indexing

Main semantic table: `anime_embeddings`
- metadata fields (title/genres/description/etc.)
- `anilist_popularity` used by popularity prior
- `embedding` (legacy 1536-dim)
- `embedding_custom` (authoritative 384-dim custom vector)
- `metadata_refreshed_at` and `metadata_fingerprint` drive idempotent metadata refresh and selective re-embedding

Metadata sync state table: `anilist_sync_state`
- `source_key`
- `next_page`
- `last_success_at`
- `last_error`
- `last_run_at`
- `budget_state`

Design reason:
- sync cursor and adaptive budget must survive restarts and deploys to keep refresh incremental and rate-limit safe.

Population failure ledger table: `embedding_population_failures`
- `anilist_id`
- `source`
- `failure_reason`
- `last_error`
- `attempts`
- `status` (`OPEN`, `DEAD_LETTER`, `RESOLVED`)
- `last_attempt_at`
- `next_retry_at`
- timestamps

Design reason:
- scheduled/manual population previously logged failures without durable retry visibility.
- failure ledger enables bounded retries, dead-letter tracking, and operational reporting without brute-force repopulation.
- failure reasons are typed and stable, enabling reason-aware retry policy tuning and consistent failure reporting.

Canonical artifact:
- `ml-models/anime_embeddings.jsonl` is rebuilt via `notebooks/export_semantic_embeddings.py`.
- Export joins `notebooks/data/corpus.jsonl` + `notebooks/data/anilist_anime.jsonl` so runtime import receives score/popularity/alias/tag metadata consistently.

Indexes:
- pgvector IVF for nearest-neighbor retrieval
- trigram index for title fuzzy matching
- full-text GIN index over title + genres + description

Design reason:
- vector index supports semantic similarity; lexical indexes cover literal/alias-heavy queries and cold-start mismatches.

## Frontend Design (`frontend/`)

Structure:
- `src/api/`: backend client modules (`authApi`, `animeApi`, `listApi`, `recommendationsApi`)
- `src/context/AuthContext.js`: auth state
- `src/components/`: reusable UI (`RequireAuth`, cards, rec item, feedback modal)
- `src/components/FilterControlPanel.js`: shared Sprout-style filter toggle UI for recommendation/search pages
  - recommendation pages use the same component to render a dedicated advanced popularity-attenuation selector with explanatory helper text
- `src/hooks/`: reusable state flows (`useDebounceSearch`, feedback/add-to-list hooks)
- `src/pages/`: route pages (`Home`, `Search`, `MyList`, `SmartRec`, etc.)

Design reason:
- API access is centralized to avoid duplicated request logic and simplify auth/header/error behavior.
- recommendation/search card components include deterministic cover fallbacks so incomplete metadata does not collapse card layout.

## ML and Notebook Tooling (`notebooks/`)

Core notebook pipeline:
1. `01_data_collection.ipynb`
2. `02_preprocessing.ipynb`
3. `03_semantic_training.ipynb`
4. `04_cf_training.ipynb`
5. `05_export.ipynb`

Supporting scripts:
- `semantic_multipos_experiment.py`: multi-positive semantic experiment runner
- `semantic_query_tests.py`: model-only semantic query benchmark
- `semantic_query_api_tests.py`: production-path semantic benchmark via backend endpoint
- `evaluate_models.py`: CF offline metrics
- `promotion_gate.py`: baseline-vs-candidate promotion decision

Design reason:
- scriptable evaluation/promotion avoids notebook-only manual decisions and makes model promotion auditable.

## Evaluation and Promotion Policy

Semantic promotion is intent-first:
- primary metrics: `Hit@K`, `MRR@K`
- diagnostics: unresolved titles, miss reason breakdown (`model_miss`, `catalog_miss`, `alias_miss`)
- strict gate for promotion, operational target tracked separately

CF promotion uses ranking/diversity metrics (`Recall`, `NDCG`, `HitRate`, coverage, novelty, long-tail share).

Design reason:
- semantic query search should be evaluated by query intent outcomes, not list holdout recall.

## Configuration Strategy

Config sources:
- backend: `application.yml` + env overrides
- sidecar: env variables and model artifacts in `/app/models`

Important knobs:
- semantic retrieval fusion and rerank toggles
- semantic query/taste/popularity blend weights and guardrail thresholds
- explanation provider controls
- startup custom embedding auto-sync

Design reason:
- environment-driven toggles support controlled rollout and quick rollback without code churn.

## Reliability and Performance Choices

Key decisions:
- AniList retries + pacing + caches to reduce external API fragility.
- sidecar communication forced to HTTP/1.1 for stability.
- metadata hydration deferred to final list where possible.
- semantic dedupe to reduce low-value near-duplicate franchise results.
- popularity prior is guardrailed so query intent remains primary.

## How to Extend Safely

When adding or changing recommendation behavior:
1. keep endpoint contracts stable or add compatibility path
2. keep mode semantics explicit (`semantic` vs `similar` vs `cf`)
3. add/adjust tests in service and sidecar layers
4. update benchmark scripts if evaluation semantics change
5. update docs in same session:
   - `README.md`
   - `DEV-COMMANDS.md`
   - `ARCHITECTURE.md`
   - `MILESTONES.md`
