# Development Commands

Practical command reference for running, testing, debugging, and evaluating AniRec.

All commands are PowerShell-friendly unless noted.

## 1) Prerequisites

Required tools:
- Docker Desktop
- PowerShell
- Java + Maven (for backend tests)
- Python (for notebook scripts)

Project root assumptions:
- run commands from repo root: `c:\Users\jeddh\Projects\animetracker`

## 2) Session Preflight (Run First)

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\session_preflight.ps1
```

Purpose:
- confirms working tree status
- shows latest eval snapshots
- verifies baseline references

## 3) Start and Stop the App

Start all services:
```powershell
docker-compose up --build
```

Start in background:
```powershell
docker-compose up --build -d
```

Stop services:
```powershell
docker-compose down
```

Stop and remove volumes (destructive for DB data):
```powershell
docker-compose down -v
```

## 4) Service Status and Logs

Service status:
```powershell
docker-compose ps
```

All logs:
```powershell
docker-compose logs -f
```

Backend logs:
```powershell
docker-compose logs -f backend
```

Sidecar logs:
```powershell
docker-compose logs -f ml-sidecar
```

Database logs:
```powershell
docker-compose logs -f db
```

## 5) Health Checks

Backend health:
```powershell
curl.exe http://localhost:8080/api/health
```

Sidecar health:
```powershell
curl.exe http://localhost:5000/health
```

Expected sidecar fields include:
- `semantic_model`
- `cf_model`

## 6) Auth Workflow (Register/Login)

Create request files once:
- `register.json`
- `login.json`

Example `register.json`:
```json
{"username":"testuser","email":"testuser@example.com","password":"password123"}
```

Example `login.json`:
```json
{"email":"testuser@example.com","password":"password123"}
```

Register:
```powershell
curl.exe -X POST http://localhost:8080/api/users/register `
  -H "Content-Type: application/json" `
  -d "@./register.json"
```

Login:
```powershell
curl.exe -X POST http://localhost:8080/api/users/login `
  -H "Content-Type: application/json" `
  -d "@./login.json"
```

Then set token:
```powershell
$TOKEN = "paste-jwt-here"
```

## 7) Anime List CRUD

Get list:
```powershell
curl.exe http://localhost:8080/api/users/list -H "Authorization: Bearer $TOKEN"
```

Add list entry:
```powershell
curl.exe -X POST http://localhost:8080/api/users/list `
  -H "Authorization: Bearer $TOKEN" `
  -H "Content-Type: application/json" `
  -d "{\"anilistId\":1,\"status\":\"WATCHING\"}"
```

Update list entry:
```powershell
curl.exe -X PUT http://localhost:8080/api/users/list/1 `
  -H "Authorization: Bearer $TOKEN" `
  -H "Content-Type: application/json" `
  -d "{\"status\":\"COMPLETED\",\"score\":9,\"episodesWatched\":24}"
```

Delete list entry:
```powershell
curl.exe -X DELETE http://localhost:8080/api/users/list/1 `
  -H "Authorization: Bearer $TOKEN"
```

Username import (dry-run first):
```powershell
curl.exe -X POST "http://localhost:8080/api/users/list/import/anilist?username=<ANILIST_USERNAME>&dryRun=true" `
  -H "Authorization: Bearer $TOKEN"
curl.exe -X POST "http://localhost:8080/api/users/list/import/mal?username=<MAL_USERNAME>&dryRun=true" `
  -H "Authorization: Bearer $TOKEN"
```

Run actual import:
```powershell
curl.exe -X POST "http://localhost:8080/api/users/list/import/anilist?username=<ANILIST_USERNAME>" `
  -H "Authorization: Bearer $TOKEN"
curl.exe -X POST "http://localhost:8080/api/users/list/import/mal?username=<MAL_USERNAME>" `
  -H "Authorization: Bearer $TOKEN"
```

Import response includes:
- `discovered`, `imported`, `updated`, `skipped`, `failed`
- bounded `failureSamples` for unmapped/invalid entries
- import is add-only (existing list rows are skipped, not overwritten)
- new imported rows carry normalized source `status`, `score`, and `progress`
- MAL import requires official MAL v2 client credentials (`MAL_CLIENT_ID` at minimum).
- `MAL_CLIENT_SECRET` is optional for future OAuth flows, but not required for username list reads.

## 8) Anime Search Endpoints

Search by text:
```powershell
curl.exe "http://localhost:8080/api/anime/search?q=attack on titan"
```

Search with format/safety filters:
```powershell
curl.exe "http://localhost:8080/api/anime/search?q=haikyuu&includeExtraSeasons=false&includeMovies=false&includeOnasOvasSpecials=false&includeMusic=false&includeAdult=false"
```

UI sanity check:
- open `Smart Recommendations`, `Recommended For You`, and `Search`
- confirm filter controls render as toggle cards and toggling updates results
- in recommendation pages, confirm the `Popularity Attenuation Factor` advanced selector renders as a full-width styled dropdown with helper text
- in Smart Search and Similar Shows (logged-in), confirm advanced `Use List Personalization` switch appears to the right of popularity attenuation and changes result mix when switched

Search metadata quality check (look for missing cover/genres/title gaps in returned rows):
```powershell
curl.exe "http://localhost:8080/api/anime/search?q=blue lock"
```

Fetch by AniList ID:
```powershell
curl.exe http://localhost:8080/api/anime/16498
```

Tip:
- `GET /api/anime/{id}` is local-catalog first; detail UI sanitizes description text client-side and shows relation-driven series links when available.
- detail relation links are filtered to anime present in local catalog; manga-only adaptation nodes are excluded from series navigation.
- if series links are unexpectedly empty for known franchises after a full populate, rebuild the local graph once:
```powershell
curl.exe -X POST "http://localhost:8080/api/users/recommendations/custom-embeddings/rebuild-relation-graph" -H "X-Ops-Token: <OPS_TOKEN>"
```

## 9) Recommendation Endpoint Checks

Semantic scored endpoint:
```powershell
curl.exe -X POST http://localhost:8080/api/users/recommendations/semantic/scored `
  -H "Content-Type: application/json" `
  -d "{\"mode\":\"semantic\",\"query\":\"dark psychological thriller with mind games\",\"limit\":10,\"filters\":{\"includeExtraSeasons\":false,\"includeMovies\":false,\"includeOnasOvasSpecials\":false,\"includeMusic\":false,\"includeAdult\":false,\"popularityAttenuation\":\"medium\"}}"
```

Inspect query-adherence diagnostics in scored payload:
- `query_adherence_score`
- `query_relevance_score`
- `user_taste_score`
- `popularity_prior_score`

Inspect explanation quality fields in scored payload:
- `recommendationReason` should be present for each returned item.
- `reasonCodes` should align with mode (`MATCHES_QUERY`, `SIMILAR_TO_SEED`, `MATCHES_TASTE_PROFILE`, `CF_SIGNAL`).

Note:
- `/semantic/scored` is the required semantic recommendation endpoint for app and test traffic.

Similar mode:
```powershell
curl.exe -X POST http://localhost:8080/api/users/recommendations/semantic/scored `
  -H "Content-Type: application/json" `
  -d "{\"mode\":\"similar\",\"seedIds\":[16498,101922],\"limit\":10}"
```

CF mode (auth required):
```powershell
curl.exe -X POST http://localhost:8080/api/users/recommendations/semantic/scored `
  -H "Authorization: Bearer $TOKEN" `
  -H "Content-Type: application/json" `
  -d "{\"mode\":\"cf\",\"limit\":10}"
```

CF mode with strict format controls (movie/special exclusion check):
```powershell
curl.exe -X POST http://localhost:8080/api/users/recommendations/semantic/scored `
  -H "Authorization: Bearer $TOKEN" `
  -H "Content-Type: application/json" `
  -d "{\"mode\":\"cf\",\"limit\":15,\"filters\":{\"includeExtraSeasons\":false,\"includeMovies\":false,\"includeOnasOvasSpecials\":false,\"includeMusic\":false,\"includeAdult\":false,\"popularityAttenuation\":\"medium\"}}"
```

Notes:
- CF cold-start fallback now returns popularity-ranked local catalog titles when rated-history is below threshold or sidecar CF returns empty.
- control filtering now uses an adaptive metadata-hydration budget per response, so strict format toggles remain effective even when CF overfetches candidates.
- `RECOMMENDATIONS_FILTERS_ENTRYPOINT_REMAP_MAX_HYDRATIONS` is a base floor; runtime may increase it for strict-filter requests.
- `RECOMMENDATIONS_FILTERS_ENTRYPOINT_REMAP_MAX_HYDRATIONS_CF` caps AniList relation hydration in `mode=cf` to reduce 429 risk under frequent For You refreshes.
- `RECOMMENDATIONS_FILTERS_ENTRYPOINT_REMAP_FAILURE_CIRCUIT_THRESHOLD` opens a per-request bypass circuit after repeated hydration failures to avoid repeated upstream calls.
- `RECOMMENDATIONS_SEMANTIC_CATALOG_SEEDING_ENABLED` enables bounded local-catalog embedding seeding for semantic underfill.
- `RECOMMENDATIONS_SEMANTIC_CATALOG_SEEDING_MAX_PER_QUERY` caps how many missing catalog items semantic can embed on-demand per request.
- `RECOMMENDATIONS_SIMILAR_CATALOG_SEEDING_MAX_PER_REQUEST` caps on-demand catalog embedding seeding for seed-driven similar requests.
- paged semantic/scored flow now supports up to 100 total ranked items per request context (`limit`/`pageSize` max 100).

## 10) Recommendation Feedback (Thumbs)

Submit thumbs-down feedback:
```powershell
curl.exe -X POST http://localhost:8080/api/users/recommendations/feedback `
  -H "Authorization: Bearer $TOKEN" `
  -H "Content-Type: application/json" `
  -d "{\"anilistId\":16498,\"signal\":\"thumbs_down\",\"sourceMode\":\"semantic\",\"queryContext\":\"sports anime\",\"title\":\"Haikyuu!!\"}"
```

Submit thumbs-up feedback:
```powershell
curl.exe -X POST http://localhost:8080/api/users/recommendations/feedback `
  -H "Authorization: Bearer $TOKEN" `
  -H "Content-Type: application/json" `
  -d "{\"anilistId\":16498,\"signal\":\"thumbs_up\",\"sourceMode\":\"similar\",\"title\":\"Haikyuu!!\"}"
```

List feedback:
```powershell
curl.exe http://localhost:8080/api/users/recommendations/feedback `
  -H "Authorization: Bearer $TOKEN"
```

Remove feedback entry:
```powershell
curl.exe -X DELETE http://localhost:8080/api/users/recommendations/feedback/1 `
  -H "Authorization: Bearer $TOKEN"
```

Feedback taste-weight knobs (backend env):
- `RECOMMENDATIONS_FEEDBACK_TASTE_THUMBS_UP_WEIGHT` (default `1.50`)
- `RECOMMENDATIONS_FEEDBACK_TASTE_THUMBS_DOWN_WEIGHT` (default `1.00`)

Behavior:
- both thumbs signals contribute to logged-in taste profile.
- thumbs-down is a negative taste signal, not a hard exclusion block.

## 11) Embedding Operations

Manual maintenance endpoints are disabled by default.

Enable for controlled ops session:
```powershell
$env:RECOMMENDATIONS_OPS_MANUAL_ENDPOINTS_ENABLED = "true"
```

Optional second-factor token (recommended in shared/prod-like environments):
```powershell
$env:RECOMMENDATIONS_OPS_TOKEN = "set-a-strong-random-token"
```

Manual import from default path:
```powershell
curl.exe -X POST http://localhost:8080/api/users/recommendations/custom-embeddings/import `
  -H "X-Ops-Token: $env:RECOMMENDATIONS_OPS_TOKEN"
```

Manual import from explicit path:
```powershell
curl.exe -X POST "http://localhost:8080/api/users/recommendations/custom-embeddings/import?path=C:\models\anime_embeddings.jsonl" `
  -H "X-Ops-Token: $env:RECOMMENDATIONS_OPS_TOKEN"
```

Populate catalog embeddings (single resumable full-catalog path):
```powershell
curl.exe -X POST "http://localhost:8080/api/users/recommendations/custom-embeddings/populate-full-catalog?maxPages=3000&perPage=10" `
  -H "X-Ops-Token: $env:RECOMMENDATIONS_OPS_TOKEN"
```

Note:
- these manual ops endpoints do not require JWT login.
- they are still blocked unless `RECOMMENDATIONS_OPS_MANUAL_ENDPOINTS_ENABLED=true`.
- set `RECOMMENDATIONS_OPS_TOKEN` in shared/prod-like environments and send it as `X-Ops-Token`.

Notes:
- this path stores expanded AniList media payload into `anime_embeddings.metadata_json` (not only currently-used card fields).
- relation edges are refreshed into `anime_relation_graph` during the same run.
- full-catalog page size is intentionally capped at `10` for stability with broad AniList payloads.
- all manual/scheduled catalog repopulates share one cursor (`catalog_populate` in `anilist_sync_state`) and continue from `nextPageHint`.
- full-catalog runs can stop early after consecutive unchanged pages (`stableStopReached=true`) to avoid scanning the entire catalog on routine repop runs.

Coverage tracking:
- use response stats:
  - `discovered`
  - `embedded`
  - `catalogSynced`
  - `metadataRefreshed`
  - `activeCatalogCoverage`
  - `scoreCoverage`
  - `popularityCoverage`
  - `tagCoverage`
  - `aliasCoverage`
- compute:
  - `active_catalog_coverage = embedded / discovered`

Catalog validation after full scrape:
```powershell
docker exec -it animetracker-db psql -U anime_user -d animetracker -c "SELECT COUNT(*) AS catalog_rows FROM anime_catalog;"
docker exec -it animetracker-db psql -U anime_user -d animetracker -c "SELECT COUNT(*) AS embedded_rows FROM anime_embeddings WHERE embedding_custom IS NOT NULL;"
```

Runtime recommendation/search traffic is local-DB only by default.

## 11.1) Metadata Sync Scheduler (Tiered AniList Refresh)

Scheduler is disabled by default and can be enabled with env:

```powershell
$env:RECOMMENDATIONS_METADATA_SYNC_ENABLED = "true"
```

Key knobs:
- `RECOMMENDATIONS_METADATA_SYNC_HOT_POPULAR_PAGES`
- `RECOMMENDATIONS_METADATA_SYNC_DAILY_ACTIVE_PAGES`
- `RECOMMENDATIONS_METADATA_SYNC_WEEKLY_DEEP_PAGES`
- `RECOMMENDATIONS_METADATA_SYNC_WEEKLY_FULL_CATALOG_ENABLED`
- `RECOMMENDATIONS_METADATA_SYNC_WEEKLY_FULL_CATALOG_PAGES`
- `RECOMMENDATIONS_METADATA_SYNC_FULL_CATALOG_PER_PAGE`
- `RECOMMENDATIONS_METADATA_SYNC_WEEKLY_GRAPH_REBUILD_ENABLED`
- `RECOMMENDATIONS_METADATA_SYNC_FULL_CATALOG_UNCHANGED_STOP_PAGES`
- `RECOMMENDATIONS_METADATA_SYNC_PER_PAGE`
- `RECOMMENDATIONS_METADATA_SYNC_ADAPTIVE_BUDGET_ENABLED`

Notes:
- weekly full-catalog sync is cursor-resumable and uses the same adaptive budget policy.
- weekly graph rebuild uses local `anime_catalog.metadata_json` only (no AniList calls).

Inspect persisted sync state:

```powershell
docker exec -it animetracker-db psql -U anime_user -d animetracker -c "SELECT source_key, next_page, last_success_at, last_error, last_run_at, budget_state FROM anilist_sync_state ORDER BY source_key;"
```

Inspect metadata fingerprint refresh state:

```powershell
docker exec -it animetracker-db psql -U anime_user -d animetracker -c "SELECT COUNT(*) FILTER (WHERE metadata_fingerprint IS NOT NULL) AS fingerprinted_rows, COUNT(*) FILTER (WHERE metadata_refreshed_at IS NOT NULL) AS refreshed_rows, COUNT(*) AS total_rows FROM anime_embeddings;"
```

Tail backend logs for scheduled runs:

```powershell
docker-compose logs -f backend | Select-String "AniList sync"
```

## 11.2) Population Failure Ledger and Retry

Get failure report:
```powershell
curl.exe "http://localhost:8080/api/users/recommendations/custom-embeddings/population-failures?limit=100" `
  -H "X-Ops-Token: $env:RECOMMENDATIONS_OPS_TOKEN"
```

Filter report by source/status:
```powershell
curl.exe "http://localhost:8080/api/users/recommendations/custom-embeddings/population-failures?source=active_catalog&status=OPEN&limit=100" `
  -H "X-Ops-Token: $env:RECOMMENDATIONS_OPS_TOKEN"
```

Retry due failures:
```powershell
curl.exe -X POST "http://localhost:8080/api/users/recommendations/custom-embeddings/population-failures/retry?source=active_catalog&limit=50" `
  -H "X-Ops-Token: $env:RECOMMENDATIONS_OPS_TOKEN"
```

Rebuild relation graph from local catalog metadata (no AniList calls):
```powershell
curl.exe -X POST "http://localhost:8080/api/users/recommendations/custom-embeddings/rebuild-relation-graph" `
  -H "X-Ops-Token: $env:RECOMMENDATIONS_OPS_TOKEN"
```

Note:
- season/special exclusion and entrypoint remap in recommendation/search filtering are graph-driven.
- if graph edges are stale or sparse, run the rebuild command above before debugging filter quality.

Status semantics:
- `OPEN`: retryable failure
- `DEAD_LETTER`: exceeded retry attempt threshold
- `RESOLVED`: later sync/retry succeeded

Failure reason semantics (typed):
- `RATE_LIMIT`
- `UPSTREAM_5XX`
- `NETWORK_TIMEOUT`
- `MISSING_METADATA`
- `EMBED_FAILURE`
- `VALIDATION`
- `UNKNOWN`

Report payload includes `reasonSummary` with stable per-reason counts.

## 12) Database Commands

Open psql shell:
```powershell
docker exec -it animetracker-db psql -U anime_user -d animetracker
```

Useful SQL checks:
```sql
SELECT COUNT(*) AS total_embeddings FROM anime_embeddings;
SELECT COUNT(*) AS custom_embeddings FROM anime_embeddings WHERE embedding_custom IS NOT NULL;
SELECT COUNT(*) AS popularity_populated FROM anime_embeddings WHERE anilist_popularity IS NOT NULL;
SELECT anilist_id, vector_dims(embedding_custom) FROM anime_embeddings WHERE embedding_custom IS NOT NULL LIMIT 10;
SELECT id, source_path, source_size_bytes, source_sha256, imported_at FROM custom_embedding_import_state;
```

## 13) Backend Unit Tests

Run key service test:
```powershell
cd .\backend
mvn -q -Dtest=SemanticRecommendationServiceTest test
cd ..
```

Run all backend tests:
```powershell
cd .\backend
mvn test
cd ..
```

## 14) Sidecar Validation

Python syntax check:
```powershell
python -m py_compile `
  .\ml-sidecar\app\main.py `
  .\ml-sidecar\app\semantic_model.py
```

Direct sidecar semantic rerank smoke test:
```powershell
curl.exe -X POST http://localhost:5000/semantic/rerank `
  -H "Content-Type: application/json" `
  -d "{\"query_embedding\":[0.0,0.0,0.0],\"candidate_ids\":[1,2,3],\"candidate_scores\":[0.4,0.5,0.6],\"top_k\":2}"
```

Note:
- if running sidecar tests with `pytest`, install it locally first:
```powershell
pip install pytest
```

## 15) Notebook and Model Pipeline Commands

Export reproducible catalog snapshot (GP11 source-of-truth input):
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\export_catalog_snapshot.ps1
```

Note:
- writes timestamped snapshot under `notebooks/data/catalog_snapshots/catalog_snapshot_<timestamp>/`
- emits:
  - `anime_catalog.jsonl`
  - `anime_relation_graph.jsonl`
  - `snapshot_manifest.json` (row counts + max updated_at + sha256 fingerprints)

Materialize notebook metadata from snapshot (SP11 canonical bridge):
```powershell
python .\notebooks\materialize_snapshot_metadata.py `
  --snapshot-dir .\notebooks\data\catalog_snapshots\catalog_snapshot_<timestamp> `
  --output-path .\notebooks\data\anilist_anime.jsonl `
  --fingerprint-output .\notebooks\data\catalog_snapshot_fingerprint.json
```

Note:
- this keeps Notebook 02/05 input path stable while sourcing metadata from the catalog snapshot.
- fingerprint output is used to trace model artifacts back to a specific snapshot.

Prepare canonical dataset:
```powershell
python .\notebooks\prepare_dataset.py --source .\data\raw-kaggle --output .\data\kaggle
```

Run multipos semantic experiment:
```powershell
python .\notebooks\semantic_multipos_experiment.py `
  --data-dir .\notebooks\data `
  --hard-neighbor-refresh-epochs 1 `
  --triplet-margin 0.30 `
  --labels-per-batch 10 `
  --examples-per-label 4 `
  --epochs 4
```

Rebuild canonical semantic embeddings artifact (metadata-complete JSONL):
```powershell
python .\notebooks\export_semantic_embeddings.py `
  --model-path .\ml-models\semantic `
  --corpus-path .\notebooks\data\corpus.jsonl `
  --metadata-path .\notebooks\data\anilist_anime.jsonl `
  --metadata-fingerprint-path .\notebooks\data\catalog_snapshot_fingerprint.json `
  --output-path .\ml-models\anime_embeddings.jsonl `
  --batch-size 64
```

Note:
- keep `ml-models/anime_embeddings.jsonl` as the canonical runtime artifact for semantic retrieval/import.
- also keep timestamped snapshots (for rollback/auditing), e.g. `ml-models/anime_embeddings_YYYYMMDDTHHMMSSZ.jsonl`.
- when popularity/metadata scoring logic changes, regenerate this artifact before import + promotion benchmarks.
- export now also writes `<output>_manifest.json` with input/output sha256s and optional snapshot fingerprint payload.

## 16) Evaluation and Promotion Commands

Model-only semantic benchmark:
```powershell
python .\notebooks\semantic_query_tests.py `
  --test-set-path .\notebooks\eval\semantic_query_testset.json `
  --model-path .\ml-models\semantic `
  --embeddings-path .\ml-models\anime_embeddings.jsonl `
  --top-k 10
```

Production-path semantic benchmark:
```powershell
python .\notebooks\semantic_query_api_tests.py `
  --endpoint http://localhost:8080/api/users/recommendations/semantic/scored `
  --test-set-path .\notebooks\eval\semantic_query_testset.json `
  --embeddings-path .\ml-models\anime_embeddings.jsonl `
  --top-k 10 `
  --limit 10
```

CF offline eval:
```powershell
python .\notebooks\evaluate_models.py
```

Promotion gate (strict + operational target):
```powershell
python .\notebooks\promotion_gate.py `
  --eval-dir .\notebooks\eval `
  --semantic-min-hit-at-k-delta 0.01 `
  --semantic-min-mrr-at-k-delta 0.01 `
  --semantic-target-hit-at-k-delta 0.02 `
  --semantic-target-mrr-at-k-delta 0.02 `
  --write-report .\notebooks\eval\promotion_gate_latest_strict.json `
  --fail-on-reject
```

## 17) Common Debugging Commands

Check ports in use:
```powershell
netstat -ano | findstr :8080
netstat -ano | findstr :3000
netstat -ano | findstr :5432
netstat -ano | findstr :5000
```

Restart single service:
```powershell
docker-compose restart backend
docker-compose restart ml-sidecar
docker-compose restart frontend
```

Force clean recreate:
```powershell
docker-compose down --remove-orphans
docker-compose up --build -d
```

## 18) Important Environment Variables

Core runtime:
- `JWT_SECRET`
- `ML_SIDECAR_ENABLED` (must remain `true` for recommendation features)
- `ML_SIDECAR_URL`
- `ML_SIDECAR_STARTUP_HEALTHCHECK_ENABLED`
- `ML_SIDECAR_STARTUP_HEALTHCHECK_ATTEMPTS`
- `ML_SIDECAR_STARTUP_HEALTHCHECK_DELAY_MS`
- `ANILIST_REQUEST_SPACING_MS`
- `ANILIST_SEARCH_METADATA_HYDRATION_MAX`
- `RECOMMENDATIONS_USE_CUSTOM_VECTORS`
- `CUSTOM_EMBEDDINGS_PATH`
- `AUTO_SYNC_CUSTOM_EMBEDDINGS`
- `RECOMMENDATIONS_METADATA_FAILURE_POLICY_*` (reason-aware retry/dead-letter tuning)
- `RECOMMENDATIONS_METADATA_SYNC_WEEKLY_FULL_CATALOG_ENABLED`
- `RECOMMENDATIONS_METADATA_SYNC_WEEKLY_FULL_CATALOG_PAGES`
- `RECOMMENDATIONS_METADATA_SYNC_FULL_CATALOG_PER_PAGE`
- `RECOMMENDATIONS_METADATA_SYNC_WEEKLY_GRAPH_REBUILD_ENABLED`

Semantic/fusion behavior:
- `RECOMMENDATIONS_DEFAULT_LIST_WEIGHT`
- `RECOMMENDATIONS_DEFAULT_SIMILAR_LIST_WEIGHT`
- `RECOMMENDATIONS_SEMANTIC_POPULARITY_PRIOR_ENABLED`
- `RECOMMENDATIONS_SEMANTIC_POPULARITY_PRIOR_WEIGHT_LOGGED_IN`
- `RECOMMENDATIONS_SEMANTIC_POPULARITY_PRIOR_WEIGHT_LOGGED_OUT`
- `RECOMMENDATIONS_SEMANTIC_POPULARITY_GUARDRAIL_THRESHOLD`
- `RECOMMENDATIONS_SEMANTIC_POPULARITY_GUARDRAIL_MAX_WEIGHT`
- `RECOMMENDATIONS_SEMANTIC_VECTOR_CANDIDATE_LIMIT`
- `RECOMMENDATIONS_SEMANTIC_LEXICAL_CANDIDATE_LIMIT`
- `RECOMMENDATIONS_SEMANTIC_MERGED_CANDIDATE_LIMIT`
- `RECOMMENDATIONS_SEMANTIC_TITLE_INTENT_LEXICAL_BOOST`
- `RECOMMENDATIONS_SEMANTIC_SECOND_PASS_ENABLED`
- `RECOMMENDATIONS_SEMANTIC_SECOND_PASS_CONTEXT_SIZE`
- `RECOMMENDATIONS_SEMANTIC_SECOND_PASS_MAX_ADDED_TOKENS`
- `RECOMMENDATIONS_SEMANTIC_SECOND_PASS_TRIGGER_MAX_QUERY_TOKENS`
- `RECOMMENDATIONS_SEMANTIC_SECOND_PASS_SKIP_TOP_RELEVANCE_THRESHOLD`
- `RECOMMENDATIONS_SEMANTIC_QUALITY_GATE_ENABLED`
- `RECOMMENDATIONS_SEMANTIC_QUALITY_GATE_MIN_SCORE`
- `RECOMMENDATIONS_SEMANTIC_QUALITY_GATE_MIN_POPULARITY`
- `RECOMMENDATIONS_SEMANTIC_QUALITY_GATE_HIGH_RELEVANCE_OVERRIDE`
- `RECOMMENDATIONS_SEMANTIC_SPARSE_METADATA_RELEVANCE_FLOOR`
- `RECOMMENDATIONS_SEMANTIC_CACHE_ENABLED`
- `RECOMMENDATIONS_SEMANTIC_CACHE_SIZE`
- `RECOMMENDATIONS_SEMANTIC_CACHE_TTL_HOURS`
- `RECOMMENDATIONS_SEMANTIC_MODEL_FINGERPRINT`

Explanation path:
- `RECOMMENDATIONS_CF_CONTRIBUTORS_ENABLED`
- `RECOMMENDATIONS_EXPLANATIONS_LLM_ENABLED`
- `RECOMMENDATIONS_EXPLANATIONS_PROVIDER`
- `RECOMMENDATIONS_EXPLANATIONS_OPENAI_API_KEY`
- `RECOMMENDATIONS_EXPLANATIONS_OPENAI_MODEL`

CF popularity attenuation:
- `RECOMMENDATIONS_POPULARITY_ATTENUATION_LOW`
- `RECOMMENDATIONS_POPULARITY_ATTENUATION_MEDIUM`
- `RECOMMENDATIONS_POPULARITY_ATTENUATION_HIGH`
- `RECOMMENDATIONS_CF_POPULARITY_ATTENUATION_LOW`
- `RECOMMENDATIONS_CF_POPULARITY_ATTENUATION_MEDIUM`
- `RECOMMENDATIONS_CF_POPULARITY_ATTENUATION_HIGH`
- `RECOMMENDATIONS_CF_POPULAR_FALLBACK_ENABLED`
- `RECOMMENDATIONS_CF_POPULAR_FALLBACK_MIN_RATED_ITEMS`
- `RECOMMENDATIONS_CF_POPULAR_FALLBACK_CANDIDATE_LIMIT`
- `RECOMMENDATIONS_FILTERS_UNDERFILL_MIN_RATIO`
- `RECOMMENDATIONS_FILTERS_UNDERFILL_MIN_FLOOR`

## 24) Container-First AWS Deployment Templates

Deployment templates are intentionally stored under `infra/` and not activated in `.github/workflows` by default.

Template files:
- `infra/github-actions/deploy-api.yml.template`
- `infra/github-actions/deploy-web.yml.template`
- `infra/github-actions/security-scan.yml.template`
- `infra/ecs/taskdef.api.json`
- `infra/ecs/taskdef.web.json`
- `infra/iam/github-oidc-trust-policy.json`
- `infra/iam/github-actions-deploy-policy.json`
- `infra/iam/ecs-task-runtime-policy.json`
- `infra/alb/listener-rules.md`

When ready to enable CI/CD:

```powershell
New-Item -ItemType Directory -Force .github\workflows | Out-Null
Copy-Item infra\github-actions\deploy-api.yml.template .github\workflows\deploy-api.yml
Copy-Item infra\github-actions\deploy-web.yml.template .github\workflows\deploy-web.yml
Copy-Item infra\github-actions\security-scan.yml.template .github\workflows\security-scan.yml
```

Then replace placeholders in `infra/ecs/*.json` and `infra/iam/*.json`:
- `<AWS_ACCOUNT_ID>`
- `<AWS_REGION>`
- `<RDS_HOST>`
- `<SECRETS_PREFIX>`
- `<MODEL_ARTIFACT_BUCKET>`

Recommended pre-deploy smoke command after ECS rollout:

```powershell
powershell -ExecutionPolicy Bypass -File .\infra\scripts\smoke-test.ps1 -BaseUrl "https://<your-domain>"
```

## 25) Frontend Vite Migration Maintenance

Frontend tooling now uses Vite instead of `react-scripts`.

Common recovery flow when lock/dependency state is out of sync:

```powershell
Remove-Item .\frontend\node_modules -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item .\frontend\package-lock.json -Force -ErrorAction SilentlyContinue
npm --prefix frontend install
npm --prefix frontend run build
```

If Docker production build fails on frontend dependency sync, rebuild with:

```powershell
docker build --no-cache -f frontend/Dockerfile.prod -t <frontend-image-tag> .
```

Frontend API base-url env behavior:
- Primary: `VITE_API_URL`
- Compatibility fallback: `REACT_APP_API_URL`
