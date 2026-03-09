# Architecture Guide

## Purpose

This document explains how AniRec is structured, why it is structured this way, and how the major subsystems interact. It is written for contributors making changes across backend, frontend, sidecar, and ML tooling.

## System Overview

AniRec is a four-service system:

1. `frontend/` (React SPA with Vite tooling)
2. `backend/` (Spring Boot API)
3. `db/` (PostgreSQL + pgvector)
4. `ml-sidecar/` (FastAPI model-serving runtime)

The separation is intentional:
- Backend owns API contracts, auth, policy, and orchestration.
- Sidecar owns model inference concerns (embedding/rerank/CF prediction).
- Database owns durable state and vector/lexical retrieval indexes.
- Frontend owns user interaction and view-state behavior.
  - frontend `AuthContext` decodes JWT `sub` for lightweight username personalization without extra profile round-trips.
  - recommendation/search pages use request-sequence guards so stale async responses cannot overwrite newer user-triggered results.

This keeps model iteration decoupled from core API lifecycle and allows independent scaling/failure handling.

## Repository Layout

Top-level:
- `backend/`: Spring Boot code and DB migrations
- `frontend/`: React app (Vite-powered dev/build)
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
AniList media queries are intentionally broad and include extra metadata fields not currently used by ranking so the catalog can be future-proofed without rescraping narrow payloads.
Search also applies a metadata quality gate on local-first results: when critical card fields are missing (for example cover/title/genres), it performs one upstream search fetch, merges missing fields before returning/caching, backfills improved metadata into the local catalog, and applies a bounded per-item ID hydration pass for remaining incomplete rows.
`/api/anime/search` additionally applies the same format/safety controls used by recommendation filtering (`includeExtraSeasons`, `includeMovies`, `includeOnasOvasSpecials`, `includeMusic`, `includeAdult`), and cache keys include both query and filter fingerprint so toggles do not reuse stale cached result sets.
Detail relation payloads are catalog-scoped: related entries not present in `anime_catalog` (for example manga-only adaptation nodes) are filtered out before response serialization.

Design reason:
- external API reliability policy belongs in one integration service, not spread across features.
- local-first search keeps latency/rate usage low, and one-call fallback merge prevents visibly broken cards without forcing per-item upstream hydration.

### `AniListMetadataSyncScheduler`

Runs tiered metadata refresh jobs:
- hot popular window (6h cadence)
- daily active-catalog rotation (cursor-based)
- weekly deep sweep (resume-safe)
- optional weekly full-catalog rolling refresh (cursor-based, page-budgeted)
- optional weekly relation-graph rebuild from local catalog metadata (no AniList calls)

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

Syncs canonical catalog metadata and then generates embeddings:
- single full-catalog population path for deployment bootstrap and refresh (`populate-full-catalog`)
  - manual and scheduled runs share one cursor via `anilist_sync_state` source `catalog_populate`
  - full-catalog path supports early stop after consecutive unchanged pages to avoid unnecessary full rescans

Each refresh persists:
- canonical metadata row in `anime_catalog`
- full media payload JSON (`metadata_json`) for future-proof fields
- relation edges in `anime_relation_graph`
- vector artifacts in `anime_embeddings` (only as embedding layer)

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
- `POST /api/users/list/import/anilist?username=...&dryRun=...`
- `POST /api/users/list/import/mal?username=...&dryRun=...`

Anime lookup:
- `GET /api/anime/search`
- `GET /api/anime/{id}`

Recommendations:
- `POST /api/users/recommendations/semantic/scored` (scored payload)
- `POST /api/users/recommendations/semantic/scored/paged`
- `POST /api/users/recommendations/feedback`
- `GET /api/users/recommendations/feedback`
- `DELETE /api/users/recommendations/feedback/{id}`
- `POST /api/users/recommendations/custom-embeddings/import`
- `POST /api/users/recommendations/custom-embeddings/populate-active-catalog`
- `POST /api/users/recommendations/custom-embeddings/populate-full-catalog`
- `GET /api/users/recommendations/custom-embeddings/population-failures`
- `POST /api/users/recommendations/custom-embeddings/population-failures/retry`
- `POST /api/users/recommendations/custom-embeddings/rebuild-relation-graph`

Health:
- `GET /api/health`

Contract choice:
- `/semantic/scored` is the primary recommendation endpoint for frontend/runtime use.
- `/semantic/scored/paged` is the lazy-loading contract and supports a capped ranked window of 100 items per request context.
- recommendation payloads may include optional scoring diagnostics (for example query relevance, taste overlap score, popularity prior, and guardrail flag) without breaking existing clients.
- feedback signals are backend-owned and persisted; frontend acts as a thin signal client.
- username import endpoints are authenticated and additive:
  - AniList import maps AniList list entries directly by `anilist_id`
  - MAL import uses official MAL v2 API (`api.myanimelist.net/v2`) with `X-MAL-CLIENT-ID` and resolves `mal_id -> anilist_id` through local `anime_catalog`
  - import is add-only: existing user rows are skipped (not overwritten)
  - imported rows include normalized source score/progress/status where available
  - dry-run mode reports import deltas without mutating user list rows
- manual ops endpoints (import/populate/failure-retry) are gated for operational use:
  - disabled by default (`RECOMMENDATIONS_OPS_MANUAL_ENDPOINTS_ENABLED=false`)
  - optional `X-Ops-Token` second factor (`RECOMMENDATIONS_OPS_TOKEN`)
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
3. If early candidate pools are too small, run bounded local-catalog seeding (embed missing local catalog matches on-demand, no AniList HTTP calls).
4. Retrieve vector candidates from pgvector (`default pool=140`).
5. Retrieve lexical candidates from full-text + trigram indexes (`default pool=60`).
6. Merge candidates with reciprocal-rank-fusion (`default merged pool=140`).
7. Optionally rerank top candidates in sidecar.
8. Optionally run deterministic second-pass lexical expansion for broad/ambiguous queries (RAG-lite).
9. Apply score calibration.
10. Apply explicit semantic score blend:
   - logged-in: `0.70*query + 0.20*taste + 0.10*popularity`
   - logged-out: `0.85*query + 0.15*popularity`
   - Smart Search UI can disable list blending per request (`listWeight=0.0`) via advanced `Use List Personalization` switch.
11. Apply relevance guardrail (suppress taste and cap popularity for low-relevance hits).
12. Apply semantic quality gate for low-signal catalog rows:
   - default gate suppresses rows with weak metadata/quality (`score`, `popularity`) unless query relevance clears high-confidence override.
13. Apply global controls filter pass (adult safety + format controls + popularity attenuation).
    - candidate pool sizing is automatically overfetched when restrictive controls are active.
    - if default controls underfill results, backend applies a safe fallback that relaxes format filters only (adult safety remains enforced).
14. Apply franchise/special dedupe.
15. Build deterministic explanation metadata for every item:
   - mode-aware template path (`semantic`, `similar`, `cf`)
   - evidence slots from available signals:
     - query theme matches
     - seed/title anchors
     - taste-genre overlap
     - audience-signal fallback when evidence is sparse
   - similar-mode explanation anchors are franchise-filtered to avoid tautological lines like recommending `Haikyuu!!` because of `Haikyuu!!`.
   - optional LLM rewrite layer is best-effort only; deterministic sentence is the baseline.
16. Cache successful semantic responses in backend in-memory LRU (6h TTL) using a fingerprinted key:
   - mode, normalized query, limit, top-k, auth state, user profile fingerprint, model fingerprint, embeddings fingerprint.
17. Lazy-loaded pages keep metadata complete through local catalog-first hydration (`anime_catalog` before legacy embedding-row fallback), so later pages do not regress to sparse card fields.

Filter logic consistency:
- filter heuristics (adult/movie/ONA-OVA-special/music) are centralized in `AnimeFilterPolicy`.
- extra-season detection uses relation graph signals (`anime_relation_graph` prequel/parent edges) rather than title-pattern matching.
- entrypoint remap for excluded sequel/special candidates is graph-only (`resolveEntrypoint`), so remap behavior is deterministic and tied to catalog graph freshness instead of per-row relation fallback traversal.
- candidate sizing is centralized in `RecommendationCandidateTuning` so search/recommendation pools are tuned from one config source.
   - controls fingerprint (prevents stale cached responses when filter toggles change).

Design reason:
- hybrid lexical + vector retrieval handles both intent semantics and exact entity mentions.

### `similar` mode

Seed-first similarity retrieval:
- build centroid from seed embeddings
- if pool underfills, run bounded local-catalog seeding from seed-title lookups to embed missing local candidates (no AniList HTTP calls)
- optional user-taste scoring contribution (no CF overlap blend), controlled by advanced `Use List Personalization` switch in Similar Shows UI
- retrieve + rerank similar items

Design reason:
- explicit seed workflow has a different user intent than free-form semantic query; keeping it separate avoids ambiguous behavior.

### `cf` mode

Collaborative filtering only:
- sidecar predicts scores for unseen items from user ratings
- backend hydrates metadata
- for cold-start users (or empty sidecar CF response), backend falls back to popularity-ranked local catalog candidates and still applies standard recommendation controls/explanations

Design reason:
- pure behavior mode simplifies debugging CF quality independent of semantic retrieval.

### Feedback Signals (Thumbs)

Thumbs feedback is stored in backend (`recommendation_feedback`) and consumed server-side:
- thumbs-up contributes a positive taste signal to logged-in preference-vector construction.
- thumbs-down contributes a negative taste signal to logged-in preference-vector construction.
- thumbs-down is not a hard exclusion block in recommendation candidate filtering.

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
7. Runtime recommendation/search traffic is local-DB only; AniList HTTP calls are reserved for explicit population/sync paths.

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

Canonical catalog table: `anime_catalog`
- full AniList-backed metadata row per `anilist_id`
- `metadata_json` stores broad upstream payload for forward compatibility
- `metadata_refreshed_at` and `metadata_fingerprint` track catalog freshness

Embedding artifact table: `anime_embeddings`
- vector-serving layer for retrieval/rerank
- `embedding` (legacy 1536-dim)
- `embedding_custom` (authoritative 384-dim custom vector)
- carries denormalized card fields for serving efficiency, but source of truth is `anime_catalog`
- `metadata_fingerprint` drives selective re-embedding instead of full rewrites

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
- trigram + full-text indexes on `anime_catalog` for local-first search without runtime AniList calls
- pgvector IVF for nearest-neighbor retrieval
- trigram index for title fuzzy matching
- full-text GIN index over title + genres + description

Design reason:
- vector index supports semantic similarity; lexical indexes cover literal/alias-heavy queries and cold-start mismatches.

## Frontend Design (`frontend/`)

Structure:
- `src/api/`: backend client modules (`authApi`, `animeApi`, `listApi`, `recommendationsApi`)
  - client base-url resolution supports both `VITE_API_URL` (primary) and legacy `REACT_APP_API_URL` (compat fallback)
- `src/context/AuthContext.js`: auth state
- `src/components/`: reusable UI (`RequireAuth`, cards, rec item, feedback modal)
- `src/components/FilterControlPanel.js`: shared Sprout-style filter toggle UI for recommendation/search pages
  - recommendation pages use the same component to render a dedicated advanced popularity-attenuation selector with explanatory helper text
- `src/hooks/`: reusable state flows (`useDebounceSearch`, feedback/add-to-list hooks)
- `src/pages/`: route pages (`Home`, `Search`, `MyList`, `SmartRec`, etc.)
  - `Home` provides quick-entry routing into Smart Search, Similar, and For You flows.
  - `AnimeDetail` renders normalized text descriptions and relation-driven series navigation links (with local relation-graph hydration + search-cluster fallback when explicit relations are missing)
  - recommendation list cards render collapsed descriptions with per-card expand/collapse controls

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
- explanation provider controls + deterministic explanation quality defaults
- startup custom embedding auto-sync

Design reason:
- environment-driven toggles support controlled rollout and quick rollback without code churn.

## Reliability and Performance Choices

Key decisions:
- AniList retries + pacing + caches to reduce external API fragility.
- sidecar communication forced to HTTP/1.1 for stability.
- metadata hydration deferred to final list where possible.
- control filtering now uses an adaptive per-response metadata-hydration budget (with config floor/hard-cap) for format/adult/relation checks so movie/special/season toggles remain reliable on sparse rows and larger candidate pools.
- CF mode uses a stricter dedicated hydration cap and a per-request failure circuit breaker to reduce AniList 429 bursts during repeated For You requests.
- semantic dedupe to reduce low-value near-duplicate franchise results.
- popularity prior is guardrailed so query intent remains primary.

## AWS Production Topology (Container-First)

Deployment baseline is container-first and mirrors local service boundaries:

1. `animetracker-api` ECS/Fargate service:
   - one task with `backend` + `ml-sidecar`
   - `ml-sidecar` is private and accessed from backend via `http://127.0.0.1:5000`
2. `animetracker-web` ECS/Fargate service:
   - one task serving React build via Nginx
3. ALB path routing:
   - `/api/*` -> API target group (backend)
   - default route -> web target group (frontend)
4. RDS PostgreSQL in private subnets
5. ECR registries for backend/frontend/sidecar images

Security hardening defaults:

- CloudFront as canonical public entrypoint in front of ALB
- TLS-only ingress (CloudFront HTTPS redirect + ACM cert)
- WAF attached at CloudFront (managed rules + rate limits)
- explicit CloudFront `/api/*` behavior is required (all methods + no API caching + auth-relevant origin forwarding)
- ALB origin access constrained with CloudFront secret origin header
- ALB default listener behavior blocks direct internet traffic (`403`) when origin header is absent
- private ECS task networking and private RDS
- least-privilege IAM for deploy/runtime roles
- OIDC-based GitHub Actions authentication (no static AWS keys)
- Secrets Manager for sensitive runtime values (`JWT_SECRET`, DB creds, ops token, MAL/OpenAI secrets)
- ECR scan-on-push; CI Trivy fail-on-critical gate when CI/CD is enabled
- production default `RECOMMENDATIONS_OPS_MANUAL_ENDPOINTS_ENABLED=false`; temporary enabling should require ops token and network restriction

Scalability defaults:

- API/Web ECS services use target-tracking autoscaling (CPU + request load).
- DB concurrency is protected with RDS Proxy + tuned Hikari pool settings.
- Request overload protection is layered:
  - edge rate limiting (WAF)
  - app rate limiting (backend filter keyed by authenticated user or anonymous client identity)
    - anonymous identity is derived from proxy-safe `X-Forwarded-For` parsing (penultimate hop when multiple hops) to reduce spoofing bypass risk
- current recommendation caches are in-memory per API task; shared Redis/ElastiCache is the next scale step for cross-task cache and rate-limit consistency.

## CI/CD Control Flow (GP11)

Active workflows in `.github/workflows`:
1. `security-scan.yml`
   - runs on PR and `main`
   - builds backend/frontend/sidecar images and runs Trivy gates
2. `deploy-web.yml`
   - runs on `main` pushes affecting frontend/workflow path
   - builds/pushes frontend image to ECR (`GITHUB_SHA` tag)
   - fetches current ECS task definition from AWS, renders updated image, deploys `anirec-web`, then smoke-tests web root
3. `deploy-api.yml`
   - runs on `main` pushes affecting backend/sidecar/workflow path
   - compiles/tests backend, builds/pushes backend + sidecar images (`GITHUB_SHA` tag)
   - fetches current ECS task definition from AWS, renders updated images, deploys `anirec-api`, then smoke-tests `/api/health`

OIDC trust boundary:
- GitHub Actions runner exchanges short-lived OIDC token for AWS role credentials (`AWS_GHA_DEPLOY_ROLE_ARN`).
- No long-lived AWS access keys are stored in GitHub.
- Workflow files do not embed account IDs/secret values; runtime config comes from GitHub Variables/Secrets.
- Production environment protections (approvals/branch rules) are the operational gate before automatic deploys.

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
