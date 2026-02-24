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
- blends with CF candidates when applicable
- applies dedupe/score calibration/reason metadata

Design reason:
- one orchestrator simplifies cross-signal policy and makes contract behavior consistent across endpoints.

### `FusionScoringService`

Normalizes and combines semantic/CF signal families, applies optional diversity penalty, and controls explanation contribution thresholds.

Design reason:
- score math is isolated so tuning does not sprawl through orchestration logic.

### `AniListService`

Owns all AniList GraphQL calls:
- search-by-query
- fetch-by-id
- popularity paging
- active-catalog paging

It includes request pacing, retries, and short-lived cache.

Design reason:
- external API reliability policy belongs in one integration service, not spread across features.

### `MlSidecarService`

Backend HTTP client for sidecar endpoints:
- `/embed`
- `/semantic/rerank`
- `/cf/recommend`
- `/health`

Design reason:
- explicit boundary for sidecar transport, timeout, and fallback behavior.

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
- `POST /api/users/recommendations/blacklist`
- `GET /api/users/recommendations/blacklist`
- `DELETE /api/users/recommendations/blacklist/{id}`
- `POST /api/users/recommendations/custom-embeddings/import`
- `POST /api/users/recommendations/custom-embeddings/populate-active-catalog`

Health:
- `GET /api/health`

Contract choice:
- both legacy and scored recommendation endpoints are kept to avoid breaking existing frontend/backward callers during iterative ranking upgrades.
- recommendation payloads may include optional scoring diagnostics (for example query relevance, taste overlap score, popularity prior, and guardrail flag) without breaking existing clients.

## Recommendation Modes and Behavior

All recommendation requests use the same DTO with `mode`.

### `semantic` mode

Query-first semantic retrieval pipeline:
1. Normalize query text and expand shorthand terms.
2. Embed query through sidecar custom model.
3. Retrieve vector candidates from pgvector.
4. Retrieve lexical candidates from full-text + trigram indexes.
5. Merge candidates with reciprocal-rank-fusion.
6. Optionally rerank top candidates in sidecar.
7. Optionally run deterministic second-pass lexical expansion for broad/ambiguous queries.
8. Apply popularity prior with relevance guardrail.
9. Optionally blend with CF overlap (policy-dependent).
10. Apply score calibration.
11. Apply franchise/special dedupe.
12. Build explanation metadata.

Design reason:
- hybrid lexical + vector retrieval handles both intent semantics and exact entity mentions.

### `similar` mode

Seed-first similarity retrieval:
- build centroid from seed embeddings
- optional list-blend personalization
- retrieve + rerank similar items

Design reason:
- explicit seed workflow has a different user intent than free-form semantic query; keeping it separate avoids ambiguous behavior.

### `cf` mode

Collaborative filtering only:
- sidecar predicts scores for unseen items from user ratings
- backend hydrates metadata

Design reason:
- pure behavior mode simplifies debugging CF quality independent of semantic retrieval.

## Hybrid Scoring and Guardrails

Semantic scoring uses a lightweight hybrid blend rather than graph/PageRank:
1. Query relevance from semantic retrieval/rerank remains the primary signal.
2. User taste signal is blended when an authenticated profile has usable ratings/history.
3. Popularity prior is blended conservatively (AniList score/popularity now, MAL-compatible later).
4. Guardrails reduce or suppress taste/popularity boosts when query relevance is low.
5. Query expansion stage is deterministic and bounded (token caps + confidence gate), not LLM-dependent.

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
- `embedding` (legacy 1536-dim)
- `embedding_custom` (authoritative 384-dim custom vector)

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
- `src/components/`: reusable UI (`RequireAuth`, cards, rec item, blacklist modal)
- `src/hooks/`: reusable state flows (`useDebounceSearch`, blacklist/add-to-list hooks)
- `src/pages/`: route pages (`Home`, `Search`, `MyList`, `SmartRec`, etc.)

Design reason:
- API access is centralized to avoid duplicated request logic and simplify auth/header/error behavior.

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
- dynamic semantic/CF blend range
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
