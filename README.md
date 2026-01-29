# Anime Tracker

Full-stack anime list and recommendation application built with Spring Boot, React, and PostgreSQL.

## Tech Stack

- **Backend**: Spring Boot 3.2 (Java 17)
- **Frontend**: React 18
- **Database**: PostgreSQL 15
- **Migrations**: Flyway
- **Containerization**: Docker & Docker Compose
- **API**: AniList GraphQL

## Features (In Progress)

- [x] Health check endpoint
- [ ] User authentication
- [ ] Anime search (AniList integration)
- [ ] Personal anime list (Watching, Completed, Plan to Watch)
- [ ] Personalized recommendations

## Quick Start

```bash
# Start all services
docker-compose up --build

# Access the application
# Frontend: http://localhost:3000
# Backend API: http://localhost:8080/api/health
```

## Project Structure

```
animetracker/
├── backend/           # Spring Boot backend
├── frontend/          # React frontend
└── docker-compose.yml # Docker orchestration
```

## Development Status

Currently in Milestone 1: Project foundation with Docker Compose, Spring Boot, and React.

## Author

Built as a portfolio project to demonstrate full-stack development skills.
