FROM python:3.11-slim

WORKDIR /app

# Install system dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

# Install Python dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application code
COPY app/ ./app/

# Models are mounted as a Docker volume at /app/models
# This directory is expected to contain:
#   semantic/          — sentence-transformers checkpoint
#   cf/model.pt        — CF autoencoder checkpoint
#   cf/anime_index.json — anime ID mapping
#   anime_embeddings.jsonl — precomputed embeddings
#   id_map.json        — MAL↔AniList ID mapping

EXPOSE 5000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "5000"]
