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

- `semantic`: text-query intent retrieval (+ optional list profile blend). `seedIds` are ignored in this mode for compatibility.
  Query text is normalized before embedding (lowercase, punctuation cleanup). Shorthand terms are preserved and expanded (for example `romcom -> romcom romance comedy`, `isekai -> isekai another world fantasy adventure`).
  Candidate generation is hybrid:
  - vector nearest-neighbor candidates from pgvector
  - lexical fallback/boost candidates from title + genres + description text matching
  - merged candidate scores are calibrated per query to reduce overconfident outliers
- `similar`: seed-centric similarity (+ optional list profile blend).
- `cf`: collaborative filtering only (requires logged-in user + sidecar).

### Semantic Preprocessing (Notebook 02)

Semantic training data is not raw reviews. `02_preprocessing.ipynb` now applies a preprocessing pass that:

- removes AniList/markdown noise from review text
- masks anime title mentions to a neutral token (`[TITLE]`)
- extracts higher-signal opinion sentences (story, characters, pacing, visuals, etc.)
- feeds that filtered text into both:
  - `corpus.jsonl` (MLM + embedding corpus)
  - `triplets.jsonl` (triplet fine-tuning data)

### Semantic Experiment Track (Phase 7)

- Baseline remains triplet fine-tuning from notebook `03`.
- Experimental path now uses multi-positive label training with hard-neighbor batches:
  - `notebooks/semantic_multipos_experiment.py`
- Rationale: this avoids assuming only one valid match per query, which better fits anime retrieval where multiple titles can be good answers.
- Legacy `notebooks/semantic_mnrl_experiment.py` is kept as a compatibility wrapper that forwards to the new script.

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

Performance note:
- Candidate retrieval overfetches for ranking quality, but metadata hydration from AniList is deferred to final top results to reduce first-request latency spikes.
- CF recommendations now load anime metadata from local `anime_embeddings` in batch first, then call AniList only for IDs missing locally.

Phase 8 additions:

- Dynamic blend policy can increase CF influence as a user has more rated anime.
- Optional CF contributor hint can enrich the one-sentence reason text (behind a flag).

### Defaults and Config

List influence defaults come from backend config:

- `RECOMMENDATIONS_DEFAULT_LIST_WEIGHT` (semantic default)
- `RECOMMENDATIONS_DEFAULT_SIMILAR_LIST_WEIGHT` (similar default)

Semantic retrieval config:

- `RECOMMENDATIONS_SEMANTIC_LEXICAL_ENABLED`
- `RECOMMENDATIONS_SEMANTIC_LEXICAL_CANDIDATE_LIMIT`
- `RECOMMENDATIONS_SEMANTIC_LEXICAL_MAX_PATTERNS`
- `RECOMMENDATIONS_SEMANTIC_LEXICAL_BOOST`
- `RECOMMENDATIONS_SEMANTIC_SCORE_CALIBRATION_ENABLED`
- `RECOMMENDATIONS_SEMANTIC_SCORE_CALIBRATION_TEMPERATURE`

Fusion config:

- `FUSION_SEMANTIC_WEIGHT`
- `FUSION_CF_WEIGHT`
- `FUSION_DIVERSITY_PENALTY`
- `FUSION_CF_CANDIDATE_MULTIPLIER`
- `FUSION_DYNAMIC_BLEND_ENABLED`
- `FUSION_DYNAMIC_BLEND_MIN_RATED_ANIME`
- `FUSION_DYNAMIC_BLEND_MAX_RATED_ANIME`
- `FUSION_DYNAMIC_BLEND_MIN_CF_WEIGHT`
- `FUSION_DYNAMIC_BLEND_MAX_CF_WEIGHT`

Explanation config:

- `RECOMMENDATIONS_CF_CONTRIBUTORS_ENABLED`

## Data and Infra Notes

- Database uses pgvector index for nearest-neighbor retrieval.
- Backend can auto-sync custom embedding JSONL into DB at startup.
- AniList calls include retry/pacing/cache safeguards.
- Sidecar calls are forced to HTTP/1.1 for stable request handling.

## Offline Evaluation Pipeline

- Script: `notebooks/evaluate_models.py`
- Input: `notebooks/data/ratings_filtered.csv` + exported `ml-models/` artifacts.
- Output: `notebooks/eval/baseline_metrics_*.json`
- Snapshot cleanup: auto-prunes old eval JSONs by retention policy (default keep 40 latest, prune files older than 30 days beyond that).
- Eval snapshots can include optional experiment metadata (label + CF training hyperparameters) for A/B traceability.
- Ranking helper: `notebooks/eval_leaderboard.py` to compare/rank snapshots and view deltas vs a selected baseline.
- Promotion gate script: `notebooks/promotion_gate.py`
  - compares baseline vs candidate CF snapshots with threshold checks
  - checks semantic experiment delta vs baseline
  - outputs explicit PASS/FAIL before model promotion

What it measures:

- `Recall@10`
- `HitRate@10`
- `NDCG@10`
- `Coverage@10`
- `Long-tail share`
- `Novelty`

Simple meaning of each metric:

- `Recall@10`: Of the anime the user actually liked in the test set, how many showed up in top 10 recommendations.
  Higher is better.
- `NDCG@10`: Like recall, but gives more credit when good recommendations are near the top of the list.
  Higher is better.
- `HitRate@10`: Percentage of users with at least one relevant hit in top 10.
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
- Relevant held-out items are defined by rating threshold (default `>= 7.0`).
- Evaluator can run CF popularity attenuation A/B via:
  - `--cf-popularity-alpha`
  - `--cf-popularity-smoothing`

## CF Phase 6 (In Progress)

- Sidecar CF ranking now supports optional popularity attenuation.
- Controlled by env vars:
  - `CF_POPULARITY_PENALTY_ALPHA` (`0.0` disables, default `0.15`)
  - `CF_POPULARITY_PENALTY_SMOOTHING`
- Popularity priors are loaded from:
  - `/app/models/cf/item_popularity.json`
- A/B eval snapshots on the same split seed/user sample:
  - `alpha=0.00`: recall `0.23716`, ndcg `0.596103`, coverage `0.120475`
  - `alpha=0.15`: recall `0.237763`, ndcg `0.596700`, coverage `0.127484`
  - `alpha=0.25`: recall `0.237192`, ndcg `0.596102`, coverage `0.133339`
- Decision: use `0.15` as default because it improves ranking quality and diversity together without a large hit-rate drop.
- Notebook 4 training now applies:
  - weak-negative weighting for unwatched entries in BCE watch loss
  - long-tail item reweighting for watched/rating reconstruction loss
  - stronger denoising masking on observed items with minimum kept-signal protection

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
