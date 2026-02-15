# Architecture Guide

## Overview

AniRec is split into two applications:

- `backend/` (Spring Boot): authentication, list management, recommendation APIs, and external API integrations.
- `frontend/` (React): route-driven UI that consumes backend APIs through centralized API modules.

## Backend Structure

- `config/`: security and JWT plumbing.
- `controller/`: HTTP layer only. Validate input, delegate to services, return DTOs.
- `service/`: business logic and orchestration.
- `repository/`: database access.
- `entity/`: persistence models.
- `dto/`: request/response transport models.
- `exception/`: centralized API exception model and global exception handling.

### Backend Design Rules

1. Controllers do not contain business logic.
2. Services throw domain exceptions (`BadRequestException`, `NotFoundException`, etc.).
3. `GlobalExceptionHandler` converts exceptions to a consistent API error payload.
4. External API calls are wrapped in dedicated services with timeout handling.
5. Request payloads should use typed DTOs, not untyped maps.

## Frontend Structure

- `api/`: all HTTP calls live here (single source of backend contract truth).
- `components/`: reusable UI building blocks.
- `context/`: global auth state and unauthorized handling.
- `hooks/`: reusable stateful behavior (search, add-to-list, blacklist workflows).
- `pages/`: route-level UI composition.

### Frontend Design Rules

1. Pages call `api/*` functions, not raw Axios directly.
2. Auth routing is enforced with `RequireAuth`.
3. Shared workflows (like recommendation blacklist) belong in hooks/components.
4. Internal navigation should use `Link` or `Navigate`, never full-page `<a href>` for app routes.
5. Prefer optimistic updates for list actions with rollback on failure.

## Recommendation Flow

1. Frontend calls `POST /api/users/recommendations/semantic` with seeds/query/list options.
2. Backend builds a vector from:
   - seed anime embeddings,
   - optional query embedding,
   - optional user preference vector.
3. Backend executes pgvector cosine similarity search.
4. Backend excludes anime already in user list, seed set, or blacklist.

## Change Checklist

When adding features, update all relevant layers:

1. DTOs + validation annotations.
2. Controller endpoint and service behavior.
3. API module in `frontend/src/api`.
4. Hook/component/page usage.
5. README/API docs and developer commands.
6. Tests for critical paths.
