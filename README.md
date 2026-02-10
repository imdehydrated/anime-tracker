# AniRec

Full-stack anime list and recommendation application built with Spring Boot, React, and PostgreSQL. Track your anime, rate what you've watched, and get personalized recommendations based on your taste.

## Tech Stack

- **Backend**: Spring Boot 3.5 (Java 17)
- **Frontend**: React 18 with React Router v6
- **Database**: PostgreSQL 15
- **Migrations**: Flyway
- **Containerization**: Docker & Docker Compose
- **External API**: AniList GraphQL

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

### Recommendation Engine
- Genre-weighted scoring algorithm based on your list and ratings
- Parallel AniList API queries for fast results
- Blacklist management to hide unwanted recommendations
- Refresh on demand

### User System
- Registration with BCrypt password hashing
- JWT authentication with protected routes
- Per-user scoped data — users can only access their own list

## Recommendation Algorithm

AniRec uses a genre-weighted scoring algorithm to suggest anime:

1. **Genre weighting** — Each anime on your list contributes its genres to a weight map. The weight is your score (1-10) for that anime, or a default of 5 if unscored. Higher-rated anime have more influence on your genre profile.

2. **Top genre selection** — Genres are ranked by total accumulated weight. The top 5 represent your strongest preferences.

3. **Candidate sourcing** — 5 parallel queries to the AniList API fetch 25 popular anime per top genre, sorted by popularity. This runs concurrently for speed.

4. **Filtering** — Candidates already on your list or blacklist are excluded. Duplicates across genre queries are deduplicated.

5. **Results** — Up to 10 unique recommendations are returned.

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
| GET | `/api/users/recommendations` | Yes | Get personalized recommendations |
| POST | `/api/users/recommendations/blacklist` | Yes | Hide anime from recommendations |
| GET | `/api/users/recommendations/blacklist` | Yes | View blacklisted anime |
| DELETE | `/api/users/recommendations/blacklist/{id}` | Yes | Remove from blacklist |
| GET | `/api/health` | No | Health check |

## Quick Start

```bash
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
│       ├── entity/             # JPA entities
│       ├── repository/         # Data access layer
│       ├── dto/                # AniList API response DTOs
│       └── service/            # Business logic (list, recommendations, AniList)
├── frontend/                   # React frontend
│   └── src/
│       ├── context/            # Auth context (JWT state)
│       ├── hooks/              # Shared hooks (useAuthHeader, useAddToList)
│       ├── components/         # Reusable components (NavBar)
│       └── pages/              # Page components
└── docker-compose.yml          # Docker orchestration (DB + backend + frontend)
```

## Author

Built as a portfolio project to learn full-stack development, API integration, and recommendation systems.
