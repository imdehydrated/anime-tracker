#!/usr/bin/env python3
"""Offline baseline evaluation for CF + semantic recommendation models.

Metrics:
- Recall@K
- HitRate@K
- NDCG@K
- Coverage@K
- Long-tail share
- Novelty

Split strategy:
- Time-based split if a timestamp column exists
- Stratified per-user random split fallback otherwise
"""

from __future__ import annotations

import argparse
import json
import math
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Iterable

import numpy as np
import pandas as pd
import torch
import torch.nn as nn
from tqdm.auto import tqdm


class AnimeCFAutoencoder(nn.Module):
    """Must match the architecture used in notebook training/export."""

    def __init__(self, n_anime: int, bottleneck_dim: int = 256, hidden_dim: int = 1024):
        super().__init__()
        input_dim = n_anime * 2

        self.encoder = nn.Sequential(
            nn.Linear(input_dim, hidden_dim),
            nn.SiLU(),
            nn.Dropout(0.3),
            nn.Linear(hidden_dim, bottleneck_dim),
        )
        self.watch_decoder = nn.Sequential(
            nn.Linear(bottleneck_dim, hidden_dim),
            nn.SiLU(),
            nn.Dropout(0.2),
            nn.Linear(hidden_dim, n_anime),
        )
        self.rating_decoder = nn.Sequential(
            nn.Linear(bottleneck_dim, hidden_dim),
            nn.SiLU(),
            nn.Dropout(0.2),
            nn.Linear(hidden_dim, n_anime),
        )
        # Present in training checkpoint; not used during inference scoring directly.
        self.log_var_watch = nn.Parameter(torch.zeros(1))
        self.log_var_rating = nn.Parameter(torch.zeros(1))

    def forward(self, x: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        z = self.encoder(x)
        return self.watch_decoder(z), self.rating_decoder(z)


@dataclass(frozen=True)
class UserRecord:
    item_id: int
    rating: float
    ts: int


@dataclass(frozen=True)
class EvalResult:
    evaluated_users: int
    recall_at_k: float
    hit_rate_at_k: float
    ndcg_at_k: float
    coverage_at_k: float
    long_tail_share: float
    novelty: float
    avg_ground_truth_size: float
    max_possible_recall_at_k: float
    recall_utilization: float


def parse_args() -> argparse.Namespace:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description="Evaluate CF + semantic recommendation baselines.")
    parser.add_argument(
        "--ratings-path",
        type=Path,
        default=root / "notebooks" / "data" / "ratings_filtered.csv",
        help="Ratings CSV path (expects user_id, anime_id, rating; optional timestamp).",
    )
    parser.add_argument(
        "--cf-model-path",
        type=Path,
        default=root / "ml-models" / "cf" / "model.pt",
        help="Exported CF model checkpoint path.",
    )
    parser.add_argument(
        "--cf-index-path",
        type=Path,
        default=root / "ml-models" / "cf" / "anime_index.json",
        help="Exported CF anime index path.",
    )
    parser.add_argument(
        "--semantic-embeddings-path",
        type=Path,
        default=root / "ml-models" / "anime_embeddings.jsonl",
        help="Semantic embedding JSONL path.",
    )
    parser.add_argument(
        "--id-map-path",
        type=Path,
        default=root / "ml-models" / "id_map.json",
        help="MAL->AniList ID map path.",
    )
    parser.add_argument(
        "--top-k",
        type=int,
        default=10,
        help="Top-K cutoff for ranking metrics.",
    )
    parser.add_argument(
        "--max-users",
        type=int,
        default=1000,
        help="Maximum number of eligible users to evaluate.",
    )
    parser.add_argument(
        "--min-user-interactions",
        type=int,
        default=20,
        help="Minimum per-user interactions to be eligible for split/eval.",
    )
    parser.add_argument(
        "--min-train-items",
        type=int,
        default=5,
        help="Minimum train interactions required after split.",
    )
    parser.add_argument(
        "--test-ratio",
        type=float,
        default=0.2,
        help="Per-user test holdout ratio.",
    )
    parser.add_argument(
        "--relevance-threshold",
        type=float,
        default=7.0,
        help="Minimum rating in held-out set treated as relevant for ranking metrics.",
    )
    parser.add_argument(
        "--long-tail-percentile",
        type=float,
        default=0.8,
        help="Items at or below this popularity quantile are treated as long-tail.",
    )
    parser.add_argument(
        "--cf-popularity-alpha",
        type=float,
        default=0.0,
        help="Offline CF popularity attenuation alpha (0.0 disables).",
    )
    parser.add_argument(
        "--cf-popularity-smoothing",
        type=float,
        default=1.0,
        help="Offline CF popularity attenuation smoothing term.",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=42,
        help="Random seed for user sampling + stratified split.",
    )
    parser.add_argument(
        "--chunksize",
        type=int,
        default=1_000_000,
        help="CSV chunk size for streaming large datasets.",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=root / "notebooks" / "eval",
        help="Directory for baseline metric snapshots.",
    )
    parser.add_argument(
        "--eval-keep-latest",
        type=int,
        default=40,
        help="Always keep at least this many newest baseline snapshots.",
    )
    parser.add_argument(
        "--eval-max-age-days",
        type=int,
        default=30,
        help="Delete baseline snapshots older than this many days (beyond keep-latest).",
    )
    parser.add_argument(
        "--disable-eval-prune",
        action="store_true",
        help="Disable automatic pruning of old baseline snapshots.",
    )
    parser.add_argument(
        "--write-cf-popularity-path",
        type=Path,
        default=None,
        help="Optional output path for sidecar CF popularity attenuation JSON.",
    )
    parser.add_argument(
        "--experiment-label",
        type=str,
        default=None,
        help="Optional label for this eval run (stored in snapshot metadata).",
    )
    parser.add_argument(
        "--cf-train-long-tail-alpha",
        type=float,
        default=None,
        help="Optional metadata: long_tail_alpha used during CF training.",
    )
    parser.add_argument(
        "--cf-train-max-pos-weight",
        type=float,
        default=None,
        help="Optional metadata: max_pos_weight used during CF training.",
    )
    parser.add_argument(
        "--cf-train-weak-negative-weight",
        type=float,
        default=None,
        help="Optional metadata: weak negative watch-loss weight used in CF training.",
    )
    parser.add_argument(
        "--cf-train-min-kept-items",
        type=int,
        default=None,
        help="Optional metadata: min_kept_items used by CF denoising dataset.",
    )
    parser.add_argument(
        "--cf-train-dropout-min",
        type=float,
        default=None,
        help="Optional metadata: min dropout used by CF denoising dataset.",
    )
    parser.add_argument(
        "--cf-train-dropout-max",
        type=float,
        default=None,
        help="Optional metadata: max dropout used by CF denoising dataset.",
    )
    return parser.parse_args()


def pick_timestamp_column(columns: list[str]) -> str | None:
    candidates = ("timestamp", "ts", "time", "updated_at", "created_at")
    lowered = {c.lower(): c for c in columns}
    for candidate in candidates:
        if candidate in lowered:
            return lowered[candidate]
    return None


def detect_schema(ratings_path: Path) -> tuple[str, str, str, str | None]:
    header = pd.read_csv(ratings_path, nrows=0).columns.tolist()
    lowered = {c.lower(): c for c in header}

    user_col = lowered.get("user_id")
    item_col = lowered.get("anime_id")
    rating_col = lowered.get("rating")

    if not user_col or not item_col or not rating_col:
        raise ValueError(f"Ratings CSV missing required columns. Found: {header}")

    ts_col = pick_timestamp_column(header)
    return user_col, item_col, rating_col, ts_col


def add_counts(counter: dict[int, int], values: pd.Series) -> None:
    value_counts = values.value_counts()
    for key, count in value_counts.items():
        counter[int(key)] += int(count)


def scan_counts(
    ratings_path: Path,
    user_col: str,
    item_col: str,
    chunksize: int,
) -> tuple[dict[int, int], dict[int, int], int]:
    user_counts: dict[int, int] = defaultdict(int)
    item_counts: dict[int, int] = defaultdict(int)
    total_rows = 0

    reader = pd.read_csv(
        ratings_path,
        usecols=[user_col, item_col],
        chunksize=chunksize,
    )
    for chunk in tqdm(reader, desc="Pass 1/2: counting users/items"):
        total_rows += len(chunk)
        add_counts(user_counts, chunk[user_col])
        add_counts(item_counts, chunk[item_col])

    return user_counts, item_counts, total_rows


def sample_users(
    user_counts: dict[int, int],
    min_interactions: int,
    max_users: int,
    seed: int,
) -> list[int]:
    eligible = [uid for uid, cnt in user_counts.items() if cnt >= min_interactions]
    if not eligible:
        return []
    rng = np.random.default_rng(seed)
    if len(eligible) <= max_users:
        eligible.sort()
        return eligible
    sampled = rng.choice(np.array(eligible, dtype=np.int64), size=max_users, replace=False)
    sampled_list = sampled.tolist()
    sampled_list.sort()
    return sampled_list


def collect_interactions(
    ratings_path: Path,
    sampled_users: set[int],
    user_col: str,
    item_col: str,
    rating_col: str,
    ts_col: str | None,
    chunksize: int,
) -> dict[int, list[UserRecord]]:
    interactions: dict[int, list[UserRecord]] = defaultdict(list)
    usecols = [user_col, item_col, rating_col]
    if ts_col:
        usecols.append(ts_col)

    reader = pd.read_csv(ratings_path, usecols=usecols, chunksize=chunksize)
    for chunk in tqdm(reader, desc="Pass 2/2: collecting sampled users"):
        filtered = chunk[chunk[user_col].isin(sampled_users)]
        if filtered.empty:
            continue

        if ts_col:
            raw_ts = filtered[ts_col]
            numeric_ts = pd.to_numeric(raw_ts, errors="coerce")
            if numeric_ts.notna().any():
                ts_values = numeric_ts.fillna(0).astype(np.int64)
            else:
                parsed_dt = pd.to_datetime(raw_ts, errors="coerce", utc=True)
                ts_values = pd.Series(
                    np.where(
                        parsed_dt.notna(),
                        parsed_dt.astype("int64") // 1_000_000_000,
                        0,
                    ),
                    index=filtered.index,
                    dtype=np.int64,
                )
        else:
            ts_values = pd.Series(np.zeros(len(filtered), dtype=np.int64), index=filtered.index)

        for uid, aid, rating, ts in zip(
            filtered[user_col].astype(np.int64).values,
            filtered[item_col].astype(np.int64).values,
            pd.to_numeric(filtered[rating_col], errors="coerce").fillna(0.0).astype(float).values,
            ts_values.values,
        ):
            if not np.isfinite(rating):
                continue
            interactions[int(uid)].append(UserRecord(item_id=int(aid), rating=float(rating), ts=int(ts)))

    return interactions


def dedupe_records(records: list[UserRecord], split_strategy: str) -> list[UserRecord]:
    by_item: dict[int, UserRecord] = {}
    for rec in records:
        prev = by_item.get(rec.item_id)
        if prev is None:
            by_item[rec.item_id] = rec
            continue
        if split_strategy == "time":
            if rec.ts >= prev.ts:
                by_item[rec.item_id] = rec
        else:
            by_item[rec.item_id] = rec
    return list(by_item.values())


def split_history(
    records: list[UserRecord],
    split_strategy: str,
    test_ratio: float,
    min_train_items: int,
    rng: np.random.Generator,
) -> tuple[dict[int, float], dict[int, float]] | None:
    unique_records = dedupe_records(records, split_strategy)
    n = len(unique_records)
    if n < (min_train_items + 1):
        return None

    test_size = max(1, int(math.ceil(n * test_ratio)))
    if n - test_size < min_train_items:
        test_size = n - min_train_items
    if test_size <= 0:
        return None

    if split_strategy == "time":
        ordered = sorted(unique_records, key=lambda r: r.ts)
    else:
        order = np.arange(n)
        rng.shuffle(order)
        ordered = [unique_records[i] for i in order]

    train_records = ordered[:-test_size]
    test_records = ordered[-test_size:]
    if len(train_records) < min_train_items or not test_records:
        return None

    train = {rec.item_id: rec.rating for rec in train_records}
    test = {rec.item_id: rec.rating for rec in test_records if rec.item_id not in train}
    if not test:
        return None
    return train, test


def build_splits(
    interactions: dict[int, list[UserRecord]],
    split_strategy: str,
    test_ratio: float,
    min_train_items: int,
    seed: int,
) -> dict[int, tuple[dict[int, float], dict[int, float]]]:
    rng = np.random.default_rng(seed)
    splits: dict[int, tuple[dict[int, float], dict[int, float]]] = {}
    for uid, records in interactions.items():
        result = split_history(records, split_strategy, test_ratio, min_train_items, rng)
        if result is not None:
            splits[uid] = result
    return splits


def compute_ranking_metrics(recommended: Iterable[int], ground_truth: set[int], k: int) -> tuple[float, float]:
    recs = list(recommended)[:k]
    if not ground_truth:
        return 0.0, 0.0
    hits = [rank for rank, item in enumerate(recs) if item in ground_truth]
    recall = float(len(hits)) / float(len(ground_truth))
    if not hits:
        return recall, 0.0
    dcg = sum(1.0 / math.log2(rank + 2.0) for rank in hits)
    idcg = sum(1.0 / math.log2(rank + 2.0) for rank in range(min(len(ground_truth), k)))
    ndcg = dcg / idcg if idcg > 0 else 0.0
    return recall, ndcg


def relevant_test_set(
    test_ratings: dict[int, float],
    relevance_threshold: float,
) -> set[int]:
    return {
        int(item_id)
        for item_id, rating in test_ratings.items()
        if np.isfinite(float(rating)) and float(rating) >= relevance_threshold
    }


def safe_mean(values: list[float]) -> float:
    return float(np.mean(values)) if values else 0.0


def build_long_tail_set(
    item_popularity: dict[int, int],
    percentile: float,
) -> set[int]:
    if not item_popularity:
        return set()
    counts = np.array(list(item_popularity.values()), dtype=np.float64)
    cutoff = float(np.quantile(counts, percentile))
    return {item for item, cnt in item_popularity.items() if float(cnt) <= cutoff}


def build_novelty_lookup(
    item_popularity: dict[int, int],
) -> dict[int, float]:
    total = float(sum(item_popularity.values()))
    num_items = float(max(len(item_popularity), 1))
    novelty: dict[int, float] = {}
    for item, cnt in item_popularity.items():
        prob = (float(cnt) + 1.0) / (total + num_items)
        novelty[item] = -math.log2(prob)
    return novelty


def summarize_metrics(
    per_user_recall: list[float],
    per_user_hit_rate: list[float],
    per_user_ndcg: list[float],
    recommended_items: list[int],
    catalog_size: int,
    long_tail_items: set[int],
    novelty_lookup: dict[int, float],
    gt_sizes: list[int],
    top_k: int,
) -> EvalResult:
    unique_recommended = set(recommended_items)
    coverage = float(len(unique_recommended)) / float(max(catalog_size, 1))
    long_tail_hits = sum(1 for item in recommended_items if item in long_tail_items)
    long_tail_share = float(long_tail_hits) / float(max(len(recommended_items), 1))
    novelty_vals = [novelty_lookup[item] for item in recommended_items if item in novelty_lookup]
    avg_gt = safe_mean([float(x) for x in gt_sizes])
    max_possible_recall = min(1.0, float(top_k) / avg_gt) if avg_gt > 0.0 else 0.0
    recall_utilization = (
        safe_mean(per_user_recall) / max_possible_recall
        if max_possible_recall > 0.0
        else 0.0
    )
    return EvalResult(
        evaluated_users=len(per_user_recall),
        recall_at_k=safe_mean(per_user_recall),
        hit_rate_at_k=safe_mean(per_user_hit_rate),
        ndcg_at_k=safe_mean(per_user_ndcg),
        coverage_at_k=coverage,
        long_tail_share=long_tail_share,
        novelty=safe_mean(novelty_vals),
        avg_ground_truth_size=avg_gt,
        max_possible_recall_at_k=max_possible_recall,
        recall_utilization=recall_utilization,
    )


def load_cf_artifacts(cf_model_path: Path, cf_index_path: Path) -> tuple[AnimeCFAutoencoder, dict[int, int], dict[int, int]]:
    with cf_index_path.open("r", encoding="utf-8") as f:
        anime_index = json.load(f)
    mal_to_idx: dict[int, int] = {}
    idx_to_mal: dict[int, int] = {}
    for entry in anime_index:
        idx = int(entry["idx"])
        mal_id = int(entry["mal_id"])
        mal_to_idx[mal_id] = idx
        idx_to_mal[idx] = mal_id

    checkpoint = torch.load(cf_model_path, map_location="cpu", weights_only=False)
    n_anime = int(checkpoint["n_anime"])
    model = AnimeCFAutoencoder(n_anime=n_anime)
    model.load_state_dict(checkpoint["model_state_dict"])
    model.eval()
    return model, mal_to_idx, idx_to_mal


def cf_predict_top_k(
    model: AnimeCFAutoencoder,
    n_anime: int,
    train_idx_ratings: dict[int, float],
    top_k: int,
    item_popularity_idx: dict[int, int] | None = None,
    popularity_alpha: float = 0.0,
    popularity_smoothing: float = 1.0,
) -> list[int]:
    watched = np.zeros(n_anime, dtype=np.float32)
    ratings = np.zeros(n_anime, dtype=np.float32)

    valid_scores = [float(s) for s in train_idx_ratings.values() if np.isfinite(float(s))]
    user_mean = float(np.mean(valid_scores)) if valid_scores else 6.5

    for idx, score in train_idx_ratings.items():
        if 0 <= idx < n_anime:
            watched[idx] = 1.0
            ratings[idx] = float(score) - user_mean

    input_vec = np.concatenate([watched, ratings], dtype=np.float32)
    with torch.no_grad():
        watch_logits, rating_pred = model(torch.from_numpy(input_vec).unsqueeze(0))
    watch_prob = torch.sigmoid(watch_logits).cpu().numpy().reshape(-1)
    rating_pred = rating_pred.cpu().numpy().reshape(-1)

    denorm_rating = np.clip(rating_pred + user_mean, 1.0, 10.0)
    blended_score = watch_prob * denorm_rating
    if popularity_alpha > 0.0 and item_popularity_idx:
        attenuation = np.ones(n_anime, dtype=np.float32)
        smooth = max(0.0, float(popularity_smoothing))
        for idx in range(n_anime):
            pop = item_popularity_idx.get(idx)
            if pop is None:
                continue
            denom = 1.0 + float(np.log1p(float(pop) + smooth))
            if denom > 0.0:
                attenuation[idx] = float(denom ** (-float(popularity_alpha)))
        blended_score = blended_score * attenuation
    blended_score[watched > 0.0] = -np.inf

    top_k = min(top_k, n_anime)
    if top_k <= 0:
        return []

    top_idx = np.argpartition(-blended_score, kth=top_k - 1)[:top_k]
    ordered = top_idx[np.argsort(-blended_score[top_idx])]
    return [int(i) for i in ordered if np.isfinite(blended_score[i])]


def evaluate_cf(
    splits: dict[int, tuple[dict[int, float], dict[int, float]]],
    model: AnimeCFAutoencoder,
    mal_to_idx: dict[int, int],
    top_k: int,
    relevance_threshold: float,
    long_tail_percentile: float,
    item_popularity_mal: dict[int, int],
    cf_popularity_alpha: float,
    cf_popularity_smoothing: float,
) -> EvalResult:
    n_anime = len(mal_to_idx)
    item_popularity_idx = {
        mal_to_idx[mal_id]: cnt
        for mal_id, cnt in item_popularity_mal.items()
        if mal_id in mal_to_idx
    }
    long_tail = build_long_tail_set(item_popularity_idx, long_tail_percentile)
    novelty_lookup = build_novelty_lookup(item_popularity_idx)

    recalls: list[float] = []
    hit_rates: list[float] = []
    ndcgs: list[float] = []
    gt_sizes: list[int] = []
    all_recommended: list[int] = []

    for train_mal, test_mal in tqdm(splits.values(), desc="Evaluating CF"):
        train_idx = {mal_to_idx[item]: rating for item, rating in train_mal.items() if item in mal_to_idx}
        test_relevant_mal = relevant_test_set(test_mal, relevance_threshold=relevance_threshold)
        test_idx = {mal_to_idx[item] for item in test_relevant_mal if item in mal_to_idx}
        if len(train_idx) < 1 or not test_idx:
            continue

        rec_idx = cf_predict_top_k(
            model=model,
            n_anime=n_anime,
            train_idx_ratings=train_idx,
            top_k=top_k,
            item_popularity_idx=item_popularity_idx,
            popularity_alpha=cf_popularity_alpha,
            popularity_smoothing=cf_popularity_smoothing,
        )
        if not rec_idx:
            continue
        recall, ndcg = compute_ranking_metrics(rec_idx, test_idx, top_k)
        recalls.append(recall)
        hit_rates.append(1.0 if recall > 0.0 else 0.0)
        ndcgs.append(ndcg)
        gt_sizes.append(len(test_idx))
        all_recommended.extend(rec_idx)

    return summarize_metrics(
        per_user_recall=recalls,
        per_user_hit_rate=hit_rates,
        per_user_ndcg=ndcgs,
        recommended_items=all_recommended,
        catalog_size=n_anime,
        long_tail_items=long_tail,
        novelty_lookup=novelty_lookup,
        gt_sizes=gt_sizes,
        top_k=top_k,
    )


def load_semantic_artifacts(
    id_map_path: Path,
    semantic_embeddings_path: Path,
) -> tuple[dict[int, int], np.ndarray, np.ndarray]:
    with id_map_path.open("r", encoding="utf-8") as f:
        id_map = json.load(f)
    mal_to_anilist = {int(k): int(v) for k, v in id_map["mal_to_anilist"].items()}

    anime_ids: list[int] = []
    vectors: list[np.ndarray] = []
    with semantic_embeddings_path.open("r", encoding="utf-8") as f:
        for line in f:
            if not line.strip():
                continue
            row = json.loads(line)
            aid = int(row["anilist_id"])
            vec = np.asarray(row["embedding"], dtype=np.float32)
            norm = np.linalg.norm(vec)
            if norm <= 0:
                continue
            anime_ids.append(aid)
            vectors.append(vec / norm)

    if not anime_ids:
        raise ValueError(f"No semantic embeddings loaded from: {semantic_embeddings_path}")
    return mal_to_anilist, np.asarray(anime_ids, dtype=np.int64), np.vstack(vectors).astype(np.float32)


def write_cf_popularity_file(
    item_popularity_mal: dict[int, int],
    mal_to_anilist: dict[int, int],
    output_path: Path,
) -> int:
    anilist_popularity: dict[int, int] = defaultdict(int)
    for mal_id, cnt in item_popularity_mal.items():
        aid = mal_to_anilist.get(mal_id)
        if aid is None:
            continue
        anilist_popularity[int(aid)] += int(cnt)

    output_path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "anilist_popularity": {
            str(k): int(v)
            for k, v in sorted(anilist_popularity.items(), key=lambda x: x[0])
        },
    }
    with output_path.open("w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2)
    return len(anilist_popularity)


def prune_eval_snapshots(
    output_dir: Path,
    pattern: str,
    keep_latest: int,
    max_age_days: int,
) -> int:
    keep_latest = max(0, int(keep_latest))
    max_age_days = max(0, int(max_age_days))
    candidates = sorted(
        output_dir.glob(pattern),
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    )
    cutoff_epoch = (datetime.now(timezone.utc) - timedelta(days=max_age_days)).timestamp()
    deleted = 0
    for idx, path in enumerate(candidates):
        if idx < keep_latest:
            continue
        try:
            if path.stat().st_mtime <= cutoff_epoch:
                path.unlink()
                deleted += 1
        except OSError:
            continue
    return deleted


def semantic_recommend_top_k(
    train_anilist_ratings: dict[int, float],
    anime_ids: np.ndarray,
    embedding_matrix: np.ndarray,
    id_to_row: dict[int, int],
    top_k: int,
) -> list[int]:
    weighted_vectors: list[np.ndarray] = []
    weights: list[float] = []
    seen_ids: set[int] = set()

    for aid, rating in train_anilist_ratings.items():
        row_idx = id_to_row.get(aid)
        if row_idx is None:
            continue
        seen_ids.add(aid)
        weighted_vectors.append(embedding_matrix[row_idx])
        weights.append(max(float(rating) / 10.0, 0.05))

    if not weighted_vectors:
        return []

    matrix = np.vstack(weighted_vectors)
    weight_arr = np.asarray(weights, dtype=np.float32)
    query = np.average(matrix, axis=0, weights=weight_arr)
    query_norm = np.linalg.norm(query)
    if query_norm <= 0:
        return []
    query = query / query_norm

    scores = embedding_matrix @ query
    for aid in seen_ids:
        row_idx = id_to_row.get(aid)
        if row_idx is not None:
            scores[row_idx] = -np.inf

    top_k = min(top_k, len(scores))
    if top_k <= 0:
        return []
    top_idx = np.argpartition(-scores, kth=top_k - 1)[:top_k]
    ordered = top_idx[np.argsort(-scores[top_idx])]
    return [int(anime_ids[i]) for i in ordered if np.isfinite(scores[i])]


def evaluate_semantic(
    splits: dict[int, tuple[dict[int, float], dict[int, float]]],
    mal_to_anilist: dict[int, int],
    anime_ids: np.ndarray,
    embedding_matrix: np.ndarray,
    top_k: int,
    relevance_threshold: float,
    long_tail_percentile: float,
    item_popularity_mal: dict[int, int],
) -> EvalResult:
    id_to_row = {int(aid): idx for idx, aid in enumerate(anime_ids.tolist())}

    item_popularity_anilist: dict[int, int] = defaultdict(int)
    for mal_id, cnt in item_popularity_mal.items():
        aid = mal_to_anilist.get(mal_id)
        if aid is not None and aid in id_to_row:
            item_popularity_anilist[aid] += int(cnt)

    long_tail = build_long_tail_set(item_popularity_anilist, long_tail_percentile)
    novelty_lookup = build_novelty_lookup(item_popularity_anilist)

    recalls: list[float] = []
    hit_rates: list[float] = []
    ndcgs: list[float] = []
    gt_sizes: list[int] = []
    all_recommended: list[int] = []

    for train_mal, test_mal in tqdm(splits.values(), desc="Evaluating semantic"):
        train_ali = {
            mal_to_anilist[item]: rating
            for item, rating in train_mal.items()
            if item in mal_to_anilist and mal_to_anilist[item] in id_to_row
        }
        test_relevant_mal = relevant_test_set(test_mal, relevance_threshold=relevance_threshold)
        test_ali = {
            mal_to_anilist[item]
            for item in test_relevant_mal
            if item in mal_to_anilist and mal_to_anilist[item] in id_to_row
        }
        if len(train_ali) < 1 or not test_ali:
            continue

        rec_ali = semantic_recommend_top_k(
            train_anilist_ratings=train_ali,
            anime_ids=anime_ids,
            embedding_matrix=embedding_matrix,
            id_to_row=id_to_row,
            top_k=top_k,
        )
        if not rec_ali:
            continue
        recall, ndcg = compute_ranking_metrics(rec_ali, test_ali, top_k)
        recalls.append(recall)
        hit_rates.append(1.0 if recall > 0.0 else 0.0)
        ndcgs.append(ndcg)
        gt_sizes.append(len(test_ali))
        all_recommended.extend(rec_ali)

    return summarize_metrics(
        per_user_recall=recalls,
        per_user_hit_rate=hit_rates,
        per_user_ndcg=ndcgs,
        recommended_items=all_recommended,
        catalog_size=len(anime_ids),
        long_tail_items=long_tail,
        novelty_lookup=novelty_lookup,
        gt_sizes=gt_sizes,
        top_k=top_k,
    )


def result_to_dict(result: EvalResult) -> dict[str, float | int]:
    return {
        "evaluated_users": result.evaluated_users,
        "recall_at_k": round(result.recall_at_k, 6),
        "hit_rate_at_k": round(result.hit_rate_at_k, 6),
        "ndcg_at_k": round(result.ndcg_at_k, 6),
        "coverage_at_k": round(result.coverage_at_k, 6),
        "long_tail_share": round(result.long_tail_share, 6),
        "novelty": round(result.novelty, 6),
        "avg_ground_truth_size": round(result.avg_ground_truth_size, 4),
        "max_possible_recall_at_k": round(result.max_possible_recall_at_k, 6),
        "recall_utilization": round(result.recall_utilization, 6),
    }


def print_summary(top_k: int, cf_result: EvalResult, semantic_result: EvalResult) -> None:
    print("\nOffline Baseline Metrics")
    print("=" * 72)
    print(
        f"{'Model':<10} {'Users':>7} {'Recall@K':>9} {'Hit@K':>8} {'NDCG@K':>9} "
        f"{'Coverage@K':>11} {'LongTail':>9} {'Novelty':>9}"
    )
    print("-" * 72)
    print(
        f"{'CF':<10} {cf_result.evaluated_users:>7} {cf_result.recall_at_k:>9.4f} "
        f"{cf_result.hit_rate_at_k:>8.4f} {cf_result.ndcg_at_k:>9.4f} "
        f"{cf_result.coverage_at_k:>11.4f} {cf_result.long_tail_share:>9.4f} {cf_result.novelty:>9.4f}"
    )
    print(
        f"{'Semantic':<10} {semantic_result.evaluated_users:>7} {semantic_result.recall_at_k:>9.4f} "
        f"{semantic_result.hit_rate_at_k:>8.4f} {semantic_result.ndcg_at_k:>9.4f} "
        f"{semantic_result.coverage_at_k:>11.4f} {semantic_result.long_tail_share:>9.4f} {semantic_result.novelty:>9.4f}"
    )
    print(f"\nK={top_k}")
    print(
        f"CF recall utilization={cf_result.recall_utilization:.3f} "
        f"(max_possible_recall@K={cf_result.max_possible_recall_at_k:.3f})"
    )
    print(
        f"Semantic recall utilization={semantic_result.recall_utilization:.3f} "
        f"(max_possible_recall@K={semantic_result.max_possible_recall_at_k:.3f})"
    )


def main() -> None:
    args = parse_args()
    ratings_path = args.ratings_path.resolve()
    if not ratings_path.exists():
        raise FileNotFoundError(f"Ratings CSV not found: {ratings_path}")

    user_col, item_col, rating_col, ts_col = detect_schema(ratings_path)
    split_strategy = "time" if ts_col else "stratified"
    if split_strategy == "stratified":
        print("No timestamp column found. Using stratified per-user random split.")
    else:
        print(f"Using time-based split on column: {ts_col}")

    user_counts, item_counts, total_rows = scan_counts(
        ratings_path=ratings_path,
        user_col=user_col,
        item_col=item_col,
        chunksize=args.chunksize,
    )
    sampled_users = sample_users(
        user_counts=user_counts,
        min_interactions=args.min_user_interactions,
        max_users=args.max_users,
        seed=args.seed,
    )
    if not sampled_users:
        raise RuntimeError("No eligible users found. Lower --min-user-interactions.")

    interactions = collect_interactions(
        ratings_path=ratings_path,
        sampled_users=set(sampled_users),
        user_col=user_col,
        item_col=item_col,
        rating_col=rating_col,
        ts_col=ts_col,
        chunksize=args.chunksize,
    )
    splits = build_splits(
        interactions=interactions,
        split_strategy=split_strategy,
        test_ratio=args.test_ratio,
        min_train_items=args.min_train_items,
        seed=args.seed,
    )
    if not splits:
        raise RuntimeError("No valid user splits produced. Adjust split/eval settings.")

    cf_model, mal_to_idx, _idx_to_mal = load_cf_artifacts(
        cf_model_path=args.cf_model_path.resolve(),
        cf_index_path=args.cf_index_path.resolve(),
    )
    cf_result = evaluate_cf(
        splits=splits,
        model=cf_model,
        mal_to_idx=mal_to_idx,
        top_k=args.top_k,
        relevance_threshold=args.relevance_threshold,
        long_tail_percentile=args.long_tail_percentile,
        item_popularity_mal=item_counts,
        cf_popularity_alpha=max(0.0, float(args.cf_popularity_alpha)),
        cf_popularity_smoothing=max(0.0, float(args.cf_popularity_smoothing)),
    )

    mal_to_anilist, anime_ids, embedding_matrix = load_semantic_artifacts(
        id_map_path=args.id_map_path.resolve(),
        semantic_embeddings_path=args.semantic_embeddings_path.resolve(),
    )
    popularity_written_entries = 0
    popularity_output_path: str | None = None
    if args.write_cf_popularity_path is not None:
        pop_path = args.write_cf_popularity_path.resolve()
        popularity_written_entries = write_cf_popularity_file(
            item_popularity_mal=item_counts,
            mal_to_anilist=mal_to_anilist,
            output_path=pop_path,
        )
        popularity_output_path = str(pop_path)
        print(f"Wrote CF popularity file: {pop_path} (entries={popularity_written_entries})")

    semantic_result = evaluate_semantic(
        splits=splits,
        mal_to_anilist=mal_to_anilist,
        anime_ids=anime_ids,
        embedding_matrix=embedding_matrix,
        top_k=args.top_k,
        relevance_threshold=args.relevance_threshold,
        long_tail_percentile=args.long_tail_percentile,
        item_popularity_mal=item_counts,
    )

    print_summary(args.top_k, cf_result, semantic_result)

    now = datetime.now(timezone.utc)
    timestamp = now.strftime("%Y%m%dT%H%M%SZ")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    output_path = args.output_dir / f"baseline_metrics_{timestamp}.json"

    payload = {
        "generated_at_utc": now.isoformat(),
        "split_strategy": split_strategy,
        "top_k": args.top_k,
        "input": {
            "ratings_path": str(ratings_path),
            "total_rows": total_rows,
            "unique_users": len(user_counts),
            "unique_items": len(item_counts),
            "sampled_users": len(sampled_users),
            "valid_user_splits": len(splits),
            "max_users": args.max_users,
            "min_user_interactions": args.min_user_interactions,
            "min_train_items": args.min_train_items,
            "test_ratio": args.test_ratio,
            "relevance_threshold": args.relevance_threshold,
            "long_tail_percentile": args.long_tail_percentile,
            "cf_popularity_alpha": args.cf_popularity_alpha,
            "cf_popularity_smoothing": args.cf_popularity_smoothing,
            "seed": args.seed,
        },
        "artifacts": {
            "cf_popularity_output_path": popularity_output_path,
            "cf_popularity_entries": popularity_written_entries,
        },
        "experiment": {
            "label": args.experiment_label,
            "cf_train_long_tail_alpha": args.cf_train_long_tail_alpha,
            "cf_train_max_pos_weight": args.cf_train_max_pos_weight,
            "cf_train_weak_negative_weight": args.cf_train_weak_negative_weight,
            "cf_train_min_kept_items": args.cf_train_min_kept_items,
            "cf_train_dropout_min": args.cf_train_dropout_min,
            "cf_train_dropout_max": args.cf_train_dropout_max,
        },
        "cf": result_to_dict(cf_result),
        "semantic": result_to_dict(semantic_result),
    }

    with output_path.open("w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2)

    print(f"\nSaved baseline snapshot: {output_path}")
    if not args.disable_eval_prune:
        pruned_count = prune_eval_snapshots(
            output_dir=args.output_dir,
            pattern="baseline_metrics_*.json",
            keep_latest=args.eval_keep_latest,
            max_age_days=args.eval_max_age_days,
        )
        if pruned_count > 0:
            print(
                f"Pruned old baseline snapshots: {pruned_count} "
                f"(keep_latest={args.eval_keep_latest}, max_age_days={args.eval_max_age_days})"
            )


if __name__ == "__main__":
    main()
