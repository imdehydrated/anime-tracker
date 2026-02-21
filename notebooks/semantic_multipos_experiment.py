"""Phase 7 semantic experiment runner: multi-positive + hard-neighbor batches.

Why this experiment:
- Unlike pure MNRL, it does not assume one single correct target.
- It trains with label groups (same anime -> multiple positives).
- It packs hard-neighbor labels into the same batch so loss sees challenging negatives.
"""

from __future__ import annotations

import argparse
import json
import time
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Iterator

import numpy as np
import torch
from sentence_transformers import InputExample, SentenceTransformer, losses
from sentence_transformers.evaluation import TripletEvaluator
from torch.utils.data import DataLoader, Sampler


@dataclass
class TripletSample:
    anchor: str
    positive: str
    negative: str
    anchor_anime_id: int | None


class HardNeighborIndexSampler(Sampler[int]):
    """Index sampler that emits contiguous hard-neighbor batches."""

    def __init__(
        self,
        label_to_indices: dict[int, list[int]],
        hard_neighbors: dict[int, list[int]],
        labels_per_batch: int,
        examples_per_label: int,
        steps_per_epoch: int,
        seed: int = 42,
    ) -> None:
        self.label_to_indices = label_to_indices
        self.hard_neighbors = hard_neighbors
        self.labels_per_batch = max(2, int(labels_per_batch))
        self.examples_per_label = max(2, int(examples_per_label))
        self.steps_per_epoch = max(1, int(steps_per_epoch))
        self.seed = int(seed)
        self.labels = sorted(label_to_indices.keys())
        self.batch_size = self.labels_per_batch * self.examples_per_label

    def __len__(self) -> int:
        return self.steps_per_epoch * self.batch_size

    def _sample_label_set(self, rng: np.random.Generator) -> list[int]:
        anchor = int(rng.choice(self.labels))
        selected: list[int] = [anchor]

        neighbors = [n for n in self.hard_neighbors.get(anchor, []) if n in self.label_to_indices]
        if neighbors:
            selected.append(int(rng.choice(neighbors)))

        while len(selected) < self.labels_per_batch:
            choose_hard = neighbors and rng.random() < 0.7
            cand = int(rng.choice(neighbors if choose_hard else self.labels))
            if cand not in selected:
                selected.append(cand)
        return selected

    def __iter__(self) -> Iterator[int]:
        rng = np.random.default_rng(self.seed)
        for _ in range(self.steps_per_epoch):
            batch_labels = self._sample_label_set(rng)
            batch_indices: list[int] = []
            for lbl in batch_labels:
                pool = self.label_to_indices[lbl]
                picked = rng.choice(
                    pool,
                    size=self.examples_per_label,
                    replace=len(pool) < self.examples_per_label,
                )
                batch_indices.extend(int(i) for i in picked.tolist())
            for idx in batch_indices:
                yield idx


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run multi-positive semantic experiment.")
    parser.add_argument("--data-dir", type=Path, default=Path("data"))
    parser.add_argument("--triplets-file", type=Path, default=None)
    parser.add_argument("--init-model-path", type=str, default="models/mlm_pretrained/final")
    parser.add_argument("--baseline-model-path", type=str, default="models/anime_semantic")
    parser.add_argument("--output-path", type=Path, default=Path("models/anime_semantic_multipos"))
    parser.add_argument("--eval-output-dir", type=Path, default=Path("eval"))
    parser.add_argument("--eval-keep-latest", type=int, default=40)
    parser.add_argument("--eval-max-age-days", type=int, default=30)
    parser.add_argument("--disable-eval-prune", action="store_true")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--eval-ratio", type=float, default=0.05)
    parser.add_argument("--max-train-triplets", type=int, default=0, help="0 means all.")
    parser.add_argument("--max-texts-per-anime", type=int, default=12)
    parser.add_argument("--min-texts-per-anime", type=int, default=2)
    parser.add_argument("--hard-neighbor-topk", type=int, default=5)
    parser.add_argument("--labels-per-batch", type=int, default=8)
    parser.add_argument("--examples-per-label", type=int, default=4)
    parser.add_argument("--steps-per-epoch", type=int, default=400)
    parser.add_argument("--epochs", type=int, default=3)
    parser.add_argument("--lr", type=float, default=2e-5)
    parser.add_argument("--warmup-ratio", type=float, default=0.1)
    parser.add_argument("--triplet-margin", type=float, default=0.35)
    parser.add_argument("--max-seq-length", type=int, default=256)
    return parser.parse_args()


def load_triplets(path: Path) -> list[TripletSample]:
    rows: list[TripletSample] = []
    with path.open("r", encoding="utf-8") as f:
        for line in f:
            x = json.loads(line)
            a = str(x.get("anchor", "")).strip()
            p = str(x.get("positive", "")).strip()
            n = str(x.get("negative", "")).strip()
            aid = x.get("anchor_anime_id")
            if not a or not p or not n:
                continue
            rows.append(TripletSample(anchor=a, positive=p, negative=n, anchor_anime_id=aid))
    return rows


def split_triplets(
    triplets: list[TripletSample],
    eval_ratio: float,
    seed: int,
) -> tuple[list[TripletSample], list[TripletSample]]:
    rng = np.random.default_rng(seed)
    idx = np.arange(len(triplets))
    rng.shuffle(idx)
    cut = int((1.0 - eval_ratio) * len(idx))
    train = [triplets[int(i)] for i in idx[:cut]]
    test = [triplets[int(i)] for i in idx[cut:]]
    return train, test


def build_label_texts(
    triplets: list[TripletSample],
    min_texts_per_anime: int,
    max_texts_per_anime: int,
    seed: int,
) -> dict[int, list[str]]:
    label_texts: dict[int, set[str]] = defaultdict(set)
    for row in triplets:
        if row.anchor_anime_id is None:
            continue
        aid = int(row.anchor_anime_id)
        label_texts[aid].add(row.anchor)
        label_texts[aid].add(row.positive)

    rng = np.random.default_rng(seed)
    out: dict[int, list[str]] = {}
    for aid, texts in label_texts.items():
        vals = list(texts)
        if len(vals) < min_texts_per_anime:
            continue
        if len(vals) > max_texts_per_anime:
            picked = rng.choice(len(vals), size=max_texts_per_anime, replace=False)
            vals = [vals[int(i)] for i in picked.tolist()]
        out[aid] = vals
    return out


def build_examples(
    anime_texts: dict[int, list[str]],
) -> tuple[list[InputExample], dict[int, int], dict[int, list[int]]]:
    anime_ids = sorted(anime_texts.keys())
    anime_to_label = {aid: i for i, aid in enumerate(anime_ids)}
    label_to_indices: dict[int, list[int]] = defaultdict(list)
    examples: list[InputExample] = []

    for aid in anime_ids:
        label = anime_to_label[aid]
        for text in anime_texts[aid]:
            idx = len(examples)
            examples.append(InputExample(texts=[text], label=float(label)))
            label_to_indices[label].append(idx)

    return examples, anime_to_label, label_to_indices


def _cosine_matrix(emb: np.ndarray) -> np.ndarray:
    return emb @ emb.T


def build_hard_neighbors(
    anime_texts: dict[int, list[str]],
    anime_to_label: dict[int, int],
    model: SentenceTransformer,
    topk: int,
) -> dict[int, list[int]]:
    anime_ids = sorted(anime_texts.keys())
    # Build one centroid per anime label.
    centroids: list[np.ndarray] = []
    for aid in anime_ids:
        texts = anime_texts[aid]
        emb = model.encode(
            texts,
            normalize_embeddings=True,
            show_progress_bar=False,
            batch_size=128,
        )
        centroids.append(np.mean(np.asarray(emb), axis=0))

    mat = np.asarray(centroids, dtype=np.float32)
    norms = np.linalg.norm(mat, axis=1, keepdims=True)
    mat = mat / np.clip(norms, 1e-8, None)
    sim = _cosine_matrix(mat)
    np.fill_diagonal(sim, -1.0)

    k = max(1, min(int(topk), sim.shape[1] - 1))
    out: dict[int, list[int]] = {}
    for i, aid in enumerate(anime_ids):
        row = sim[i]
        nn = np.argsort(row)[::-1][:k]
        src_label = anime_to_label[aid]
        out[src_label] = [anime_to_label[anime_ids[int(j)]] for j in nn.tolist()]
    return out


def make_triplet_evaluator(eval_triplets: list[TripletSample]) -> TripletEvaluator:
    return TripletEvaluator(
        anchors=[t.anchor for t in eval_triplets],
        positives=[t.positive for t in eval_triplets],
        negatives=[t.negative for t in eval_triplets],
        name="multipos-triplet-eval",
    )


def evaluate_triplet_accuracy(model_path: str | Path, evaluator: TripletEvaluator, device: str) -> float:
    model = SentenceTransformer(str(model_path), device=device)
    result = evaluator(model)
    if isinstance(result, dict):
        for key in sorted(result.keys()):
            if "cosine_accuracy" in key:
                return float(result[key])
        first = next(iter(result.values()))
        return float(first)
    return float(result)


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


def main() -> None:
    args = parse_args()
    np.random.seed(args.seed)
    torch.manual_seed(args.seed)

    triplets_path = args.triplets_file or (args.data_dir / "triplets.jsonl")
    if not triplets_path.exists():
        raise FileNotFoundError(f"Triplets file not found: {triplets_path}")

    all_triplets = load_triplets(triplets_path)
    if not all_triplets:
        raise ValueError(f"No valid triplets found in {triplets_path}")

    train_triplets, eval_triplets = split_triplets(
        all_triplets,
        eval_ratio=float(np.clip(args.eval_ratio, 0.01, 0.5)),
        seed=args.seed,
    )
    if args.max_train_triplets and args.max_train_triplets > 0:
        train_triplets = train_triplets[: int(args.max_train_triplets)]

    device = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"Device: {device}")
    print(f"Total triplets: {len(all_triplets):,}")
    print(f"Train triplets: {len(train_triplets):,}")
    print(f"Eval triplets: {len(eval_triplets):,}")

    anime_texts = build_label_texts(
        train_triplets,
        min_texts_per_anime=int(args.min_texts_per_anime),
        max_texts_per_anime=int(args.max_texts_per_anime),
        seed=args.seed,
    )
    if len(anime_texts) < 2:
        raise ValueError("Not enough labeled anime groups to train multi-positive loss.")

    model = SentenceTransformer(str(args.init_model_path), device=device)
    model.max_seq_length = int(args.max_seq_length)

    examples, anime_to_label, label_to_indices = build_examples(anime_texts)
    hard_neighbors = build_hard_neighbors(
        anime_texts=anime_texts,
        anime_to_label=anime_to_label,
        model=model,
        topk=int(args.hard_neighbor_topk),
    )

    index_sampler = HardNeighborIndexSampler(
        label_to_indices=label_to_indices,
        hard_neighbors=hard_neighbors,
        labels_per_batch=int(args.labels_per_batch),
        examples_per_label=int(args.examples_per_label),
        steps_per_epoch=int(args.steps_per_epoch),
        seed=args.seed,
    )
    batch_size = int(args.labels_per_batch) * int(args.examples_per_label)
    train_loader = DataLoader(
        examples,
        sampler=index_sampler,
        batch_size=batch_size,
        drop_last=True,
    )
    evaluator = make_triplet_evaluator(eval_triplets)

    train_loss = losses.BatchHardTripletLoss(
        model=model,
        distance_metric=losses.BatchHardTripletLossDistanceFunction.cosine_distance,
        margin=float(args.triplet_margin),
    )

    warmup_steps = int(
        max(1, round(int(args.steps_per_epoch) * int(args.epochs) * float(args.warmup_ratio)))
    )
    args.output_path.mkdir(parents=True, exist_ok=True)
    print(
        "Training multi-positive hard-neighbor model: "
        f"epochs={args.epochs}, labels_per_batch={args.labels_per_batch}, "
        f"examples_per_label={args.examples_per_label}, steps_per_epoch={args.steps_per_epoch}, "
        f"lr={args.lr}, margin={args.triplet_margin}, warmup_steps={warmup_steps}"
    )

    model.fit(
        train_objectives=[(train_loader, train_loss)],
        evaluator=evaluator,
        epochs=int(args.epochs),
        warmup_steps=warmup_steps,
        optimizer_params={"lr": float(args.lr)},
        weight_decay=0.01,
        output_path=str(args.output_path),
        evaluation_steps=max(100, int(args.steps_per_epoch) // 2),
        save_best_model=True,
        show_progress_bar=True,
    )

    multipos_score = evaluate_triplet_accuracy(args.output_path, evaluator, device=device)
    baseline_score = None
    baseline_path = Path(args.baseline_model_path)
    if baseline_path.exists():
        baseline_score = evaluate_triplet_accuracy(baseline_path, evaluator, device=device)

    args.eval_output_dir.mkdir(parents=True, exist_ok=True)
    ts = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    result_path = args.eval_output_dir / f"semantic_multipos_experiment_{ts}.json"
    payload = {
        "timestamp_utc": ts,
        "triplets_path": str(triplets_path),
        "init_model_path": str(args.init_model_path),
        "baseline_model_path": str(args.baseline_model_path),
        "output_model_path": str(args.output_path),
        "config": {
            "seed": args.seed,
            "eval_ratio": args.eval_ratio,
            "max_train_triplets": args.max_train_triplets,
            "max_texts_per_anime": args.max_texts_per_anime,
            "min_texts_per_anime": args.min_texts_per_anime,
            "hard_neighbor_topk": args.hard_neighbor_topk,
            "labels_per_batch": args.labels_per_batch,
            "examples_per_label": args.examples_per_label,
            "steps_per_epoch": args.steps_per_epoch,
            "epochs": args.epochs,
            "lr": args.lr,
            "warmup_ratio": args.warmup_ratio,
            "triplet_margin": args.triplet_margin,
            "max_seq_length": args.max_seq_length,
        },
        "counts": {
            "all_triplets": len(all_triplets),
            "train_triplets": len(train_triplets),
            "eval_triplets": len(eval_triplets),
            "anime_labels": len(anime_to_label),
            "train_examples": len(examples),
        },
        "metrics": {
            "multipos_triplet_eval_cosine_accuracy": multipos_score,
            "baseline_triplet_eval_cosine_accuracy": baseline_score,
            "delta_vs_baseline": (multipos_score - baseline_score) if baseline_score is not None else None,
        },
    }
    with result_path.open("w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2)

    print(f"Multi-positive triplet eval cosine accuracy: {multipos_score:.6f}")
    if baseline_score is not None:
        delta = multipos_score - baseline_score
        print(f"Baseline triplet cosine accuracy: {baseline_score:.6f}")
        print(f"Delta vs baseline: {delta:+.6f}")
    print(f"Saved experiment snapshot: {result_path}")
    if not args.disable_eval_prune:
        pruned_count = prune_eval_snapshots(
            output_dir=args.eval_output_dir,
            pattern="semantic_multipos_experiment_*.json",
            keep_latest=args.eval_keep_latest,
            max_age_days=args.eval_max_age_days,
        )
        if pruned_count > 0:
            print(
                f"Pruned old semantic experiment snapshots: {pruned_count} "
                f"(keep_latest={args.eval_keep_latest}, max_age_days={args.eval_max_age_days})"
            )


if __name__ == "__main__":
    main()
