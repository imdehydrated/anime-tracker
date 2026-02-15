# AniRec

Full-stack anime list and recommendation application built with Spring Boot, React, and PostgreSQL. Track your anime, rate what you've watched, and get AI-powered personalized recommendations using OpenAI embeddings and pgvector similarity search.

## Tech Stack

- **Backend**: Spring Boot 3.5 (Java 17)
- **Frontend**: React 18 with React Router v6
- **Database**: PostgreSQL 15 with pgvector extension
- **Migrations**: Flyway (V1–V10)
- **Containerization**: Docker & Docker Compose
- **External APIs**: AniList GraphQL, OpenAI Embeddings API
- **AI/ML**: OpenAI `text-embedding-3-small` (1536-dim vectors), pgvector cosine similarity

## Features

### Anime List Management
- MAL-style table layout with sortable, filterable columns
- Status tracking (Watching, Completed, Plan to Watch, On Hold, Dropped)
- Score rating (1-10) and episode progress tracking with auto-capping
- Auto-fill progress when marking as completed
- Inline editing — changes save immediately

### Search & Discovery
- Live search with debounced autocomplete (fires after 3+ characters)
- Anime detail pages with synopsis, genres, episodes, and AniList links
- Add to list directly from search results, detail pages, or recommendations

### AI-Powered Recommendations
- **Smart Recs** — Pick seed anime, describe what you're looking for in natural language, or both
- **For You** — Automatic recommendations based on your rated anime, no input needed
- OpenAI embeddings encode anime metadata (title, genres, tags, description) into vectors
- pgvector cosine similarity finds the closest matches from a local database of embedded anime
- Configurable list influence slider (0-100%) lets you control how much your ratings shape Smart Rec results
- Blacklist management to hide unwanted recommendations from both pages
- Works without login — anonymous users can use Smart Recs with seeds and text queries
- Inspired by [Sprout](https://github.com/ameobea/sprout) (collaborative filtering) and [Yuno](https://github.com/IAmPara0x/yuno) (semantic search)

### User System
- Registration with BCrypt password hashing
- JWT authentication with protected routes
- Auto-logout on JWT expiry (token decoded client-side, 401 interceptor)
- Per-user scoped data — users can only access their own list

## Recommendation Algorithm

All recommendations run through a single semantic engine powered by OpenAI embeddings and pgvector:

1. **Input** — Pick 1-5 seed anime, describe what you want in natural language, or both. The "For You" page uses list-only mode (no seeds or query needed).
2. **Vector blending** — Seed anime embeddings are averaged into a centroid, then blended with the query embedding at 50/50. A user preference vector (built from scored anime, weighted by `score - 6.5`) can be blended in at a configurable weight (0-100%, default 20%).
3. **Cosine similarity** — pgvector searches the `anime_embeddings` table using an IVFFlat index for fast nearest-neighbor lookup.
4. **Results** — Top 15 similar anime returned, excluding user's list, blacklist, and seeds. Works without login (anonymous users see results without list exclusions).

## API Endpoints

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| POST | `/api/users/register` | No | Register a new account |
| POST | `/api/users/login` | No | Login and receive JWT token |
| GET | `/api/users/list` | Yes | Get your anime list |
| POST | `/api/users/list` | Yes | Add anime to your list |
| PUT | `/api/users/list/{id}` | Yes | Update an entry (status, score, episodes) |
| DELETE | `/api/users/list/{id}` | Yes | Remove an entry |
| GET | `/api/anime/search?q=` | No | Search anime by title |
| GET | `/api/anime/{id}` | No | Get anime details by AniList ID |
| POST | `/api/users/recommendations/semantic` | No | Get AI recommendations (seeds, query, list-only, or combination) |
| POST | `/api/users/recommendations/blacklist` | Yes | Hide anime from recommendations |
| GET | `/api/users/recommendations/blacklist` | Yes | View blacklisted anime |
| DELETE | `/api/users/recommendations/blacklist/{id}` | Yes | Remove from blacklist |
| POST | `/api/admin/embeddings/populate` | Yes | Populate anime embeddings database *(planned)* |
| GET | `/api/health` | No | Health check |

## Quick Start

```bash
# Set your OpenAI API key in .env
OPENAI_API_KEY=sk-...

# Start all services (database, backend, frontend)
docker-compose up --build

# Frontend: http://localhost:3000
# Backend API: http://localhost:8080
```

## Project Structure

```
animetracker/
├── backend/                    # Spring Boot backend
│   └── src/main/java/com/animetracker/
│       ├── config/             # Security, JWT, CORS, filters
│       ├── controller/         # REST controllers
│       ├── entity/             # JPA entities (User, AnimeListEntry, RecommendationBlacklist)
│       ├── repository/         # Data access layer
│       ├── dto/                # AniList API response DTOs
│       └── service/            # Business logic (list, recommendations, AniList, embeddings)
│   └── src/main/resources/
│       ├── application.yml     # Spring Boot config
│       └── db/migration/       # Flyway migrations (V1–V10)
├── frontend/                   # React frontend
│   └── src/
│       ├── context/            # Auth context (JWT state)
│       ├── hooks/              # Shared hooks (useAuthHeader, useAddToList)
│       ├── components/         # Reusable components (NavBar)
│       └── pages/              # Page components (MyList, Search, Recommendations, etc.)
├── docker-compose.yml          # Docker orchestration (pgvector DB + backend + frontend)
├── .env                        # Environment variables (JWT_SECRET, OPENAI_API_KEY)
└── DEV-COMMANDS.md             # Developer command reference
```

## Author

Built as a portfolio project to learn full-stack development, API integration, and AI-powered recommendation systems.
