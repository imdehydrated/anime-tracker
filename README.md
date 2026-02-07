# Anime Tracker

Full-stack anime list and recommendation application built with Spring Boot, React, and PostgreSQL.

## Tech Stack

- **Backend**: Spring Boot 3.2 (Java 17)
- **Frontend**: React 18
- **Database**: PostgreSQL 15
- **Migrations**: Flyway
- **Containerization**: Docker & Docker Compose
- **API**: AniList GraphQL

## Features

- [x] User registration with BCrypt password hashing
- [x] JWT authentication with protected routes
- [x] Personal anime list CRUD (Add, View, Update, Delete)
- [x] Status tracking (Watching, Completed, Plan to Watch, Dropped, On Hold)
- [x] Score and episode progress tracking
- [x] Anime search (AniList GraphQL integration)
- [ ] React frontend with routing and auth
- [ ] Recommendation engine

## API Endpoints

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| POST | `/api/users/register` | No | Register a new account |
| POST | `/api/users/login` | No | Login and receive JWT token |
| GET | `/api/users/list` | Yes | Get your anime list |
| POST | `/api/users/list` | Yes | Add anime to your list |
| PUT | `/api/users/list/{id}` | Yes | Update an entry (status, score, episodes) |
| DELETE | `/api/users/list/{id}` | Yes | Remove an entry |
| GET | `/api/anime/search?q=` | No | Search anime by title (AniList) |
| GET | `/api/health` | No | Health check |

## Quick Start

```bash
# Start all services
docker-compose up --build

# Backend API: http://localhost:8080
# Frontend: http://localhost:3000 (coming soon)
```

## Project Structure

```
animetracker/
├── backend/                # Spring Boot backend
│   └── src/main/java/com/animetracker/
│       ├── config/         # Security, JWT, filters
│       ├── controller/     # REST controllers
│       ├── model/          # JPA entities
│       ├── repository/     # Data access layer
│       ├── dto/            # Data transfer objects
│       └── service/        # Business logic
├── frontend/               # React frontend (coming soon)
└── docker-compose.yml      # Docker orchestration (PostgreSQL + backend)
```

## Development Status

Milestones 1-7 complete (project setup, database schema, user auth, JWT login, protected routes, anime list CRUD, AniList API integration). Currently moving into frontend development.

## Author

Built as a portfolio project to learn new technologies and develop full-stack skills.
