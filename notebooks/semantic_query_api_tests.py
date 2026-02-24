#!/usr/bin/env python3
"""Production-path semantic query benchmark against backend API."""

from __future__ import annotations

import argparse
import json
import re
import time
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any
from urllib import error, request


TITLE_KEYS = ("title", "title_romaji", "title_english", "title_native")


@dataclass(frozen=True)
class CaseResult:
    query: str
    cluster: str
    expected_ids: list[int]
    unresolved_titles: list[str]
    expected_in_index: list[int]
    top_ids: list[int]
    top_results: list[dict[str, Any]]
    best_rank: int | None
    reciprocal_rank: float
    hit_at_k: bool
    miss_reason: str | None
    latency_ms: float


def parse_args() -> argparse.Namespace:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description="Run semantic benchmark against backend API.")
    parser.add_argument(
        "--test-set-path",
        type=Path,
        default=root / "notebooks" / "eval" / "semantic_query_testset.json",
    )
    parser.add_argument(
        "--embeddings-path",
        type=Path,
        default=root / "ml-models" / "anime_embeddings.jsonl",
        help="Used for expected-title mapping and catalog coverage classification.",
    )
    parser.add_argument(
        "--endpoint",
        type=str,
        default="http://localhost:8080/api/users/recommendations/semantic/scored",
    )
    parser.add_argument(
        "--top-k",
        type=int,
        default=10,
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=10,
    )
    parser.add_argument(
        "--timeout-seconds",
        type=float,
        default=15.0,
    )
    parser.add_argument(
        "--bearer-token",
        type=str,
        default="",
        help="Optional JWT for authenticated environments.",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=root / "notebooks" / "eval",
    )
    parser.add_argument(
        "--eval-keep-latest",
        type=int,
        default=40,
    )
    parser.add_argument(
        "--eval-max-age-days",
        type=int,
        default=30,
    )
    parser.add_argument(
        "--disable-eval-prune",
        action="store_true",
    )
    return parser.parse_args()


def normalize_text(value: str) -> str:
    value = value.lower().strip()
    value = re.sub(r"[^a-z0-9]+", " ", value)
    value = re.sub(r"\s+", " ", value).strip()
    return value


def infer_query_cluster(query: str) -> str:
    q = normalize_text(query)
    if any(token in q for token in ("romance", "romantic", "tsundere")):
        return "romance"
    if any(token in q for token in ("mecha", "robot", "cyberpunk", "space")):
        return "sci_fi"
    if any(token in q for token in ("slice of life", "wholesome", "healing", "camping")):
        return "slice_of_life"
    if any(token in q for token in ("thriller", "mystery", "psychological", "mind games")):
        return "thriller"
    if any(token in q for token in ("sports", "basketball", "volleyball")):
        return "sports"
    if any(token in q for token in ("isekai", "fantasy", "magic")):
        return "fantasy"
    return "mixed"


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


def load_title_map(path: Path) -> tuple[dict[str, set[int]], set[int]]:
    title_to_ids: dict[str, set[int]] = {}
    all_ids: set[int] = set()
    with path.open("r", encoding="utf-8") as f:
        for line in f:
            if not line.strip():
                continue
            row = json.loads(line)
            aid = int(row["anilist_id"])
            all_ids.add(aid)
            for alias in iter_aliases(row):
                key = normalize_text(alias)
                if key:
                    title_to_ids.setdefault(key, set()).add(aid)
    return title_to_ids, all_ids


def load_test_set(path: Path) -> list[dict[str, Any]]:
    with path.open("r", encoding="utf-8") as f:
        payload = json.load(f)
    cases = payload.get("cases")
    if not isinstance(cases, list) or not cases:
        raise ValueError(f"Invalid test set: {path}")
    return cases


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

    for raw_title in case.get("acceptable_titles", []):
        if not isinstance(raw_title, str):
            continue
        key = normalize_text(raw_title)
        if not key:
            continue
        ids = title_to_ids.get(key)
        if ids:
            expected.update(ids)
        else:
            unresolved_titles.append(raw_title)
    return expected, unresolved_titles


def post_json(url: str, payload: dict[str, Any], timeout: float, bearer_token: str) -> tuple[Any, float]:
    body = json.dumps(payload).encode("utf-8")
    req = request.Request(url=url, method="POST", data=body)
    req.add_header("Content-Type", "application/json")
    if bearer_token:
        req.add_header("Authorization", f"Bearer {bearer_token}")
    t0 = time.perf_counter()
    try:
        with request.urlopen(req, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
            elapsed_ms = (time.perf_counter() - t0) * 1000.0
            return json.loads(raw), elapsed_ms
    except error.HTTPError as exc:
        details = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {exc.code}: {details}") from exc
    except error.URLError as exc:
        raise RuntimeError(f"Failed to call backend endpoint: {exc.reason}") from exc


def extract_results(payload: Any) -> list[dict[str, Any]]:
    if not isinstance(payload, list):
        return []
    rows: list[dict[str, Any]] = []
    for item in payload:
        if not isinstance(item, dict):
            continue
        anime = item.get("anime")
        if isinstance(anime, dict) and isinstance(anime.get("id"), int):
            title = ""
            title_obj = anime.get("title")
            if isinstance(title_obj, dict):
                title = str(title_obj.get("english") or title_obj.get("romaji") or "")
            rows.append(
                {
                    "anilist_id": int(anime["id"]),
                    "title": title,
                    "score": float(item.get("fusionScore", 0.0)),
                }
            )
            continue
        if isinstance(item.get("id"), int):
            title = ""
            title_obj = item.get("title")
            if isinstance(title_obj, dict):
                title = str(title_obj.get("english") or title_obj.get("romaji") or "")
            rows.append(
                {
                    "anilist_id": int(item["id"]),
                    "title": title,
                    "score": 0.0,
                }
            )
    return rows


def prune_eval_snapshots(output_dir: Path, pattern: str, keep_latest: int, max_age_days: int) -> int:
    keep_latest = max(0, int(keep_latest))
    max_age_days = max(0, int(max_age_days))
    files = sorted(output_dir.glob(pattern), key=lambda p: p.stat().st_mtime, reverse=True)
    cutoff_epoch = (datetime.now(timezone.utc) - timedelta(days=max_age_days)).timestamp()
    deleted = 0
    for idx, path in enumerate(files):
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
    test_set_path = args.test_set_path.resolve()
    embeddings_path = args.embeddings_path.resolve()
    if not test_set_path.exists():
        raise FileNotFoundError(f"Test set not found: {test_set_path}")
    if not embeddings_path.exists():
        raise FileNotFoundError(f"Embeddings file not found: {embeddings_path}")

    title_to_ids, index_ids = load_title_map(embeddings_path)
    cases = load_test_set(test_set_path)

    per_case: list[CaseResult] = []
    reciprocal_ranks: list[float] = []
    hits = 0

    for case in cases:
        query = str(case.get("query", "")).strip()
        if not query:
            continue
        expected_ids, unresolved_titles = resolve_expected_ids(case, title_to_ids)
        expected_in_index = sorted([aid for aid in expected_ids if aid in index_ids])

        payload = {
            "query": query,
            "seedIds": [],
            "limit": int(args.limit),
            "mode": "semantic",
        }
        response_payload, latency_ms = post_json(
            url=args.endpoint,
            payload=payload,
            timeout=float(args.timeout_seconds),
            bearer_token=args.bearer_token.strip(),
        )
        rows = extract_results(response_payload)[: max(1, int(args.top_k))]
        ranked_ids = [int(r["anilist_id"]) for r in rows]

        best_rank: int | None = None
        for idx, aid in enumerate(ranked_ids, start=1):
            if aid in expected_ids:
                best_rank = idx
                break
        hit = best_rank is not None
        rr = 1.0 / float(best_rank) if best_rank is not None else 0.0
        if hit:
            hits += 1
        reciprocal_ranks.append(rr)

        miss_reason: str | None = None
        if not hit:
            if unresolved_titles:
                miss_reason = "alias_miss"
            elif not expected_in_index:
                miss_reason = "catalog_miss"
            else:
                miss_reason = "model_miss"

        per_case.append(
            CaseResult(
                query=query,
                cluster=infer_query_cluster(query),
                expected_ids=sorted(expected_ids),
                unresolved_titles=unresolved_titles,
                expected_in_index=expected_in_index,
                top_ids=ranked_ids,
                top_results=rows,
                best_rank=best_rank,
                reciprocal_rank=rr,
                hit_at_k=hit,
                miss_reason=miss_reason,
                latency_ms=latency_ms,
            )
        )

    evaluated = len(per_case)
    hit_at_k = float(hits) / float(evaluated) if evaluated > 0 else 0.0
    mrr_at_k = float(sum(reciprocal_ranks) / float(len(reciprocal_ranks))) if reciprocal_ranks else 0.0
    miss_reason_counts: dict[str, int] = {}
    miss_cluster_counts: dict[str, int] = {}
    for case in per_case:
        if case.miss_reason:
            miss_reason_counts[case.miss_reason] = miss_reason_counts.get(case.miss_reason, 0) + 1
            miss_cluster_counts[case.cluster] = miss_cluster_counts.get(case.cluster, 0) + 1

    avg_latency_ms = (
        float(sum(case.latency_ms for case in per_case) / float(evaluated))
        if evaluated > 0
        else 0.0
    )

    now = datetime.now(timezone.utc)
    ts = now.strftime("%Y%m%dT%H%M%SZ")
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    output_path = output_dir / f"semantic_query_api_benchmark_{ts}.json"
    payload = {
        "generated_at_utc": now.isoformat(),
        "input": {
            "test_set_path": str(test_set_path),
            "embeddings_path": str(embeddings_path),
            "endpoint": args.endpoint,
            "top_k": int(args.top_k),
            "limit": int(args.limit),
            "evaluated_cases": evaluated,
        },
        "summary": {
            "hit_at_k": round(hit_at_k, 6),
            "mrr_at_k": round(mrr_at_k, 6),
            "avg_latency_ms": round(avg_latency_ms, 3),
            "miss_reason_counts": miss_reason_counts,
            "miss_cluster_counts": miss_cluster_counts,
        },
        "cases": [
            {
                "query": x.query,
                "cluster": x.cluster,
                "expected_ids": x.expected_ids,
                "expected_in_index": x.expected_in_index,
                "unresolved_titles": x.unresolved_titles,
                "top_ids": x.top_ids,
                "best_rank": x.best_rank,
                "reciprocal_rank": x.reciprocal_rank,
                "hit_at_k": x.hit_at_k,
                "miss_reason": x.miss_reason,
                "latency_ms": x.latency_ms,
                "top_results": x.top_results,
            }
            for x in per_case
        ],
    }
    with output_path.open("w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2)

    print("Production Semantic Query Benchmark Summary")
    print("=" * 72)
    print(f"evaluated_cases: {evaluated}")
    print(f"hit_at_k:        {hit_at_k:.4f}")
    print(f"mrr_at_k:        {mrr_at_k:.4f}")
    print(f"avg_latency_ms:  {avg_latency_ms:.2f}")
    print(f"miss_reason_counts: {miss_reason_counts}")
    print(f"miss_cluster_counts: {miss_cluster_counts}")
    print(f"Saved benchmark snapshot: {output_path}")

    if not args.disable_eval_prune:
        pruned = prune_eval_snapshots(
            output_dir=output_dir,
            pattern="semantic_query_api_benchmark_*.json",
            keep_latest=args.eval_keep_latest,
            max_age_days=args.eval_max_age_days,
        )
        if pruned > 0:
            print(
                f"Pruned old API benchmark snapshots: {pruned} "
                f"(keep_latest={args.eval_keep_latest}, max_age_days={args.eval_max_age_days})"
            )


if __name__ == "__main__":
    main()
