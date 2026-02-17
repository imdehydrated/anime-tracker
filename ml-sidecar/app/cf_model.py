"""Collaborative filtering model: denoising autoencoder for rating prediction."""

import json
import logging
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn

logger = logging.getLogger(__name__)

MODELS_DIR = Path("/app/models")


class AnimeCFAutoencoder(nn.Module):
    """Same architecture as training notebook — must match exactly."""

    def __init__(self, n_anime, bottleneck_dim=256, hidden_dim=1024):
        super().__init__()
        self.n_anime = n_anime
        input_dim = n_anime * 2

        self.encoder = nn.Sequential(
            nn.Linear(input_dim, hidden_dim),
            nn.SiLU(),
            nn.Dropout(0.3),
            nn.Linear(hidden_dim, bottleneck_dim)
        )

        self.watch_decoder = nn.Sequential(
            nn.Linear(bottleneck_dim, hidden_dim),
            nn.SiLU(),
            nn.Dropout(0.2),
            nn.Linear(hidden_dim, n_anime)
        )

        self.rating_decoder = nn.Sequential(
            nn.Linear(bottleneck_dim, hidden_dim),
            nn.SiLU(),
            nn.Dropout(0.2),
            nn.Linear(hidden_dim, n_anime)
        )

        self.log_var_watch = nn.Parameter(torch.zeros(1))
        self.log_var_rating = nn.Parameter(torch.zeros(1))

    def forward(self, x):
        z = self.encoder(x)
        watch_logits = self.watch_decoder(z)
        rating_pred = self.rating_decoder(z)
        return watch_logits, rating_pred


class CFModel:
    def __init__(self):
        cf_dir = MODELS_DIR / "cf"
        model_path = cf_dir / "model.pt"
        index_path = cf_dir / "anime_index.json"

        if not model_path.exists():
            raise FileNotFoundError(f"CF model not found at {model_path}")

        # Load anime index
        with open(index_path, "r") as f:
            anime_index = json.load(f)

        self.anilist_to_idx = {}
        self.idx_to_anilist = {}
        self.mal_to_idx = {}

        for entry in anime_index:
            idx = entry["idx"]
            mal_id = entry["mal_id"]
            anilist_id = entry.get("anilist_id")

            self.mal_to_idx[mal_id] = idx
            if anilist_id:
                self.anilist_to_idx[anilist_id] = idx
                self.idx_to_anilist[idx] = anilist_id

        self.n_anime = len(anime_index)

        # Load model
        checkpoint = torch.load(model_path, map_location="cpu", weights_only=False)
        self.model = AnimeCFAutoencoder(checkpoint["n_anime"])
        self.model.load_state_dict(checkpoint["model_state_dict"])
        self.model.eval()

        logger.info(f"CF model loaded: {self.n_anime} anime, epoch {checkpoint.get('epoch', '?')}")

    def predict(
        self,
        user_ratings: dict[int, float],
        exclude_ids: list[int] | None = None,
        top_k: int = 15
    ) -> list[dict]:
        """Predict ratings for a user given their existing ratings.

        Args:
            user_ratings: dict of {anilist_id: score} for the user's rated anime
            exclude_ids: AniList IDs to exclude from results
            top_k: number of top predictions to return

        Returns:
            List of {anilist_id, predicted_score, watch_confidence}
        """
        exclude_set = set(exclude_ids or [])
        valid_scores = []
        for score in user_ratings.values():
            try:
                score_val = float(score)
            except (TypeError, ValueError):
                continue
            if np.isfinite(score_val):
                valid_scores.append(score_val)
        user_mean = float(np.mean(valid_scores)) if valid_scores else 6.5

        # Build input vector
        watched = np.zeros(self.n_anime, dtype=np.float32)
        ratings = np.zeros(self.n_anime, dtype=np.float32)

        for anilist_id, score in user_ratings.items():
            idx = self.anilist_to_idx.get(anilist_id)
            if idx is not None and idx < self.n_anime:
                watched[idx] = 1.0
                # Match training normalization: center by this user's mean score.
                ratings[idx] = float(score) - user_mean

        input_vec = np.concatenate([watched, ratings])
        input_tensor = torch.FloatTensor(input_vec).unsqueeze(0)

        with torch.no_grad():
            watch_logits, rating_pred = self.model(input_tensor)

        watch_probs = torch.sigmoid(watch_logits).numpy().flatten()
        pred_ratings = rating_pred.numpy().flatten()

        # Score = watch probability * denormalized predicted rating
        results = []
        for idx in range(self.n_anime):
            anilist_id = self.idx_to_anilist.get(idx)
            if anilist_id is None:
                continue
            if watched[idx] > 0:  # Skip already watched
                continue
            if anilist_id in exclude_set:
                continue

            predicted_score = float(np.clip(pred_ratings[idx] + user_mean, 1.0, 10.0))
            confidence = float(watch_probs[idx])

            results.append({
                "anilist_id": anilist_id,
                "predicted_score": round(predicted_score, 2),
                "watch_confidence": round(confidence, 4)
            })

        # Sort by combined signal: confidence * predicted_score
        results.sort(key=lambda x: x["watch_confidence"] * x["predicted_score"], reverse=True)
        return results[:top_k]
