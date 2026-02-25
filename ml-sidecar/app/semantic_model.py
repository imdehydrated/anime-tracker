"""Semantic model: fine-tuned sentence transformer for anime text embedding."""

from __future__ import annotations

import json
import logging
from pathlib import Path
from time import perf_counter

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
        logger.info("Semantic model loaded: dim=%d", self.embedding_dim)

        self._load_anime_data()

    def _load_anime_data(self):
        """Load precomputed embeddings and anime metadata for reranking."""
        embeddings_path = MODELS_DIR / "anime_embeddings.jsonl"
        self.anime_embeddings: dict[int, np.ndarray] = {}
        self.anime_titles: dict[int, str] = {}

        if embeddings_path.exists():
            with open(embeddings_path, "r", encoding="utf-8") as f:
                for line in f:
                    if not line.strip():
                        continue
                    entry = json.loads(line)
                    aid = int(entry["anilist_id"])
                    self.anime_embeddings[aid] = np.array(entry["embedding"], dtype=np.float32)
                    title = (
                        entry.get("title")
                        or entry.get("title_english")
                        or entry.get("title_romaji")
                        or entry.get("title_native")
                        or ""
                    )
                    self.anime_titles[aid] = str(title)
            logger.info("Loaded %d precomputed anime embeddings", len(self.anime_embeddings))
        else:
            logger.warning("No precomputed embeddings found; embed endpoint only")

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
        top_k: int = 15,
    ) -> list[dict]:
        """Multi-stage reranking of pgvector candidates.

        Stage 1 is pgvector candidate generation in backend.
        Stage 2 is semantic similarity blending.
        """
        t0 = perf_counter()
        query_vec = np.array(query_embedding, dtype=np.float32).reshape(-1)
        results: list[dict] = []

        for cid, pg_distance in zip(candidate_ids, candidate_scores):
            cid_int = int(cid)
            pg_similarity = max(-1.0, min(1.0, 1.0 - float(pg_distance)))
            anime_vec = self.anime_embeddings.get(cid_int)

            if anime_vec is None or anime_vec.shape[0] != query_vec.shape[0]:
                rel_score = max(0.0, min(1.0, (pg_similarity + 1.0) * 0.5))
                results.append(
                    {
                        "anilist_id": cid_int,
                        "score": pg_similarity,
                        "query_adherence_score": rel_score,
                        "title": self.anime_titles.get(cid_int, ""),
                        "_base_score": pg_similarity,
                        "_rel_score": rel_score,
                    }
                )
                continue

            custom_sim = float(np.dot(query_vec, anime_vec))
            blended = 0.7 * custom_sim + 0.3 * pg_similarity
            rel_score = max(0.0, min(1.0, (custom_sim + 1.0) * 0.5))
            results.append(
                {
                    "anilist_id": cid_int,
                    "score": blended,
                    "query_adherence_score": rel_score,
                    "title": self.anime_titles.get(cid_int, ""),
                    "_base_score": blended,
                    "_rel_score": rel_score,
                }
            )

        base_elapsed_ms = (perf_counter() - t0) * 1000.0

        results.sort(key=lambda x: x["score"], reverse=True)
        trimmed = results[:top_k]
        for row in trimmed:
            row.pop("_base_score", None)
            row.pop("_rel_score", None)

        logger.debug(
            "semantic_rerank timings: base_ms=%.2f total_candidates=%d",
            base_elapsed_ms,
            len(results),
        )
        return trimmed
