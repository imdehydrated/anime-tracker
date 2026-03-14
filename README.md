# AniRec

[Live App](https://d2twcwm8eoud49.cloudfront.net/)

AniRec is a full-stack anime recommendation and tracking platform. It combines intent-based search, seed-based similarity, and user-personalized recommendations with list import and cloud deployment.

## Core Capabilities

- Account system with personal anime list management
- List import from AniList and MyAnimeList
- Recommendation modes: `Smart Search`, `Similar Shows`, `For You`
- Search/detail pages with configurable result filters
- Home page popular strip sourced from the local popular-anime catalog
- Ongoing local catalog refresh so recommendations are not tied to live third-party API reliability

## Tech

- Frontend: React
- Backend API: Spring Boot (Java)
- ML serving sidecar: FastAPI (Python)
- Database: PostgreSQL
- Infra: AWS CloudFront, ALB, ECS/Fargate, RDS, ECR, Secrets Manager, CloudWatch
- CI/CD: GitHub Actions with AWS OIDC

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

4. Stop services:

```bash
docker-compose down
```

## Docs

- Architecture and internals: `ARCHITECTURE.md`
- Local commands and troubleshooting: `DEV-COMMANDS.md`
