# AniRec

AniRec is a full-stack anime tracking and recommendation app.
It combines a Spring Boot API, React frontend, PostgreSQL + pgvector, and a Python ML sidecar for semantic and collaborative recommendations.

## What It Does

- User auth with JWT (register/login)
- Personal anime list management (status, score, progress)
- AniList search + details
- AI recommendation modes:
  - Smart Search (semantic text query + optional taste blending when logged in)
  - Similar Shows (seed-based similarity)
  - For You (collaborative filtering)
- Recommendation explanations (single-sentence reason text)
- Recommendation blacklist support

## Architecture at a Glance

- `backend/` (Spring Boot): API, auth, recommendation orchestration
- `frontend/` (React): app UI and routes
- `db/` (PostgreSQL + pgvector): application data + vector retrieval
- `ml-sidecar/` (FastAPI): semantic model serving + CF inference
- `notebooks/`: model data prep, training, evaluation, and export

For deeper design details, see `ARCHITECTURE.md`.

## Core Recommendation Flow

- Semantic and Similar modes retrieve vector candidates and can blend with user taste profile.
- CF mode predicts unwatched anime using a trained autoencoder.
- CF ranking uses a mild popularity attenuation by default (tunable via env vars).
- Fusion can dynamically shift semantic-vs-CF weight based on how rich a user profile is.
- Fusion scoring combines available signals into a normalized rank score.
- Frontend renders recommendation reason text from backend metadata.

## Run Locally

1. Create `.env` with at least:

```bash
JWT_SECRET=your-secret
OPENAI_API_KEY=sk-...
ML_SIDECAR_ENABLED=true
RECOMMENDATIONS_USE_CUSTOM_VECTORS=true
```

2. Start services:

```bash
docker-compose up --build
```

3. Access:

- Frontend: `http://localhost:3000`
- Backend: `http://localhost:8080`
- Sidecar health: `http://localhost:5000/health`

## Model Training and Ops

This README is intentionally overview-only.
Use these docs for details:

- `DEV-COMMANDS.md` for setup, notebook run order, eval commands, and troubleshooting
- `ARCHITECTURE.md` for design and scoring behavior
- `EXECUTION-PLAN.md` for phase progress and handoff workflow
- Eval scripts auto-prune old snapshot JSONs by retention defaults (tunable flags in `DEV-COMMANDS.md`)
- Eval A/B comparison tooling includes `notebooks/eval_leaderboard.py` for ranking snapshot runs
- Promotion gating for model updates uses `notebooks/promotion_gate.py` (commands in `DEV-COMMANDS.md`)

## Project Structure

```text
animetracker/
|-- backend/
|-- frontend/
|-- ml-sidecar/
|-- notebooks/
|-- ml-models/
|-- ARCHITECTURE.md
|-- DEV-COMMANDS.md
|-- EXECUTION-PLAN.md
`-- docker-compose.yml
```
