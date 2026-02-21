# Development Commands

Quick reference for running the AniRec app locally.

---

## Starting the App

```powershell
# Start all services (database, backend, frontend)
docker-compose up

# Start in detached mode (runs in background)
docker-compose up -d

# Rebuild containers and start (use after code changes)
docker-compose up --build

# Rebuild specific service only
docker-compose up --build backend
```

---

## Fusion Scoring Config (Phase 1)

```powershell
# Optional .env overrides for recommendation fusion infrastructure
FUSION_SEMANTIC_WEIGHT=0.6
FUSION_CF_WEIGHT=0.4
FUSION_DIVERSITY_PENALTY=0.10
FUSION_CF_CANDIDATE_MULTIPLIER=2

# Restart backend after changing fusion config
docker-compose restart backend
```

---

## Phase 8 Hybrid Blend + Explanations

```powershell
# Dynamic semantic-vs-CF blending by profile richness
FUSION_DYNAMIC_BLEND_ENABLED=true
FUSION_DYNAMIC_BLEND_MIN_RATED_ANIME=10
FUSION_DYNAMIC_BLEND_MAX_RATED_ANIME=80
FUSION_DYNAMIC_BLEND_MIN_CF_WEIGHT=0.15
FUSION_DYNAMIC_BLEND_MAX_CF_WEIGHT=0.55

# Optional one-sentence CF contributor hint (off by default)
RECOMMENDATIONS_CF_CONTRIBUTORS_ENABLED=false

# Restart backend after changing these values
docker-compose restart backend
```

---

## Recommendation List-Weight Defaults

```powershell
# Global defaults used when request listWeight is omitted
RECOMMENDATIONS_DEFAULT_LIST_WEIGHT=0.20
RECOMMENDATIONS_DEFAULT_SIMILAR_LIST_WEIGHT=0.00

# Example: stronger list influence for Smart Search globally
RECOMMENDATIONS_DEFAULT_LIST_WEIGHT=0.35

# Restart backend after changing defaults
docker-compose restart backend
```

---

## Stopping the App

```powershell
# Stop all services (Ctrl+C if running in foreground, or:)
docker-compose down

# Stop all services and remove orphan containers (recommended after refactors)
docker-compose down --remove-orphans

# Stop and remove volumes (clears database data)
docker-compose down -v
```

---

## Viewing Logs

```powershell
# View all logs
docker-compose logs

# Follow logs in real-time
docker-compose logs -f

# View specific service logs
docker-compose logs backend
docker-compose logs frontend
docker-compose logs db

# Follow specific service
docker-compose logs -f backend
```

---

## Checking Status

```powershell
# See running containers
docker-compose ps

# See all containers (including stopped)
docker-compose ps -a
```

---

## Database Access

```powershell
# Connect to PostgreSQL CLI (use anime_user, not postgres)
docker exec -it animetracker-db psql -U anime_user -d animetracker

# Common psql commands once connected:
#   \dt          - list tables
#   \d users     - describe users table
#   \d anime_embeddings  - describe embeddings table
#   SELECT * FROM users;
#   SELECT COUNT(*) FROM anime_embeddings;  - check embedding count
#   \q           - quit
```

---

## Troubleshooting

```powershell
# Restart a specific service
docker-compose restart backend

# Force recreate containers
docker-compose up --force-recreate

# If Docker says "network ... is still in use":
# 1) remove orphan containers
docker-compose down --remove-orphans
# 2) list remaining containers attached to that network
docker ps -a --filter network=animetracker_animetracker-network
# 3) remove leftovers manually, then run docker-compose down again

# Remove all containers and rebuild fresh
docker-compose down
docker-compose up --build

# Check if ports are in use (PowerShell)
netstat -ano | findstr :8080
netstat -ano | findstr :3000
netstat -ano | findstr :5432
```

---

## AniList Rate-Limit Diagnostics

```powershell
# Watch backend logs for AniList throttling / retry behavior
docker-compose logs -f backend | findstr /I "AniList rate limited retry retryable"

# Quick smoke test for repeated search calls (should hit cache after first call)
curl.exe "http://localhost:8080/api/anime/search?q=one%20piece"
curl.exe "http://localhost:8080/api/anime/search?q=one%20piece"
curl.exe "http://localhost:8080/api/anime/search?q=one%20piece"
```

---

## Testing Endpoints (Public)

**Note:** PowerShell doesn't handle inline JSON well with curl. Use JSON files instead (see below).

### JSON Test Files

Create these files in the project root for testing:

- `register.json` - `{"username":"testuser","email":"testuser@example.com","password":"password123"}`
- `login.json` - `{"email":"testuser@example.com","password":"password123"}`
- `add-anime.json` - `{"anilistId":1,"status":"watching"}`
- `update-anime.json` - `{"status":"completed","score":9,"episodesWatched":24}`

These are gitignored (*.json rule) so they won't be committed.

```powershell
# Health check
curl.exe http://localhost:8080/api/health

# Register user (use @./filename to read JSON from file)
curl.exe -X POST http://localhost:8080/api/users/register -H "Content-Type: application/json" -d "@./register.json"

# Login (returns JWT token - save it to $TOKEN variable)
curl.exe -X POST http://localhost:8080/api/users/login -H "Content-Type: application/json" -d "@./login.json"
```

---

## Testing Endpoints (Authenticated)

After logging in, save the token to a PowerShell variable:

```powershell
$TOKEN = "paste-your-token-here"
```

### Anime List CRUD

```powershell
# Add anime to your list (CREATE)
curl.exe -X POST http://localhost:8080/api/users/list -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d "@./add-anime.json"

# Get your anime list (READ)
curl.exe http://localhost:8080/api/users/list -H "Authorization: Bearer $TOKEN"

# Update an entry - replace {id} with the entry's id (UPDATE)
curl.exe -X PUT http://localhost:8080/api/users/list/{id} -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d "@./update-anime.json"

# Delete an entry - replace {id} with the entry's id (DELETE)
curl.exe -X DELETE http://localhost:8080/api/users/list/{id} -H "Authorization: Bearer $TOKEN"
```

### Anime Search (Public - no token needed)

```powershell
# Search for anime by title
curl.exe http://localhost:8080/api/anime/search?q=naruto

# Search with spaces (use quotes around URL)
curl.exe "http://localhost:8080/api/anime/search?q=attack on titan"
```

### Testing Auth Protection

```powershell
# Should get 401/403 - no token provided
curl.exe http://localhost:8080/api/users/list

# Should succeed - valid token provided
curl.exe http://localhost:8080/api/users/list -H "Authorization: Bearer $TOKEN"
```

---

## Recommendation Endpoint Contract Checks

```powershell
# Legacy shape: List<AnimeInfo>
curl.exe -X POST http://localhost:8080/api/users/recommendations/semantic `
  -H "Content-Type: application/json" `
  -d "{\"mode\":\"semantic\",\"query\":\"dark thriller\",\"limit\":5}"

# Scored shape: List<RecommendationResponse> { anime, fusionScore, reasonCodes }
curl.exe -X POST http://localhost:8080/api/users/recommendations/semantic/scored `
  -H "Content-Type: application/json" `
  -d "{\"mode\":\"semantic\",\"query\":\"dark thriller\",\"limit\":5}"
```

---

## Database Queries

Run these after connecting with: `docker exec -it animetracker-db psql -U anime_user -d animetracker`

```sql
-- List all users
SELECT id, username, email, created_at FROM users;

-- Count total users
SELECT COUNT(*) FROM users;

-- Find a specific user by email
SELECT id, username, email, created_at FROM users WHERE email = 'test@example.com';

-- List all tables in the database
\dt

-- Show columns for a specific table
\d users
\d anime_embeddings

-- Show Flyway migration history
SELECT version, description, installed_on FROM flyway_schema_history;

-- List all anime list entries
SELECT * FROM anime_list_entries;

-- List entries for a specific user
SELECT ale.* FROM anime_list_entries ale
JOIN users u ON ale.user_id = u.id
WHERE u.username = 'testuser';

-- Check pgvector extension
SELECT * FROM pg_extension WHERE extname = 'vector';

-- Anime embeddings stats
SELECT COUNT(*) AS total_embedded FROM anime_embeddings;
SELECT anilist_id, title_romaji, genres, average_score FROM anime_embeddings LIMIT 10;

-- Check embedding dimensions (should be 1536)
SELECT anilist_id, title_romaji, vector_dims(embedding) AS dims FROM anime_embeddings LIMIT 5;

-- Delete a specific user (useful for re-testing registration)
DELETE FROM users WHERE email = 'test@example.com';

-- Clear all anime list entries (useful for re-testing)
DELETE FROM anime_list_entries;

-- Clear all embeddings (useful for re-populating)
DELETE FROM anime_embeddings;

-- Exit psql
\q
```

---

## Frontend Live Reload

The frontend uses Docker volume mounting (`./frontend/src:/app/src`) for hot-reload.
File changes in `frontend/src/` are picked up automatically - no rebuild needed.

```powershell
# Enable live reload (default - already enabled in docker-compose.yml)
# The WATCHPACK_POLLING env var + volume mount makes this work on Windows.
# Just edit files in frontend/src/ and save - the browser auto-refreshes.

# Disable live reload (use a full rebuild instead)
# Comment out the volumes section under frontend in docker-compose.yml:
#   volumes:
#     - ./frontend/src:/app/src
# Then rebuild:
docker-compose up --build frontend

# If hot-reload stops working, restart the frontend container:
docker-compose restart frontend
```

---

## Flyway Migrations

Database schema changes use Flyway migrations in `backend/src/main/resources/db/migration/`.

```powershell
# View applied migrations (connect to DB first)
docker exec -it animetracker-db psql -U anime_user -d animetracker -c "SELECT version, description, installed_on FROM flyway_schema_history;"

# Migrations run automatically on backend startup.
# To apply new migrations, restart the backend:
docker-compose restart backend

# If a migration fails and gets stuck, you may need to:
# 1. Fix the migration SQL
# 2. Delete the failed entry from flyway_schema_history
# 3. Restart backend
```

---

## Docker Cleanup

```powershell
# Remove stopped containers
docker-compose rm

# Remove all project images (forces full rebuild next time)
docker-compose down --rmi all

# Remove unused Docker resources system-wide
docker system prune

# Remove unused volumes (careful - deletes DB data!)
docker volume prune

# Nuclear option - remove everything and start fresh
docker-compose down -v --rmi all
docker-compose up --build
```

---

## Useful Docker Commands

```powershell
# Execute a command inside a running container
docker exec -it animetracker-backend bash
docker exec -it animetracker-frontend sh

# Copy a file from container to host
docker cp animetracker-backend:/app/logs/app.log ./app.log

# View container resource usage (CPU, memory)
docker stats

# Inspect a container's environment variables
docker inspect animetracker-backend | findstr -i "env"

# View container's network info
docker network inspect animetracker_animetracker-network
```

---

## Backend-Only Rebuild

```powershell
# Rebuild just the backend (faster than rebuilding everything)
docker-compose up --build --no-deps backend

# Rebuild backend + restart dependent services
docker-compose up --build backend

# View Spring Boot logs in real-time (SQL queries, errors, etc.)
docker-compose logs -f backend
```

---

## AniList API Testing

```powershell
# Search for anime (public endpoint, no auth needed)
curl.exe "http://localhost:8080/api/anime/search?q=naruto"

# Get anime by AniList ID
curl.exe http://localhost:8080/api/anime/1

# View blacklist
curl.exe http://localhost:8080/api/users/recommendations/blacklist -H "Authorization: Bearer $TOKEN"
```

---

## Recommendation Modes (AI-Powered)

```powershell
# Smart Search (semantic): seeds + text query
curl.exe -X POST http://localhost:8080/api/users/recommendations/semantic -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d '{"mode":"semantic","seedIds":[1535,21],"query":"dark psychological thriller with antiheroes","limit":15}'

# Note: Smart Search UI now auto-sends listWeight=0.20 for logged-in users.
# You can still override via API by passing listWeight explicitly.

# Smart Search text-only (anonymous allowed)
curl.exe -X POST http://localhost:8080/api/users/recommendations/semantic -H "Content-Type: application/json" -d '{"mode":"semantic","query":"dark psychological thriller","limit":15}'

# Similar Shows: requires 1-5 seeds
curl.exe -X POST http://localhost:8080/api/users/recommendations/semantic -H "Content-Type: application/json" -d '{"mode":"similar","seedIds":[1535,21],"limit":15}'

# Similar Shows + optional list personalization (logged in)
curl.exe -X POST http://localhost:8080/api/users/recommendations/semantic -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d '{"mode":"similar","seedIds":[1535,21],"listWeight":0.35,"limit":15}'

# For You (CF): login required
curl.exe -X POST http://localhost:8080/api/users/recommendations/semantic -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d '{"mode":"cf","limit":15}'

# Smart Search list-only mode (same behavior as 100% list influence)
curl.exe -X POST http://localhost:8080/api/users/recommendations/semantic -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d '{"mode":"semantic","useListOnly":true,"limit":15}'

# Response metadata now includes:
# - recommendationReason (one sentence)
# - fusionScore (0..1)
# - reasonCodes (signal provenance tags)
```

### Custom Embedding Import

```powershell
# Import from configured default path (requires auth)
curl.exe -X POST http://localhost:8080/api/users/recommendations/custom-embeddings/import -H "Authorization: Bearer $TOKEN"

# Import from explicit path inside backend container
curl.exe -X POST "http://localhost:8080/api/users/recommendations/custom-embeddings/import?path=/app/models/anime_embeddings.jsonl" -H "Authorization: Bearer $TOKEN"
```

---

## Recommendation Model Operations

### Prepare Kaggle Dataset for Notebooks

```powershell
# From project root
cd notebooks

# Normalize raw Kaggle CSVs to the canonical schema expected by notebooks 01/02/04
# Supports:
# - hernan4444/anime-recommendation-database-2020 (copy as-is)
# - marlesson/myanimelist-dataset-animes-profiles-reviews (adapt schema)
python prepare_dataset.py --source data/raw-kaggle --output data/kaggle
```

### Notebook Run Order (Training Pipeline)

```powershell
# Run in this order:
# 01_data_collection.ipynb
# 02_preprocessing.ipynb
# 03_semantic_training.ipynb
# 04_cf_training.ipynb
# 05_export.ipynb

# Open notebooks
cd notebooks
jupyter lab
```

### Semantic Preprocessing Smoke Check (Notebook 02 helper)

```powershell
# Quick check for the semantic preprocessing module used by notebook 02
python -m py_compile notebooks\semantic_preprocessing.py

# Minimal behavior smoke test
python -c "from notebooks.semantic_preprocessing import preprocess_review_text; s='Great character writing and pacing. Thanks for reading!'; print(preprocess_review_text(s, anime_title='Toradora'))"
```

### Phase 7 Semantic Multi-Positive Experiment

```powershell
# Run the new multi-positive + hard-neighbor semantic experiment
notebooks\.venv311\Scripts\python notebooks\semantic_multipos_experiment.py --epochs 3 --steps-per-epoch 400 --labels-per-batch 8 --examples-per-label 4

# Optional smaller smoke run
notebooks\.venv311\Scripts\python notebooks\semantic_multipos_experiment.py --epochs 1 --steps-per-epoch 100 --max-train-triplets 20000

# Optional eval snapshot retention tuning (default keeps 40 latest, prunes >30 days old)
notebooks\.venv311\Scripts\python notebooks\semantic_multipos_experiment.py --epochs 1 --steps-per-epoch 100 --eval-keep-latest 25 --eval-max-age-days 14

# Disable automatic eval snapshot pruning for this run
notebooks\.venv311\Scripts\python notebooks\semantic_multipos_experiment.py --epochs 1 --steps-per-epoch 100 --disable-eval-prune

# Compatibility alias (now forwards to semantic_multipos_experiment.py)
notebooks\.venv311\Scripts\python notebooks\semantic_mnrl_experiment.py --help
```

### Phase 6 CF Training Knobs (Notebook 04)

```powershell
# In notebook cell "Hyperparameters"
# WEAK_NEGATIVE_WEIGHT controls how hard unwatched entries are penalized.
# Lower = softer negatives (more discovery), higher = stricter negatives.

# In notebook cell where CFDataset(...) is created:
# dropout_range     -> denoising strength on observed interactions
# min_kept_items    -> keep at least N watched items after masking
# long_tail_alpha   -> reweight tail items in training loss
# max_pos_weight    -> cap for long-tail weight amplification
```

### Jupyter / Kernel Commands

```powershell
# Create a fresh notebook venv on Python 3.11 (recommended for torch + CUDA on Windows)
cd notebooks
py -3.11 -m venv .venv311
.\.venv311\Scripts\Activate.ps1

# Install notebook/training dependencies
python -m pip install --upgrade pip
pip install jupyter ipykernel pandas numpy scipy requests tqdm kaggle
pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu121
pip install sentence-transformers transformers datasets accelerate scikit-learn

# Register kernel for Jupyter/VS Code
python -m ipykernel install --user --name animetracker-py311 --display-name "Python 3.11 (animetracker)"

# List available kernels
jupyter kernelspec list

# Quick GPU sanity checks
nvidia-smi
python -c "import torch; print('torch=', torch.__version__, 'cuda=', torch.version.cuda, 'available=', torch.cuda.is_available())"
```

### Data Integrity Checks (Notebook Outputs)

```powershell
# Confirm expected outputs from 01/02 exist
Get-ChildItem .\notebooks\data

# Quick file sizes and line counts
Get-Item .\notebooks\data\ratings_filtered.csv
Get-Item .\notebooks\data\anilist_anime.jsonl
Get-Item .\notebooks\data\anilist_reviews.jsonl
(Get-Content .\notebooks\data\anilist_anime.jsonl | Measure-Object -Line).Lines
(Get-Content .\notebooks\data\anilist_reviews.jsonl | Measure-Object -Line).Lines

# Validate JSONL format and key coverage
python -c "import json, pathlib; p=pathlib.Path('notebooks/data/anilist_anime.jsonl'); c=0; [json.loads(l) for l in p.open(encoding='utf-8') if l.strip() and (c:=c+1)]; print('anilist_anime.jsonl lines=', c)"
python -c "import json, pathlib; p=pathlib.Path('notebooks/data/anilist_reviews.jsonl'); c=0; [json.loads(l) for l in p.open(encoding='utf-8') if l.strip() and (c:=c+1)]; print('anilist_reviews.jsonl lines=', c)"

# Validate CF index + matrix artifacts from 02
python -c "import json, scipy.sparse as s; from pathlib import Path; d=Path('notebooks/data'); idx=json.load((d/'cf_anime_index.json').open()); m=s.load_npz(d/'cf_ratings.npz'); print('cf_index=', len(idx), 'cf_shape=', m.shape, 'cf_nnz=', m.nnz)"
```

### Offline Baseline Evaluation (Phase 5)

```powershell
# Run offline CF + semantic baseline metrics on current exported models
# Metrics: Recall@10, HitRate@10, NDCG@10, Coverage@10, Long-tail share, Novelty
# Split policy: time-based if timestamp exists, stratified fallback otherwise
notebooks\.venv311\Scripts\python notebooks\evaluate_models.py --max-users 1000 --top-k 10 --relevance-threshold 7.0

# Faster smoke run while iterating on evaluator changes
notebooks\.venv311\Scripts\python notebooks\evaluate_models.py --max-users 100 --top-k 10 --relevance-threshold 7.0

# Also export CF popularity priors (for Phase 6 popularity attenuation)
notebooks\.venv311\Scripts\python notebooks\evaluate_models.py --max-users 1000 --top-k 10 --relevance-threshold 7.0 --write-cf-popularity-path ml-models/cf/item_popularity.json

# Tag a run with CF training hyperparameters (useful for long-tail A/B tracking)
notebooks\.venv311\Scripts\python notebooks\evaluate_models.py --max-users 1000 --top-k 10 --relevance-threshold 7.0 `
  --experiment-label "cf-lt-a0.45-w6.0-wn0.20" `
  --cf-train-long-tail-alpha 0.45 `
  --cf-train-max-pos-weight 6.0 `
  --cf-train-weak-negative-weight 0.20 `
  --cf-train-min-kept-items 3 `
  --cf-train-dropout-min 0.45 `
  --cf-train-dropout-max 0.85

# Optional eval snapshot retention tuning (default keeps 40 latest, prunes >30 days old)
notebooks\.venv311\Scripts\python notebooks\evaluate_models.py --max-users 1000 --top-k 10 --relevance-threshold 7.0 --eval-keep-latest 25 --eval-max-age-days 14

# Disable automatic eval snapshot pruning for this run
notebooks\.venv311\Scripts\python notebooks\evaluate_models.py --max-users 1000 --top-k 10 --relevance-threshold 7.0 --disable-eval-prune

# Output snapshots are written to:
# notebooks\eval\baseline_metrics_*.json
Get-ChildItem .\notebooks\eval\baseline_metrics_*.json | Sort-Object LastWriteTime -Descending | Select-Object -First 5
```

### Eval Leaderboard (A/B Ranking)

```powershell
# Rank CF snapshots by NDCG (default), showing tracked CF training metadata
python notebooks\eval_leaderboard.py --mode cf --top-n 10

# Compare against a chosen baseline and show deltas
python notebooks\eval_leaderboard.py --mode cf --top-n 10 --baseline notebooks\eval\baseline_metrics_20260219T223054Z.json

# Semantic leaderboard
python notebooks\eval_leaderboard.py --mode semantic --top-n 10

# Save leaderboard report JSON
python notebooks\eval_leaderboard.py --mode both --top-n 20 --write-report notebooks\eval\leaderboard_latest.json
```

### Phase 6/7 Promotion Gate

```powershell
# Compare latest CF + semantic experiment snapshots with promotion thresholds
notebooks\.venv311\Scripts\python notebooks\promotion_gate.py --eval-dir notebooks\eval

# Explicitly compare a chosen baseline/candidate pair
notebooks\.venv311\Scripts\python notebooks\promotion_gate.py `
  --cf-baseline notebooks\eval\baseline_metrics_20260219T223054Z.json `
  --cf-candidate notebooks\eval\baseline_metrics_20260219T223221Z.json

# Stricter semantic requirement (must beat baseline by at least +0.01)
notebooks\.venv311\Scripts\python notebooks\promotion_gate.py --semantic-min-delta 0.01

# Save machine-readable report and fail process on reject (useful in CI/automation)
notebooks\.venv311\Scripts\python notebooks\promotion_gate.py `
  --write-report notebooks\eval\promotion_gate_latest.json `
  --fail-on-reject
```

### CF Popularity Attenuation (Phase 6, Sidecar)

```powershell
# Current default (mild attenuation tuned from A/B eval)
CF_POPULARITY_PENALTY_ALPHA=0.15
CF_POPULARITY_PENALTY_SMOOTHING=1.0

# Disable attenuation explicitly
CF_POPULARITY_PENALTY_ALPHA=0.0
CF_POPULARITY_PENALTY_SMOOTHING=1.0

# Rebuild/restart sidecar after changing env or popularity file
docker-compose up --build -d ml-sidecar
docker-compose logs -f ml-sidecar
```

### Verify Exported Model Artifacts

```powershell
# Export target used by docker-compose mount: ./ml-models:/app/models
Get-ChildItem .\ml-models
Get-ChildItem .\ml-models\semantic
Get-ChildItem .\ml-models\cf
Get-Item .\ml-models\anime_embeddings.jsonl
```

### Sidecar Model Health Checks

```powershell
# Backend health
curl.exe http://localhost:8080/api/health

# Sidecar health (should report semantic_model=true and cf_model=true)
curl.exe http://localhost:5000/health

# Watch sidecar startup logs for model load errors
docker-compose logs -f ml-sidecar
```

### Sidecar Direct Model Smoke Tests

```powershell
# Embed endpoint (semantic model)
curl.exe -X POST http://localhost:5000/embed -H "Content-Type: application/json" -d "{\"text\":\"dark psychological thriller with mind games\"}"

# Rerank endpoint (semantic model) - minimal test payload
curl.exe -X POST http://localhost:5000/semantic/rerank -H "Content-Type: application/json" -d "{\"query_embedding\":[0.0,0.0,0.0],\"candidate_ids\":[1,2,3],\"candidate_scores\":[0.4,0.5,0.6],\"top_k\":2}"

# CF endpoint (requires model + index loaded)
curl.exe -X POST http://localhost:5000/cf/recommend -H "Content-Type: application/json" -d "{\"user_ratings\":{\"1\":8.0,\"20\":9.0},\"exclude_ids\":[1,20],\"top_k\":5}"
```

### Custom Embedding Auto-Sync (Startup)

```powershell
# Restart backend to trigger startup auto-sync check/import
docker-compose restart backend

# Watch backend logs for auto-sync messages
docker-compose logs -f backend
```

### Force Reimport / Resync

```powershell
# Force immediate import (manual endpoint)
curl.exe -X POST http://localhost:8080/api/users/recommendations/custom-embeddings/import -H "Authorization: Bearer $TOKEN"

# Optional: force next startup to treat file as "new" by clearing import-state row
# (run inside psql)
# DELETE FROM custom_embedding_import_state WHERE id = 1;
```

### Database Checks for Recommendation Models

Run these after connecting with: `docker exec -it animetracker-db psql -U anime_user -d animetracker`

```sql
-- Check OpenAI and custom embedding coverage
SELECT COUNT(*) AS openai_embeddings FROM anime_embeddings WHERE embedding IS NOT NULL;
SELECT COUNT(*) AS custom_embeddings FROM anime_embeddings WHERE embedding_custom IS NOT NULL;

-- Check vector dimensions
SELECT anilist_id, vector_dims(embedding) AS openai_dims
FROM anime_embeddings
WHERE embedding IS NOT NULL
LIMIT 5;

SELECT anilist_id, vector_dims(embedding_custom) AS custom_dims
FROM anime_embeddings
WHERE embedding_custom IS NOT NULL
LIMIT 5;

-- Check latest custom import-state fingerprint used by startup auto-sync
SELECT id, source_path, source_size_bytes, source_sha256, imported_at
FROM custom_embedding_import_state;
```

---

## Environment Setup

```powershell
# Required environment variables in .env:
# JWT_SECRET=<your-jwt-secret-at-least-32-chars>
# OPENAI_API_KEY=sk-<your-openai-api-key>
# ML_SIDECAR_ENABLED=true|false
# RECOMMENDATIONS_USE_CUSTOM_VECTORS=true|false
# CUSTOM_EMBEDDINGS_PATH=/app/models/anime_embeddings.jsonl
# AUTO_SYNC_CUSTOM_EMBEDDINGS=true|false
# FUSION_DYNAMIC_BLEND_ENABLED=true|false
# FUSION_DYNAMIC_BLEND_MIN_RATED_ANIME=10
# FUSION_DYNAMIC_BLEND_MAX_RATED_ANIME=80
# FUSION_DYNAMIC_BLEND_MIN_CF_WEIGHT=0.15
# FUSION_DYNAMIC_BLEND_MAX_CF_WEIGHT=0.55
# RECOMMENDATIONS_CF_CONTRIBUTORS_ENABLED=true|false
# SPRING_PROFILES_ACTIVE=dev   # optional: verbose SQL/logging in local development

# Important: booleans must be true/false exactly (no leading dash).

# The .env file is loaded automatically by docker-compose.
# Never commit .env to git (it's in .gitignore).
```

---

## Deployment Commands

*(To be added when deploying)*





