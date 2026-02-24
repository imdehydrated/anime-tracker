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

Fetch by AniList ID:
```powershell
curl.exe http://localhost:8080/api/anime/16498
```

## 9) Recommendation Endpoint Checks

Semantic scored endpoint:
```powershell
curl.exe -X POST http://localhost:8080/api/users/recommendations/semantic/scored `
  -H "Content-Type: application/json" `
  -d "{\"mode\":\"semantic\",\"query\":\"dark psychological thriller with mind games\",\"limit\":10}"
```

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
  - `activeCatalogCoverage`
- compute:
  - `active_catalog_coverage = embedded / discovered`

## 12) Database Commands

Open psql shell:
```powershell
docker exec -it animetracker-db psql -U anime_user -d animetracker
```

Useful SQL checks:
```sql
SELECT COUNT(*) AS total_embeddings FROM anime_embeddings;
SELECT COUNT(*) AS custom_embeddings FROM anime_embeddings WHERE embedding_custom IS NOT NULL;
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

Note:
- keep `ml-models/anime_embeddings.jsonl` as the canonical runtime artifact for semantic retrieval/import.

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
- `ML_SIDECAR_ENABLED`
- `ML_SIDECAR_URL`
- `RECOMMENDATIONS_USE_CUSTOM_VECTORS`
- `CUSTOM_EMBEDDINGS_PATH`
- `AUTO_SYNC_CUSTOM_EMBEDDINGS`

Semantic/fusion behavior:
- `RECOMMENDATIONS_DEFAULT_LIST_WEIGHT`
- `RECOMMENDATIONS_DEFAULT_SIMILAR_LIST_WEIGHT`
- `RECOMMENDATIONS_SEMANTIC_POPULARITY_PRIOR_ENABLED`
- `RECOMMENDATIONS_SEMANTIC_POPULARITY_PRIOR_WEIGHT_LOGGED_IN`
- `RECOMMENDATIONS_SEMANTIC_POPULARITY_PRIOR_WEIGHT_LOGGED_OUT`
- `RECOMMENDATIONS_SEMANTIC_POPULARITY_GUARDRAIL_THRESHOLD`
- `RECOMMENDATIONS_SEMANTIC_POPULARITY_GUARDRAIL_MAX_WEIGHT`
- `RECOMMENDATIONS_SEMANTIC_SECOND_PASS_ENABLED`
- `RECOMMENDATIONS_SEMANTIC_SECOND_PASS_CONTEXT_SIZE`
- `RECOMMENDATIONS_SEMANTIC_SECOND_PASS_MAX_ADDED_TOKENS`
- `RECOMMENDATIONS_SEMANTIC_SECOND_PASS_TRIGGER_MAX_QUERY_TOKENS`
- `RECOMMENDATIONS_SEMANTIC_SECOND_PASS_SKIP_TOP_RELEVANCE_THRESHOLD`
- `FUSION_SEMANTIC_WEIGHT`
- `FUSION_CF_WEIGHT`
- `FUSION_DIVERSITY_PENALTY`
- `FUSION_DYNAMIC_BLEND_ENABLED`
- `FUSION_DYNAMIC_BLEND_MIN_RATED_ANIME`
- `FUSION_DYNAMIC_BLEND_MAX_RATED_ANIME`
- `FUSION_DYNAMIC_BLEND_MIN_CF_WEIGHT`
- `FUSION_DYNAMIC_BLEND_MAX_CF_WEIGHT`

Explanation path:
- `RECOMMENDATIONS_CF_CONTRIBUTORS_ENABLED`
- `RECOMMENDATIONS_EXPLANATIONS_LLM_ENABLED`
- `RECOMMENDATIONS_EXPLANATIONS_PROVIDER`
- `RECOMMENDATIONS_EXPLANATIONS_OPENAI_API_KEY`
- `RECOMMENDATIONS_EXPLANATIONS_OPENAI_MODEL`

CF popularity attenuation:
- `CF_POPULARITY_PENALTY_ALPHA`
- `CF_POPULARITY_PENALTY_SMOOTHING`
