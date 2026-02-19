# AniRec

Full-stack anime list and recommendation application built with Spring Boot, React, and PostgreSQL. Track your anime, rate what you watch, and get AI-powered recommendations using a custom fine-tuned sentence transformer, collaborative filtering, and pgvector similarity search.

## Tech Stack

- Backend: Spring Boot 3.5 (Java 17)
- Frontend: React 18 + React Router v6
- Database: PostgreSQL 15 + pgvector
- ML Sidecar: FastAPI (Python) - custom semantic model + CF autoencoder
- Migrations: Flyway (V1-V12)
- Containerization: Docker + Docker Compose (4 services)
- External APIs: AniList GraphQL, OpenAI Embeddings API
- Training: Kaggle/Colab notebooks (PyTorch + sentence-transformers)

## Features

### Anime List Management

- MAL-style table layout with filtering and sorting
- Status tracking: `WATCHING`, `COMPLETED`, `PLAN_TO_WATCH`, `ON_HOLD`, `DROPPED`
- Score rating (1-10) and episode progress tracking
- Auto-fill progress when marking a show as completed
- Optimistic UI updates for list edits/deletes

### Search and Discovery

- Live search with debounced queries
- Anime detail pages with synopsis, genres, episode count, and AniList link
- Add-to-list actions from search, detail, and recommendation views
- AniList client resilience: request pacing, retry/backoff on 429/5xx, and in-memory caching for search + anime-by-id lookups

### AI Recommendations

- **Smart Search**: seed anime + optional natural-language query
- Logged-in Smart Search auto-blends your list profile using backend global defaults
- **Similar Shows**: pick 1-5 example anime and get similar titles
- **For You (CF)**: collaborative filtering predictions from rating patterns
- Similar Shows supports optional list personalization
- One-sentence recommendation explanations are attached to recommendation results
- Recommendation blacklist shared by all recommendation modes
- Anonymous recommendation support for non-login modes

### Auth and Security

- Registration with BCrypt password hashing
- JWT authentication for protected endpoints
- Protected frontend routes for `My List` and `For You`
- Centralized API unauthorized handling (auto logout on 401)

## Recommendation Algorithm

Three backend modes are exposed through one endpoint (`/api/users/recommendations/semantic`) using the `mode` field.

### Semantic Mode (`mode=semantic`)

1. Build a search vector from seed anime and/or query text.
2. Blend with a user preference vector from scored list entries when `listWeight > 0`.
   If request `listWeight` is omitted, backend uses `RECOMMENDATIONS_DEFAULT_LIST_WEIGHT` (default `0.20`).
3. Run pgvector similarity search and filter out list/blacklist/seed items.

### Similar Mode (`mode=similar`)

1. Require 1-5 seed anime.
2. Average seed vectors into a centroid.
3. Optionally blend centroid with the user's list profile (request `listWeight` override or backend default `RECOMMENDATIONS_DEFAULT_SIMILAR_LIST_WEIGHT`, default `0.00`).
4. Query pgvector, overfetch, and rerank with sidecar when available.

### CF Mode (`mode=cf`)

1. Send user ratings to sidecar collaborative filtering model.
2. Return top predictions for unwatched anime.

### Fusion Scoring (Phase 1 Infrastructure)

- Added `FusionScoringService` for normalized score conversion and deterministic candidate fusion.
- Added `RecommendationResponse` DTO to support optional `fusionScore` and `reasonCodes`.
- Added fusion config under `recommendations.fusion.*` with startup validation:
  - weights are clamped to `>= 0`, renormalized to sum to `1`
  - zero/invalid weights fallback to semantic-only (`1.0 / 0.0`)
  - diversity penalty is clamped to `[0, 1]`
- This phase is infrastructure-only: existing recommendation endpoint behavior is unchanged.

### Fusion Scoring (Phase 2 Wiring)

- Semantic and Similar flows now create normalized semantic candidates and blend with overlapping CF signals for logged-in users when sidecar is enabled.
- CF mode now emits normalized fusion score metadata per recommendation.
- Recommendation metadata fields are attached to result items:
  - `fusionScore` (0..1)
  - `reasonCodes` (provenance tags)
  - `recommendationReason` (single sentence)

### Fusion Scoring (Phase 3 Compatibility Rollout)

- Legacy endpoint remains available:
  - `POST /api/users/recommendations/semantic` returns `List<AnimeInfo>`.
- Scored endpoint is now available for migration:
  - `POST /api/users/recommendations/semantic/scored` returns `List<RecommendationResponse>`.
- Frontend currently prefers scored endpoint and falls back to legacy endpoint if unavailable.

### Vector Source Switch

- `RECOMMENDATIONS_USE_CUSTOM_VECTORS=false` (default): query `embedding` (OpenAI, 1536-dim). Missing vectors can be backfilled on demand.
- `RECOMMENDATIONS_USE_CUSTOM_VECTORS=true`: query `embedding_custom` (custom, 384-dim). Query text embedding requires sidecar.

### Metadata Backfill for Recommendation Cards

- Custom embedding imports can include optional metadata fields (`title_english`, `cover_image`, `genres`, `description`, `average_score`, `status`, `episodes`).
- If a recommended anime row still has missing metadata, backend now fetches details from AniList at runtime, returns complete card data, and persists the missing metadata to `anime_embeddings`.

## API Endpoints

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| POST | `/api/users/register` | No | Register account |
| POST | `/api/users/login` | No | Login and receive JWT |
| GET | `/api/users/list` | Yes | Get current user's list |
| POST | `/api/users/list` | Yes | Add anime to list |
| PUT | `/api/users/list/{id}` | Yes | Update list entry |
| DELETE | `/api/users/list/{id}` | Yes | Delete list entry |
| GET | `/api/anime/search?q=` | No | Search AniList anime |
| GET | `/api/anime/{id}` | No | Get AniList anime details |
| POST | `/api/users/recommendations/semantic` | No | Legacy recommendations (`semantic`, `similar`, `cf`) returning `List<AnimeInfo>` |
| POST | `/api/users/recommendations/semantic/scored` | No | Scored recommendations returning `List<RecommendationResponse>` |
| POST | `/api/users/recommendations/blacklist` | Yes | Add blacklist entry |
| GET | `/api/users/recommendations/blacklist` | Yes | List blacklist entries |
| DELETE | `/api/users/recommendations/blacklist/{id}` | Yes | Remove blacklist entry |
| POST | `/api/users/recommendations/custom-embeddings/import` | Yes | Import `embedding_custom` vectors from JSONL |
| GET | `/api/health` | No | Health check |

## Quick Start

```bash
# .env
JWT_SECRET=your-jwt-secret-at-least-32-chars
OPENAI_API_KEY=sk-...
ML_SIDECAR_ENABLED=true
RECOMMENDATIONS_USE_CUSTOM_VECTORS=true
CUSTOM_EMBEDDINGS_PATH=/app/models/anime_embeddings.jsonl
AUTO_SYNC_CUSTOM_EMBEDDINGS=true
RECOMMENDATIONS_DEFAULT_LIST_WEIGHT=0.20
RECOMMENDATIONS_DEFAULT_SIMILAR_LIST_WEIGHT=0.00
FUSION_SEMANTIC_WEIGHT=0.6
FUSION_CF_WEIGHT=0.4
FUSION_DIVERSITY_PENALTY=0.10
FUSION_CF_CANDIDATE_MULTIPLIER=2

# Start all services (db, backend, frontend, ml-sidecar)
docker-compose up --build

# Optional: verbose backend logs
# SPRING_PROFILES_ACTIVE=dev
```

Use literal booleans only: `true` or `false` (not `-false`).

If custom vectors are enabled, startup now auto-syncs `anime_embeddings.jsonl` into `embedding_custom` when the file changes.
Manual reimport is still available:

```bash
curl -X POST http://localhost:8080/api/users/recommendations/custom-embeddings/import \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

- Frontend: `http://localhost:3000`
- Backend: `http://localhost:8080`
- ML Sidecar: `http://localhost:5000/health`

## Train and Run Custom Models

This project supports two runtime modes:
- OpenAI vector mode (no local model training required)
- Custom model mode (train semantic + CF models in `notebooks/`, then serve via `ml-sidecar`)

### 1. Prepare notebook environment

```bash
cd notebooks

# Optional but recommended
python -m venv .venv311
# Windows:
.venv311\Scripts\activate
# macOS/Linux:
# source .venv311/bin/activate

pip install jupyter
```

### 2. Prepare Kaggle dataset files

Place raw Kaggle CSVs under `notebooks/data/raw-kaggle/`, then run:

```bash
cd notebooks
python prepare_dataset.py --source data/raw-kaggle --output data/kaggle
```

Supported sources:
- `hernan4444/anime-recommendation-database-2020` (copied directly)
- `marlesson/myanimelist-dataset-animes-profiles-reviews` (mapped to canonical schema)

### 3. Run notebooks in order

Run all cells in this sequence:

1. `01_data_collection.ipynb`
2. `02_preprocessing.ipynb`
3. `03_semantic_training.ipynb`
4. `04_cf_training.ipynb`
5. `05_export.ipynb`

Expected key outputs:
- `notebooks/data/corpus.jsonl`
- `notebooks/data/triplets.jsonl`
- `notebooks/data/cf_ratings.npz`
- `notebooks/models/anime_semantic/`
- `notebooks/models/anime_cf/best_model.pt`
- `ml-models/semantic/`
- `ml-models/cf/model.pt`
- `ml-models/anime_embeddings.jsonl`

`05_export.ipynb` writes deployable artifacts to project-root `ml-models/` when run locally from `notebooks/`.

### 4. Run offline baseline evaluation (recommended before model changes)

Run this after `05_export.ipynb` so evaluation uses the same `ml-models/` artifacts served by the app:

```bash
python notebooks/evaluate_models.py --max-users 1000 --top-k 10
```

The evaluator computes:
- `Recall@10`
- `NDCG@10`
- `Coverage@10`
- `Long-tail share`
- `Novelty`

It uses a time split if ratings include timestamps, otherwise it automatically falls back to a stratified per-user split.
Baseline snapshots are saved to `notebooks/eval/baseline_metrics_*.json`.

### 5. Run the app with custom models

Set `.env` values:

```bash
ML_SIDECAR_ENABLED=true
RECOMMENDATIONS_USE_CUSTOM_VECTORS=true
CUSTOM_EMBEDDINGS_PATH=/app/models/anime_embeddings.jsonl
AUTO_SYNC_CUSTOM_EMBEDDINGS=true
RECOMMENDATIONS_DEFAULT_LIST_WEIGHT=0.20
RECOMMENDATIONS_DEFAULT_SIMILAR_LIST_WEIGHT=0.00
FUSION_SEMANTIC_WEIGHT=0.6
FUSION_CF_WEIGHT=0.4
FUSION_DIVERSITY_PENALTY=0.10
FUSION_CF_CANDIDATE_MULTIPLIER=2
```

Then start services:

```bash
docker-compose up --build
```

### 6. Verify models are loaded

```bash
# Backend
curl http://localhost:8080/api/health

# Sidecar (should report semantic_model=true and cf_model=true)
curl http://localhost:5000/health
```

If `AUTO_SYNC_CUSTOM_EMBEDDINGS=true`, backend imports `ml-models/anime_embeddings.jsonl` into `embedding_custom` on startup when file contents change.
Manual reimport remains available:

```bash
curl -X POST http://localhost:8080/api/users/recommendations/custom-embeddings/import \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

### 7. Run without custom models (OpenAI-only fallback)

Set:

```bash
ML_SIDECAR_ENABLED=false
RECOMMENDATIONS_USE_CUSTOM_VECTORS=false
```

In this mode, backend uses OpenAI embeddings (`embedding` column) and sidecar is optional.

## Project Structure

```text
animetracker/
|-- backend/
|   |-- src/main/java/com/animetracker/
|   |   |-- config/          # Security and JWT
|   |   |-- controller/      # HTTP layer
|   |   |-- dto/             # Request/response DTOs
|   |   |-- entity/          # JPA entities
|   |   |-- exception/       # Domain exceptions + global handler
|   |   |-- repository/      # Data access
|   |   `-- service/         # Business logic + external API adapters
|   |-- src/main/resources/
|   |   |-- application.yml
|   |   |-- application-dev.yml
|   |   `-- db/migration/
|   `-- src/test/java/       # Backend unit tests
|-- frontend/
|   `-- src/
|       |-- api/             # Centralized HTTP client + API modules
|       |-- components/      # Reusable UI components
|       |-- context/         # Auth context
|       |-- hooks/           # Shared stateful logic
|       `-- pages/           # Route-level pages
|-- ml-sidecar/
|   |-- Dockerfile
|   |-- requirements.txt
|   `-- app/                # FastAPI semantic + CF model serving
|-- notebooks/              # Kaggle/Colab training pipeline (01-05)
|-- ml-models/              # Trained model checkpoints (Docker volume)
|-- ARCHITECTURE.md
|-- DEV-COMMANDS.md
`-- docker-compose.yml
```

## Developer Notes

- Architecture conventions are documented in `ARCHITECTURE.md`.
- Backend tests live in `backend/src/test/java` (run with `mvn test`).
- AniList rate-limit mitigation lives in `backend/src/main/java/com/animetracker/service/AniListService.java`:
  - Global request spacing (~2.8 req/sec per backend instance)
  - Retry with jitter for `429` and `5xx`
  - Cached search results (10 min TTL) and anime-by-id lookups (30 min TTL)
