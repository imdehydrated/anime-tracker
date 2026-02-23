#!/usr/bin/env python3
"""Query-intent benchmark tests for semantic anime retrieval quality.

This complements profile-holdout offline metrics by testing direct
text-query behavior against a curated query->acceptable-title set.
"""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import numpy as np
from sentence_transformers import SentenceTransformer


TITLE_KEYS = ("title", "title_romaji", "title_english", "title_native")


@dataclass(frozen=True)
class CaseResult:
    query: str
    expected_ids: list[int]
    unresolved_titles: list[str]
    best_rank: int | None
    reciprocal_rank: float
    hit_at_k: bool
    top_results: list[dict[str, Any]]


def parse_args() -> argparse.Namespace:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description="Run query-intent semantic benchmark tests.")
    parser.add_argument(
        "--test-set-path",
        type=Path,
        default=root / "notebooks" / "eval" / "semantic_query_testset.json",
        help="Query benchmark definition JSON.",
    )
    parser.add_argument(
        "--model-path",
        type=str,
        default=str(root / "ml-models" / "semantic"),
        help="SentenceTransformer model used to encode queries.",
    )
    parser.add_argument(
        "--embeddings-path",
        type=Path,
        default=root / "ml-models" / "anime_embeddings.jsonl",
        help="Anime embedding JSONL used as retrieval index.",
    )
    parser.add_argument(
        "--top-k",
        type=int,
        default=10,
        help="Top-K cutoff used for hit/MRR.",
    )
    parser.add_argument(
        "--show-top-n",
        type=int,
        default=5,
        help="How many top results to print per query.",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=root / "notebooks" / "eval",
        help="Directory for benchmark snapshot JSON.",
    )
    parser.add_argument(
        "--eval-keep-latest",
        type=int,
        default=40,
        help="Always keep at least this many newest snapshots.",
    )
    parser.add_argument(
        "--eval-max-age-days",
        type=int,
        default=30,
        help="Delete snapshots older than this many days (beyond keep-latest).",
    )
    parser.add_argument(
        "--disable-eval-prune",
        action="store_true",
        help="Disable automatic pruning of old benchmark snapshots.",
    )
    return parser.parse_args()


def normalize_text(value: str) -> str:
    value = value.lower().strip()
    value = re.sub(r"[^a-z0-9]+", " ", value)
    value = re.sub(r"\s+", " ", value).strip()
    return value


def iter_aliases(row: dict[str, Any]) -> list[str]:
    aliases: list[str] = []
    for key in TITLE_KEYS:
        val = row.get(key)
        if isinstance(val, str) and val.strip():
            aliases.append(val.strip())
    synonyms = row.get("synonyms")
    if isinstance(synonyms, list):
        for item in synonyms:
            if isinstance(item, str) and item.strip():
                aliases.append(item.strip())
    return aliases


def load_embeddings(path: Path) -> tuple[np.ndarray, np.ndarray, list[str], dict[str, set[int]]]:
    anime_ids: list[int] = []
    titles: list[str] = []
    vectors: list[np.ndarray] = []
    title_to_ids: dict[str, set[int]] = {}

    with path.open("r", encoding="utf-8") as f:
        for line in f:
            if not line.strip():
                continue
            row = json.loads(line)
            aid = int(row["anilist_id"])
            vec = np.asarray(row["embedding"], dtype=np.float32)
            norm = float(np.linalg.norm(vec))
            if norm <= 0.0:
                continue
            vec = vec / norm
            anime_ids.append(aid)
            title = str(row.get("title") or f"anilist:{aid}")
            titles.append(title)
            vectors.append(vec)
            for alias in iter_aliases(row):
                alias_norm = normalize_text(alias)
                if alias_norm:
                    title_to_ids.setdefault(alias_norm, set()).add(aid)

    if not vectors:
        raise ValueError(f"No valid embeddings loaded from: {path}")
    return (
        np.asarray(anime_ids, dtype=np.int64),
        np.vstack(vectors).astype(np.float32),
        titles,
        title_to_ids,
    )


def load_test_set(path: Path) -> list[dict[str, Any]]:
    with path.open("r", encoding="utf-8") as f:
        payload = json.load(f)

    cases = payload.get("cases")
    if not isinstance(cases, list) or not cases:
        raise ValueError(f"Invalid test set: expected non-empty 'cases' list in {path}")

    valid_cases: list[dict[str, Any]] = []
    for i, case in enumerate(cases, start=1):
        if not isinstance(case, dict):
            continue
        query = case.get("query")
        if not isinstance(query, str) or not query.strip():
            raise ValueError(f"Case {i} missing non-empty 'query'")
        acceptable_titles = case.get("acceptable_titles", [])
        acceptable_ids = case.get("acceptable_anilist_ids", [])
        if not acceptable_titles and not acceptable_ids:
            raise ValueError(
                f"Case {i} must define at least one acceptable title or AniList ID"
            )
        valid_cases.append(case)
    return valid_cases


def resolve_expected_ids(
    case: dict[str, Any],
    title_to_ids: dict[str, set[int]],
) -> tuple[set[int], list[str]]:
    expected: set[int] = set()
    unresolved_titles: list[str] = []

    for raw_id in case.get("acceptable_anilist_ids", []):
        try:
            expected.add(int(raw_id))
        except (TypeError, ValueError):
            continue

    for title in case.get("acceptable_titles", []):
        if not isinstance(title, str):
            continue
        key = normalize_text(title)
        if not key:
            continue
        ids = title_to_ids.get(key)
        if ids:
            expected.update(ids)
        else:
            unresolved_titles.append(title)
    return expected, unresolved_titles


def rank_top_k(
    query_vec: np.ndarray,
    anime_ids: np.ndarray,
    titles: list[str],
    matrix: np.ndarray,
    top_k: int,
) -> tuple[list[dict[str, Any]], list[int]]:
    scores = matrix @ query_vec
    k = min(max(1, int(top_k)), len(scores))
    top_idx = np.argpartition(-scores, kth=k - 1)[:k]
    ordered_idx = top_idx[np.argsort(-scores[top_idx])]
    results: list[dict[str, Any]] = []
    ordered_ids: list[int] = []
    for rank, idx in enumerate(ordered_idx.tolist(), start=1):
        aid = int(anime_ids[idx])
        ordered_ids.append(aid)
        results.append(
            {
                "rank": rank,
                "anilist_id": aid,
                "title": titles[idx],
                "score": float(scores[idx]),
            }
        )
    return results, ordered_ids


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


def print_case(case_idx: int, case: CaseResult, show_top_n: int) -> None:
    print(f"\nQ{case_idx}: {case.query}")
    if case.expected_ids:
        print(f"  expected_ids: {len(case.expected_ids)} candidates")
    if case.unresolved_titles:
        print(f"  unresolved_titles: {case.unresolved_titles}")
    if case.best_rank is None:
        print("  result: MISS")
    else:
        print(
            f"  result: HIT (best_rank={case.best_rank}, "
            f"rr={case.reciprocal_rank:.4f})"
        )
    print("  top_results:")
    for row in case.top_results[: max(1, int(show_top_n))]:
        print(
            f"    {row['rank']:>2}. {row['title']} "
            f"(id={row['anilist_id']}, score={row['score']:.4f})"
        )


def main() -> None:
    args = parse_args()
    test_set_path = args.test_set_path.resolve()
    embeddings_path = args.embeddings_path.resolve()
    if not test_set_path.exists():
        raise FileNotFoundError(f"Test set not found: {test_set_path}")
    if not embeddings_path.exists():
        raise FileNotFoundError(f"Embeddings file not found: {embeddings_path}")

    cases = load_test_set(test_set_path)
    anime_ids, matrix, titles, title_to_ids = load_embeddings(embeddings_path)

    print(f"Loaded {len(cases)} benchmark cases from: {test_set_path}")
    print(f"Loaded {len(anime_ids)} anime embeddings from: {embeddings_path}")
    print(f"Loading model: {args.model_path}")
    model = SentenceTransformer(args.model_path)

    queries = [str(case["query"]).strip() for case in cases]
    query_vectors = model.encode(
        queries,
        normalize_embeddings=True,
        show_progress_bar=True,
        batch_size=32,
    )
    query_vectors = np.asarray(query_vectors, dtype=np.float32)

    per_case: list[CaseResult] = []
    hits = 0
    reciprocal_ranks: list[float] = []
    evaluated_count = 0
    skipped_count = 0

    for case, query_vec in zip(cases, query_vectors):
        expected_ids, unresolved_titles = resolve_expected_ids(case, title_to_ids)
        if not expected_ids:
            skipped_count += 1
            per_case.append(
                CaseResult(
                    query=str(case["query"]),
                    expected_ids=[],
                    unresolved_titles=unresolved_titles,
                    best_rank=None,
                    reciprocal_rank=0.0,
                    hit_at_k=False,
                    top_results=[],
                )
            )
            continue

        ranked_rows, ranked_ids = rank_top_k(
            query_vec=query_vec,
            anime_ids=anime_ids,
            titles=titles,
            matrix=matrix,
            top_k=int(args.top_k),
        )

        best_rank: int | None = None
        for idx, aid in enumerate(ranked_ids, start=1):
            if aid in expected_ids:
                best_rank = idx
                break

        hit = best_rank is not None
        rr = (1.0 / float(best_rank)) if best_rank is not None else 0.0
        if hit:
            hits += 1
        reciprocal_ranks.append(rr)
        evaluated_count += 1

        per_case.append(
            CaseResult(
                query=str(case["query"]),
                expected_ids=sorted(expected_ids),
                unresolved_titles=unresolved_titles,
                best_rank=best_rank,
                reciprocal_rank=rr,
                hit_at_k=hit,
                top_results=ranked_rows,
            )
        )

    for i, result in enumerate(per_case, start=1):
        print_case(i, result, show_top_n=int(args.show_top_n))

    hit_at_k = (float(hits) / float(evaluated_count)) if evaluated_count > 0 else 0.0
    mrr_at_k = float(np.mean(reciprocal_ranks)) if reciprocal_ranks else 0.0

    print("\nSemantic Query Benchmark Summary")
    print("=" * 72)
    print(f"evaluated_cases: {evaluated_count}")
    print(f"skipped_cases:   {skipped_count}")
    print(f"top_k:           {int(args.top_k)}")
    print(f"hit_at_k:        {hit_at_k:.4f}")
    print(f"mrr_at_k:        {mrr_at_k:.4f}")

    now = datetime.now(timezone.utc)
    ts = now.strftime("%Y%m%dT%H%M%SZ")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    output_path = args.output_dir / f"semantic_query_benchmark_{ts}.json"
    payload = {
        "generated_at_utc": now.isoformat(),
        "input": {
            "test_set_path": str(test_set_path),
            "model_path": str(args.model_path),
            "embeddings_path": str(embeddings_path),
            "top_k": int(args.top_k),
            "show_top_n": int(args.show_top_n),
            "total_cases": len(cases),
            "evaluated_cases": evaluated_count,
            "skipped_cases": skipped_count,
        },
        "summary": {
            "hit_at_k": round(hit_at_k, 6),
            "mrr_at_k": round(mrr_at_k, 6),
        },
        "cases": [
            {
                "query": x.query,
                "expected_ids": x.expected_ids,
                "unresolved_titles": x.unresolved_titles,
                "best_rank": x.best_rank,
                "reciprocal_rank": x.reciprocal_rank,
                "hit_at_k": x.hit_at_k,
                "top_results": x.top_results,
            }
            for x in per_case
        ],
    }

    with output_path.open("w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2)

    print(f"\nSaved benchmark snapshot: {output_path}")
    if not args.disable_eval_prune:
        pruned_count = prune_eval_snapshots(
            output_dir=args.output_dir,
            pattern="semantic_query_benchmark_*.json",
            keep_latest=args.eval_keep_latest,
            max_age_days=args.eval_max_age_days,
        )
        if pruned_count > 0:
            print(
                f"Pruned old query benchmark snapshots: {pruned_count} "
                f"(keep_latest={args.eval_keep_latest}, max_age_days={args.eval_max_age_days})"
            )


if __name__ == "__main__":
    main()
