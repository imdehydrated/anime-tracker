"""Semantic model: fine-tuned sentence transformer for anime text embedding."""

import json
import logging
from pathlib import Path

import numpy as np
from sentence_transformers import SentenceTransformer

logger = logging.getLogger(__name__)

MODELS_DIR = Path("/app/models")


class SemanticModel:
    def __init__(self):
        model_path = MODELS_DIR / "semantic"
        if not model_path.exists():
            raise FileNotFoundError(f"Semantic model not found at {model_path}")

        self.model = SentenceTransformer(str(model_path))
        self.embedding_dim = self.model.get_sentence_embedding_dimension()
        logger.info(f"Semantic model loaded: dim={self.embedding_dim}")

        # Load precomputed anime embeddings for tag-based reranking
        self._load_anime_data()

    def _load_anime_data(self):
        """Load precomputed embeddings and anime metadata for reranking."""
        embeddings_path = MODELS_DIR / "anime_embeddings.jsonl"
        self.anime_embeddings = {}  # anilist_id -> np.array
        self.anime_titles = {}     # anilist_id -> title

        if embeddings_path.exists():
            with open(embeddings_path, "r") as f:
                for line in f:
                    entry = json.loads(line)
                    aid = entry["anilist_id"]
                    self.anime_embeddings[aid] = np.array(entry["embedding"], dtype=np.float32)
                    self.anime_titles[aid] = entry.get("title", "")
            logger.info(f"Loaded {len(self.anime_embeddings)} precomputed anime embeddings")
        else:
            logger.warning("No precomputed embeddings found — embed endpoint only")

    def embed(self, text: str) -> list[float]:
        """Embed a text string into a 384-dim vector."""
        embedding = self.model.encode(text, normalize_embeddings=True)
        return embedding.tolist()

    def embed_batch(self, texts: list[str]) -> list[list[float]]:
        """Embed multiple texts."""
        embeddings = self.model.encode(texts, normalize_embeddings=True, batch_size=32)
        return embeddings.tolist()

    def rerank(
        self,
        query_embedding: list[float],
        candidate_ids: list[int],
        candidate_scores: list[float],
        top_k: int = 15
    ) -> list[dict]:
        """Multi-stage reranking of pgvector candidates.

        Stage 1 (pgvector) already done by Spring Boot — we receive candidates.
        Stage 2: Rerank using our fine-tuned embeddings for better precision.
        Stage 3: Blend sidecar similarity with pgvector similarity.

        Note: candidate_scores are pgvector cosine distances from the backend.
        We convert them to cosine similarity via (1 - distance).
        """
        query_vec = np.array(query_embedding, dtype=np.float32)
        results = []

        for cid, pg_distance in zip(candidate_ids, candidate_scores):
            pg_similarity = max(-1.0, min(1.0, 1.0 - float(pg_distance)))
            anime_vec = self.anime_embeddings.get(cid)
            if anime_vec is None or anime_vec.shape[0] != query_vec.shape[0]:
                # Fall back to pgvector similarity if we don't have this anime's embedding
                # or dimensions don't match for a safe dot-product.
                results.append({
                    "anilist_id": cid,
                    "score": pg_similarity,
                    "title": self.anime_titles.get(cid, "")
                })
                continue

            # Cosine similarity with our fine-tuned embeddings
            custom_sim = float(np.dot(query_vec, anime_vec))

            # Blend: 70% custom model + 30% pgvector similarity
            blended = 0.7 * custom_sim + 0.3 * pg_similarity

            results.append({
                "anilist_id": cid,
                "score": blended,
                "title": self.anime_titles.get(cid, "")
            })

        # Sort by blended score descending
        results.sort(key=lambda x: x["score"], reverse=True)
        return results[:top_k]
