#!/usr/bin/env python3
"""Summarize and rank offline eval snapshots for CF and semantic query benchmarks."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


CF_METRIC_KEYS = (
    "recall_at_k",
    "hit_rate_at_k",
    "ndcg_at_k",
    "coverage_at_k",
    "long_tail_share",
    "novelty",
)

SEMANTIC_QUERY_METRIC_KEYS = (
    "hit_at_k",
    "mrr_at_k",
)


def parse_args() -> argparse.Namespace:
    default_eval_dir = Path(__file__).resolve().parent / "eval"
    parser = argparse.ArgumentParser(
        description="Rank CF and semantic-query benchmark snapshots."
    )
    parser.add_argument("--eval-dir", type=Path, default=default_eval_dir)
    parser.add_argument("--mode", choices=("cf", "semantic", "both"), default="cf")
    parser.add_argument("--top-n", type=int, default=10)
    parser.add_argument(
        "--sort-key",
        choices=(
            "auto",
            "ndcg_at_k",
            "recall_at_k",
            "hit_rate_at_k",
            "coverage_at_k",
            "long_tail_share",
            "novelty",
            "hit_at_k",
            "mrr_at_k",
        ),
        default="auto",
    )

    parser.add_argument("--cf-pattern", type=str, default="baseline_metrics_*.json")
    parser.add_argument(
        "--semantic-pattern", type=str, default="semantic_query_benchmark_*.json"
    )
    parser.add_argument("--min-evaluated-users", type=int, default=100)
    parser.add_argument("--min-evaluated-cases", type=int, default=10)

    # Mode-specific baselines for delta reporting.
    parser.add_argument("--cf-baseline", type=Path, default=None)
    parser.add_argument("--semantic-baseline", type=Path, default=None)
    # Backward-compatible baseline flag (applies to selected single mode).
    parser.add_argument("--baseline", type=Path, default=None)

    parser.add_argument("--write-report", type=Path, default=None)
    return parser.parse_args()


def _load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def _flt(x: Any, default: float = 0.0) -> float:
    try:
        return float(x)
    except Exception:
        return default


def _extract_cf_row(path: Path, payload: dict[str, Any]) -> dict[str, Any] | None:
    section = payload.get("cf")
    if not isinstance(section, dict):
        return None

    exp = payload.get("experiment", {}) or {}
    inp = payload.get("input", {}) or {}

    row: dict[str, Any] = {
        "name": path.name,
        "path": str(path.resolve()),
        "generated_at_utc": payload.get("generated_at_utc"),
        "mode": "cf",
        "evaluated_users": int(section.get("evaluated_users", 0)),
        "label": exp.get("label"),
        "cf_popularity_alpha": inp.get("cf_popularity_alpha"),
        "cf_train_long_tail_alpha": exp.get("cf_train_long_tail_alpha"),
        "cf_train_max_pos_weight": exp.get("cf_train_max_pos_weight"),
        "cf_train_weak_negative_weight": exp.get("cf_train_weak_negative_weight"),
    }
    for key in CF_METRIC_KEYS:
        row[key] = _flt(section.get(key))
    return row


def _extract_semantic_query_row(path: Path, payload: dict[str, Any]) -> dict[str, Any] | None:
    summary = payload.get("summary")
    if not isinstance(summary, dict):
        return None

    inp = payload.get("input", {}) or {}
    row: dict[str, Any] = {
        "name": path.name,
        "path": str(path.resolve()),
        "generated_at_utc": payload.get("generated_at_utc"),
        "mode": "semantic",
        "evaluated_cases": int(inp.get("evaluated_cases", 0)),
        "total_cases": int(inp.get("total_cases", 0)),
        "top_k": int(inp.get("top_k", 0)),
        "model_path": inp.get("model_path"),
        "test_set_path": inp.get("test_set_path"),
        "hit_at_k": _flt(summary.get("hit_at_k")),
        "mrr_at_k": _flt(summary.get("mrr_at_k")),
    }
    return row


def _with_deltas(
    rows: list[dict[str, Any]], baseline: dict[str, Any] | None, metric_keys: tuple[str, ...]
) -> None:
    if baseline is None:
        return
    for row in rows:
        for key in metric_keys:
            row[f"delta_{key}"] = _flt(row.get(key)) - _flt(baseline.get(key))


def _sort_rows(rows: list[dict[str, Any]], sort_keys: tuple[str, ...]) -> list[dict[str, Any]]:
    return sorted(
        rows,
        key=lambda r: tuple(_flt(r.get(k)) for k in sort_keys),
        reverse=True,
    )


def _resolve_sort_keys(mode: str, sort_key: str) -> tuple[str, ...]:
    if mode == "cf":
        if sort_key == "auto":
            return (
                "ndcg_at_k",
                "recall_at_k",
                "hit_rate_at_k",
                "coverage_at_k",
                "long_tail_share",
                "novelty",
            )
        if sort_key in CF_METRIC_KEYS:
            return (
                sort_key,
                "ndcg_at_k",
                "recall_at_k",
                "hit_rate_at_k",
                "coverage_at_k",
                "long_tail_share",
                "novelty",
            )
        return (
            "ndcg_at_k",
            "recall_at_k",
            "hit_rate_at_k",
            "coverage_at_k",
            "long_tail_share",
            "novelty",
        )

    # Semantic query benchmark sorting.
    if sort_key == "auto":
        return ("mrr_at_k", "hit_at_k")
    if sort_key in SEMANTIC_QUERY_METRIC_KEYS:
        return (sort_key, "mrr_at_k", "hit_at_k")
    return ("mrr_at_k", "hit_at_k")


def _resolve_baseline_path(args: argparse.Namespace, mode: str) -> Path | None:
    if mode == "cf":
        if args.cf_baseline is not None:
            return args.cf_baseline.resolve()
        if args.baseline is not None and args.mode != "both":
            return args.baseline.resolve()
        return None
    if args.semantic_baseline is not None:
        return args.semantic_baseline.resolve()
    if args.baseline is not None and args.mode != "both":
        return args.baseline.resolve()
    return None


def _print_cf_rows(rows: list[dict[str, Any]], top_n: int, sort_key: str) -> None:
    print("\nCF Leaderboard")
    print("-" * 120)
    print(
        f"{'rank':<5} {'snapshot':<38} {'users':>6} {'ndcg':>8} {'recall':>8} "
        f"{'hit':>8} {'cov':>8} {'tail':>8} {'novelty':>9} {'alpha':>7} {'lt_a':>7} {'max_w':>7}"
    )
    for i, row in enumerate(rows[:top_n], start=1):
        print(
            f"{i:<5} "
            f"{row['name'][:38]:<38} "
            f"{int(row['evaluated_users']):>6} "
            f"{_flt(row['ndcg_at_k']):>8.4f} "
            f"{_flt(row['recall_at_k']):>8.4f} "
            f"{_flt(row['hit_rate_at_k']):>8.4f} "
            f"{_flt(row['coverage_at_k']):>8.4f} "
            f"{_flt(row['long_tail_share']):>8.4f} "
            f"{_flt(row['novelty']):>9.4f} "
            f"{_flt(row.get('cf_popularity_alpha')):>7.2f} "
            f"{_flt(row.get('cf_train_long_tail_alpha')):>7.2f} "
            f"{_flt(row.get('cf_train_max_pos_weight')):>7.2f}"
        )

    delta_key = sort_key if sort_key in CF_METRIC_KEYS else "ndcg_at_k"
    if rows and any(f"delta_{delta_key}" in r for r in rows):
        print("\nTop deltas vs baseline:")
        for i, row in enumerate(rows[:top_n], start=1):
            d = _flt(row.get(f"delta_{delta_key}"))
            print(f"{i:>2}. {row['name']}  delta_{delta_key}={d:+.6f}")


def _print_semantic_rows(rows: list[dict[str, Any]], top_n: int, sort_key: str) -> None:
    print("\nSemantic Query Leaderboard")
    print("-" * 120)
    print(
        f"{'rank':<5} {'snapshot':<42} {'cases':>7} {'hit@k':>10} {'mrr@k':>10} {'top_k':>6} {'model':<36}"
    )
    for i, row in enumerate(rows[:top_n], start=1):
        model_str = str(row.get("model_path") or "")
        print(
            f"{i:<5} "
            f"{row['name'][:42]:<42} "
            f"{int(row['evaluated_cases']):>7} "
            f"{_flt(row['hit_at_k']):>10.4f} "
            f"{_flt(row['mrr_at_k']):>10.4f} "
            f"{int(row['top_k']):>6} "
            f"{model_str[-36:]:<36}"
        )

    delta_key = sort_key if sort_key in SEMANTIC_QUERY_METRIC_KEYS else "mrr_at_k"
    if rows and any(f"delta_{delta_key}" in r for r in rows):
        print("\nTop deltas vs baseline:")
        for i, row in enumerate(rows[:top_n], start=1):
            d = _flt(row.get(f"delta_{delta_key}"))
            print(f"{i:>2}. {row['name']}  delta_{delta_key}={d:+.6f}")


def main() -> None:
    args = parse_args()
    eval_dir = args.eval_dir.resolve()
    if not eval_dir.exists():
        raise FileNotFoundError(f"Eval directory not found: {eval_dir}")

    modes = ("cf", "semantic") if args.mode == "both" else (args.mode,)
    report: dict[str, Any] = {"modes": {}}

    for mode in modes:
        baseline_row = None
        baseline_path = _resolve_baseline_path(args, mode)
        if mode == "cf":
            files = sorted(
                eval_dir.glob(args.cf_pattern),
                key=lambda p: p.stat().st_mtime,
                reverse=True,
            )
            rows = []
            for path in files:
                payload = _load_json(path)
                row = _extract_cf_row(path, payload)
                if row is None:
                    continue
                if int(row["evaluated_users"]) < int(args.min_evaluated_users):
                    continue
                rows.append(row)

            if baseline_path is not None:
                baseline_payload = _load_json(baseline_path)
                baseline_row = _extract_cf_row(Path("baseline"), baseline_payload)

            _with_deltas(rows, baseline_row, CF_METRIC_KEYS)
            sort_keys = _resolve_sort_keys("cf", args.sort_key)
            rows = _sort_rows(rows, sort_keys)
            _print_cf_rows(rows, top_n=int(args.top_n), sort_key=args.sort_key)
            report["modes"]["cf"] = rows[: int(args.top_n)]
        else:
            files = sorted(
                eval_dir.glob(args.semantic_pattern),
                key=lambda p: p.stat().st_mtime,
                reverse=True,
            )
            rows = []
            for path in files:
                payload = _load_json(path)
                row = _extract_semantic_query_row(path, payload)
                if row is None:
                    continue
                if int(row["evaluated_cases"]) < int(args.min_evaluated_cases):
                    continue
                rows.append(row)

            if baseline_path is not None:
                baseline_payload = _load_json(baseline_path)
                baseline_row = _extract_semantic_query_row(Path("baseline"), baseline_payload)

            _with_deltas(rows, baseline_row, SEMANTIC_QUERY_METRIC_KEYS)
            sort_keys = _resolve_sort_keys("semantic", args.sort_key)
            rows = _sort_rows(rows, sort_keys)
            _print_semantic_rows(rows, top_n=int(args.top_n), sort_key=args.sort_key)
            report["modes"]["semantic"] = rows[: int(args.top_n)]

    if args.write_report is not None:
        out = args.write_report.resolve()
        out.parent.mkdir(parents=True, exist_ok=True)
        with out.open("w", encoding="utf-8") as f:
            json.dump(report, f, indent=2)
        print(f"\nSaved leaderboard report: {out}")


if __name__ == "__main__":
    main()

