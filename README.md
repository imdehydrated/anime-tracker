# AniRec

[Live App](https://d2twcwm8eoud49.cloudfront.net/)

AniRec is a full-stack anime recommendation and tracking platform. It combines intent-based search, seed-based similarity, and user-personalized recommendations with list import and cloud deployment.

## Core Capabilities

- Account system with personal anime list management
- List import from AniList and MyAnimeList
- Recommendation modes: `Smart Search`, `Similar Shows`, `For You`
- Search/detail pages with configurable result filters and stat-rich anime detail layouts
- Dashboard-style My List view with inline edits, quick filters, and visible summary stats
- Home page popular strip sourced from the local popular-anime catalog
- Native mobile app with Home, Search, Smart Rec, anime detail, and My List flows backed by the same API
- Ongoing local catalog refresh so recommendations are not tied to live third-party API reliability

## Tech

- Frontend: React
- Mobile app: Expo / React Native
- Backend API: Spring Boot (Java)
- ML serving sidecar: FastAPI (Python)
- Database: PostgreSQL
- Infra: AWS CloudFront, ALB, ECS/Fargate, RDS, ECR, Secrets Manager, CloudWatch
- CI/CD: GitHub Actions with AWS OIDC, Docker Buildx, and GitHub-hosted layer caching

## Recommendation System

- `Smart Search` blends semantic retrieval with lexical retrieval and ranking controls.
- `Similar Shows` uses seed-item similarity for fast related-title discovery.
- `For You` uses user history plus thumbs feedback to personalize ranking.
- Explanations are returned with recommendations so results are easier to interpret.
- Metadata sync lanes continuously backfill sparse titles and roll through full-catalog refresh windows.

## Deployment

- Public traffic enters through CloudFront over HTTPS
- ALB routes `/api/*` to API service and all other routes to web service
- Backend and ML sidecar run together in ECS task topology
- Frontend runs as separate ECS service
- Runtime secrets come from AWS Secrets Manager
- Monitoring and alarms are configured through CloudWatch
- CI/CD deploys container updates through GitHub Actions workflows

## Repository Structure

- `frontend/`: React client
- `mobile/`: Expo / React Native app
- `backend/`: Spring Boot API and business logic
- `ml-sidecar/`: Python model serving service
- `db/`: local database setup and migration support
- `infra/`: AWS task definitions and workflow infrastructure
- `notebooks/`: model/data workflow scripts and experiments
- `scripts/`: deployment and operational helper scripts

## Run Locally

1. Create `.env` with required values.
2. Start services:

```bash
docker-compose up --build
```

3. Open:

- Frontend: `http://localhost:3000`
- Backend health: `http://localhost:8080/api/health`

## Mobile App

- The native app lives in `mobile/` and uses the same backend/search/recommendation/list APIs as the web app.
- Current mobile flows include Home, Search, Smart Rec (`Smart Search`, `Similar Shows`, `For You`), anime detail, login/register, and My List with import/edit support.
- Mobile content screens support pull-to-refresh, shared list membership state, and the same default newest-first My List sorting used on web.
- Adult content remains filtered by default on mobile and requires explicit local opt-in before `18+ Content` can be enabled.

### Run Mobile Locally

1. Make sure the backend is running.
2. In a separate terminal:

```bash
cd mobile
npm install
npx expo start --dev-client
```

3. Type-check the mobile app when changing shared/backend behavior:

```bash
cd mobile
./node_modules/.bin/tsc --noEmit
```

4. Stop services:

```bash
docker-compose down
```

## Docs

- Architecture and internals: `ARCHITECTURE.md`
- Local commands and troubleshooting: `DEV-COMMANDS.md`
