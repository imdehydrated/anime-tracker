# AniRec

Full-stack anime list and recommendation application built with Spring Boot, React, and PostgreSQL. Track your anime, rate what you've watched, and get personalized recommendations — from simple genre-based suggestions to AI-powered semantic search.

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

### Genre-Based Recommendations
- Genre-weighted scoring algorithm based on your list and ratings
- Parallel AniList API queries for fast results
- Blacklist management to hide unwanted recommendations
- Refresh on demand

### AI-Powered Semantic Recommendations (In Progress)
- Pick seed anime + describe what you're looking for in natural language
- OpenAI embeddings encode anime metadata (title, genres, tags, description) into vectors
- pgvector cosine similarity finds the closest matches from a local database of embedded anime
- Blends seed-based and query-based vectors for nuanced results
- Inspired by [Sprout](https://github.com/ameobea/sprout) (collaborative filtering) and [Yuno](https://github.com/IAmPara0x/yuno) (semantic search)

### User System
- Registration with BCrypt password hashing
- JWT authentication with protected routes
- Per-user scoped data — users can only access their own list

## Recommendation Algorithms

### Genre-Weighted (Current)

1. **Genre weighting** — Each anime on your list contributes its genres to a weight map. The weight is your score (1-10) for that anime, or a default of 5 if unscored.
2. **Top genre selection** — Genres are ranked by total accumulated weight. The top 5 represent your strongest preferences.
3. **Candidate sourcing** — 5 parallel queries to AniList fetch 25 popular anime per top genre.
4. **Filtering** — Candidates already on your list or blacklist are excluded and deduplicated.
5. **Results** — Up to 10 unique recommendations are returned.

### Semantic Search (In Progress)

1. **Seed selection** — User picks 1-5 anime they enjoy.
2. **Context query** — Optional natural language description (e.g., "dark psychological thriller with antiheroes").
3. **Vector blending** — Seed anime embeddings are averaged, then blended with the query embedding: `0.6 * seedAvg + 0.4 * queryVector`.
4. **Cosine similarity** — pgvector searches the `anime_embeddings` table using an IVFFlat index for fast nearest-neighbor lookup.
5. **Results** — Top 15 similar anime returned, excluding user's list, blacklist, and seeds.

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
| GET | `/api/users/recommendations` | Yes | Get genre-based recommendations |
| POST | `/api/users/recommendations/semantic` | Yes | Get AI semantic recommendations *(planned)* |
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

Built as a portfolio project to learn full-stack development, API integration, and recommendation systems (genre-based and AI-powered).
