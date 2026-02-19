# Architecture Guide

## Overview

AniRec has 4 running services:

- `backend/` (Spring Boot): auth, list CRUD, recommendation APIs, AniList + OpenAI integration.
- `frontend/` (React): route-based UI that calls backend APIs.
- `db/` (PostgreSQL + pgvector): app data + vector search.
- `ml-sidecar/` (FastAPI): custom semantic embedding/rerank + CF predictions.

## Backend Layout

- `config/`: JWT and security config.
- `controller/`: request/response layer only.
- `service/`: recommendation logic, orchestration, external calls.
- `repository/`: JPA + native SQL queries (including pgvector search).
- `entity/`: database models.
- `dto/`: request/response models.
- `exception/`: domain exceptions + global API error handler.

### Backend Rules

1. Keep controllers thin.
2. Put business logic in services.
3. Throw domain exceptions from services (`BadRequestException`, `NotFoundException`, etc.).
4. Use typed DTOs (not untyped maps) for request bodies.
5. Keep external API logic inside dedicated service classes.

## Frontend Layout

- `src/api/`: all backend HTTP calls.
- `src/pages/`: route pages.
- `src/components/`: reusable UI blocks.
- `src/hooks/`: reusable state/workflows.
- `src/context/`: auth state + unauthorized handling.

### Frontend Rules

1. Pages should call `src/api/*`, not raw Axios.
2. Use shared hooks/components for repeated workflows.
3. Keep route protection in auth wrappers (like `RequireAuth`).
4. Use router navigation (`Link`, `Navigate`) for app routes.

## Recommendation Architecture

### Endpoints

- Legacy endpoint:
  - `POST /api/users/recommendations/semantic`
  - Returns `List<AnimeInfo>` (for compatibility).
- Scored endpoint:
  - `POST /api/users/recommendations/semantic/scored`
  - Returns `List<RecommendationResponse>` (`anime`, `fusionScore`, `reasonCodes`).

Frontend currently calls scored first, then falls back to legacy on `404`.

### Modes

All modes use the same request DTO (`SemanticRequest`) with `mode`:

- `semantic`: seeds + optional text query (+ optional list profile blend).
- `similar`: seed-centric similarity (+ optional list profile blend).
- `cf`: collaborative filtering only (requires logged-in user + sidecar).

### Vector Sources

- `RECOMMENDATIONS_USE_CUSTOM_VECTORS=false`: use OpenAI vector column (`embedding`, 1536-dim).
- `RECOMMENDATIONS_USE_CUSTOM_VECTORS=true`: use custom vector column (`embedding_custom`, 384-dim).

### Scoring + Fusion

`SemanticRecommendationService` builds candidate lists and uses `FusionScoringService` to:

1. Normalize score types into `[0, 1]`.
2. Merge semantic and CF overlap candidates.
3. Blend weights (global config + optional per-request override path).
4. Optionally apply diversity penalty pass.
5. Attach explanation metadata:
   - `fusionScore`
   - `reasonCodes`
   - one-sentence `recommendationReason`

### Defaults and Config

List influence defaults come from backend config:

- `RECOMMENDATIONS_DEFAULT_LIST_WEIGHT` (semantic default)
- `RECOMMENDATIONS_DEFAULT_SIMILAR_LIST_WEIGHT` (similar default)

Fusion config:

- `FUSION_SEMANTIC_WEIGHT`
- `FUSION_CF_WEIGHT`
- `FUSION_DIVERSITY_PENALTY`
- `FUSION_CF_CANDIDATE_MULTIPLIER`

## Data and Infra Notes

- Database uses pgvector index for nearest-neighbor retrieval.
- Backend can auto-sync custom embedding JSONL into DB at startup.
- AniList calls include retry/pacing/cache safeguards.
- Sidecar calls are forced to HTTP/1.1 for stable request handling.

## Offline Evaluation Pipeline

- Script: `notebooks/evaluate_models.py`
- Input: `notebooks/data/ratings_filtered.csv` + exported `ml-models/` artifacts.
- Output: `notebooks/eval/baseline_metrics_*.json`

What it measures:

- `Recall@10`
- `NDCG@10`
- `Coverage@10`
- `Long-tail share`
- `Novelty`

Simple meaning of each metric:

- `Recall@10`: Of the anime the user actually liked in the test set, how many showed up in top 10 recommendations.
  Higher is better.
- `NDCG@10`: Like recall, but gives more credit when good recommendations are near the top of the list.
  Higher is better.
- `Coverage@10`: How much of the full catalog the model recommends across all users.
  Higher means less “same few shows for everyone.”
- `Long-tail share`: How often recommendations come from less popular anime.
  Higher means more niche discovery; too low can mean over-popular recommendations.
- `Novelty`: How surprising/less-popular recommended items are on average.
  Higher means more novel picks, but very high can reduce mainstream relevance.

How to read them together:

- Strong quality usually means good `Recall@10` and `NDCG@10`.
- Healthy diversity usually means non-trivial `Coverage@10`, `Long-tail share`, and `Novelty`.
- No single metric is enough; track all of them before and after model changes.

Split behavior:

- Uses time split if a timestamp column exists in ratings.
- Falls back to stratified per-user split when no timestamp column exists.

## Change Checklist

When changing architecture-sensitive features:

1. Update DTOs and validation.
2. Update controller + service contracts.
3. Update frontend API module usage.
4. Update tests for changed behavior.
5. Update docs:
   - `README.md`
   - `DEV-COMMANDS.md`
   - `ARCHITECTURE.md`
   - `MILESTONES.md`
