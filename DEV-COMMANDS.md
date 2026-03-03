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

Search metadata quality check (look for missing cover/genres/title gaps in returned rows):
```powershell
curl.exe "http://localhost:8080/api/anime/search?q=blue lock"
```

Fetch by AniList ID:
```powershell
curl.exe http://localhost:8080/api/anime/16498
```

Tip:
- `GET /api/anime/{id}` now triggers AniList fallback when local metadata is incomplete and writes improved metadata back to local storage.

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

Semantic legacy endpoint:
```powershell
curl.exe -X POST http://localhost:8080/api/users/recommendations/semantic `
  -H "Content-Type: application/json" `
  -d "{\"mode\":\"semantic\",\"query\":\"healing anime about camping and nature\",\"limit\":10}"
```

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

## 10) Recommendation Blacklist

Add to blacklist:
```powershell
curl.exe -X POST http://localhost:8080/api/users/recommendations/blacklist `
  -H "Authorization: Bearer $TOKEN" `
  -H "Content-Type: application/json" `
  -d "{\"anilistId\":16498,\"title\":\"Attack on Titan\"}"
```

Get blacklist:
```powershell
curl.exe http://localhost:8080/api/users/recommendations/blacklist `
  -H "Authorization: Bearer $TOKEN"
```

Remove blacklist entry:
```powershell
curl.exe -X DELETE http://localhost:8080/api/users/recommendations/blacklist/1 `
  -H "Authorization: Bearer $TOKEN"
```

## 11) Embedding Operations

Manual import from default path:
```powershell
curl.exe -X POST http://localhost:8080/api/users/recommendations/custom-embeddings/import `
  -H "Authorization: Bearer $TOKEN"
```

Manual import from explicit path:
```powershell
curl.exe -X POST "http://localhost:8080/api/users/recommendations/custom-embeddings/import?path=C:\models\anime_embeddings.jsonl" `
  -H "Authorization: Bearer $TOKEN"
```

Populate active catalog embeddings:
```powershell
curl.exe -X POST "http://localhost:8080/api/users/recommendations/custom-embeddings/populate-active-catalog?maxPages=200&perPage=50" `
  -H "Authorization: Bearer $TOKEN"
```

Coverage tracking:
- use response stats:
  - `discovered`
  - `embedded`
  - `metadataRefreshed`
  - `activeCatalogCoverage`
  - `scoreCoverage`
  - `popularityCoverage`
  - `tagCoverage`
  - `aliasCoverage`
- compute:
  - `active_catalog_coverage = embedded / discovered`

## 11.1) Metadata Sync Scheduler (Tiered AniList Refresh)

Scheduler is disabled by default and can be enabled with env:

```powershell
$env:RECOMMENDATIONS_METADATA_SYNC_ENABLED = "true"
```

Key knobs:
- `RECOMMENDATIONS_METADATA_SYNC_HOT_POPULAR_PAGES`
- `RECOMMENDATIONS_METADATA_SYNC_DAILY_ACTIVE_PAGES`
- `RECOMMENDATIONS_METADATA_SYNC_WEEKLY_DEEP_PAGES`
- `RECOMMENDATIONS_METADATA_SYNC_PER_PAGE`
- `RECOMMENDATIONS_METADATA_SYNC_ADAPTIVE_BUDGET_ENABLED`

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
  -H "Authorization: Bearer $TOKEN"
```

Filter report by source/status:
```powershell
curl.exe "http://localhost:8080/api/users/recommendations/custom-embeddings/population-failures?source=active_catalog&status=OPEN&limit=100" `
  -H "Authorization: Bearer $TOKEN"
```

Retry due failures:
```powershell
curl.exe -X POST "http://localhost:8080/api/users/recommendations/custom-embeddings/population-failures/retry?source=active_catalog&limit=50" `
  -H "Authorization: Bearer $TOKEN"
```

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
  --output-path .\ml-models\anime_embeddings.jsonl `
  --batch-size 64
```

Note:
- keep `ml-models/anime_embeddings.jsonl` as the canonical runtime artifact for semantic retrieval/import.
- also keep timestamped snapshots (for rollback/auditing), e.g. `ml-models/anime_embeddings_YYYYMMDDTHHMMSSZ.jsonl`.
- when popularity/metadata scoring logic changes, regenerate this artifact before import + promotion benchmarks.

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
  --endpoint http://localhost:8080/api/users/recommendations/semantic `
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
- `RECOMMENDATIONS_FILTERS_UNDERFILL_MIN_RATIO`
- `RECOMMENDATIONS_FILTERS_UNDERFILL_MIN_FLOOR`
