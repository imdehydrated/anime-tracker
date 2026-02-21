#!/usr/bin/env python3
"""Summarize and rank offline eval snapshots for A/B tuning."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


METRIC_KEYS = (
    "recall_at_k",
    "hit_rate_at_k",
    "ndcg_at_k",
    "coverage_at_k",
    "long_tail_share",
    "novelty",
)


def parse_args() -> argparse.Namespace:
    default_eval_dir = Path(__file__).resolve().parent / "eval"
    parser = argparse.ArgumentParser(description="Rank baseline_metrics snapshots.")
    parser.add_argument("--eval-dir", type=Path, default=default_eval_dir)
    parser.add_argument("--pattern", type=str, default="baseline_metrics_*.json")
    parser.add_argument("--mode", choices=("cf", "semantic", "both"), default="cf")
    parser.add_argument("--top-n", type=int, default=10)
    parser.add_argument("--min-evaluated-users", type=int, default=100)
    parser.add_argument(
        "--sort-key",
        choices=("ndcg_at_k", "recall_at_k", "hit_rate_at_k", "coverage_at_k", "long_tail_share", "novelty"),
        default="ndcg_at_k",
    )
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


def _extract_row(path: Path, mode: str, payload: dict[str, Any]) -> dict[str, Any] | None:
    section = payload.get(mode)
    if not isinstance(section, dict):
        return None
    exp = payload.get("experiment", {}) or {}
    inp = payload.get("input", {}) or {}

    row: dict[str, Any] = {
        "name": path.name,
        "path": str(path.resolve()),
        "generated_at_utc": payload.get("generated_at_utc"),
        "mode": mode,
        "evaluated_users": int(section.get("evaluated_users", 0)),
        "label": exp.get("label"),
        "cf_popularity_alpha": inp.get("cf_popularity_alpha"),
        "cf_train_long_tail_alpha": exp.get("cf_train_long_tail_alpha"),
        "cf_train_max_pos_weight": exp.get("cf_train_max_pos_weight"),
        "cf_train_weak_negative_weight": exp.get("cf_train_weak_negative_weight"),
    }
    for key in METRIC_KEYS:
        row[key] = _flt(section.get(key))
    return row


def _with_deltas(rows: list[dict[str, Any]], baseline: dict[str, Any] | None) -> None:
    if baseline is None:
        return
    for row in rows:
        for key in METRIC_KEYS:
            row[f"delta_{key}"] = _flt(row.get(key)) - _flt(baseline.get(key))


def _print_rows(rows: list[dict[str, Any]], mode: str, top_n: int, sort_key: str) -> None:
    print(f"\n{mode.upper()} Leaderboard")
    print("-" * 120)
    header = (
        f"{'rank':<5} {'snapshot':<38} {'users':>6} {'ndcg':>8} {'recall':>8} "
        f"{'hit':>8} {'cov':>8} {'tail':>8} {'novelty':>9} {'alpha':>7} {'lt_a':>7} {'max_w':>7}"
    )
    print(header)
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

    if rows and any(f"delta_{sort_key}" in r for r in rows):
        print("\nTop deltas vs baseline:")
        for i, row in enumerate(rows[:top_n], start=1):
            d = _flt(row.get(f"delta_{sort_key}"))
            print(f"{i:>2}. {row['name']}  delta_{sort_key}={d:+.6f}")


def _sort_rows(rows: list[dict[str, Any]], sort_key: str) -> list[dict[str, Any]]:
    return sorted(
        rows,
        key=lambda r: (
            _flt(r.get(sort_key)),
            _flt(r.get("ndcg_at_k")),
            _flt(r.get("recall_at_k")),
            _flt(r.get("hit_rate_at_k")),
            _flt(r.get("coverage_at_k")),
            _flt(r.get("long_tail_share")),
            _flt(r.get("novelty")),
        ),
        reverse=True,
    )


def main() -> None:
    args = parse_args()
    eval_dir = args.eval_dir.resolve()
    if not eval_dir.exists():
        raise FileNotFoundError(f"Eval directory not found: {eval_dir}")

    modes = ("cf", "semantic") if args.mode == "both" else (args.mode,)
    files = sorted(eval_dir.glob(args.pattern), key=lambda p: p.stat().st_mtime, reverse=True)
    payloads = [(path, _load_json(path)) for path in files]

    baseline_payload = None
    if args.baseline is not None:
        baseline_path = args.baseline.resolve()
        baseline_payload = _load_json(baseline_path)

    report: dict[str, Any] = {"modes": {}}

    for mode in modes:
        rows: list[dict[str, Any]] = []
        for path, payload in payloads:
            row = _extract_row(path, mode, payload)
            if row is None:
                continue
            if int(row["evaluated_users"]) < int(args.min_evaluated_users):
                continue
            rows.append(row)

        baseline_row = None
        if baseline_payload is not None:
            baseline_row = _extract_row(Path("baseline"), mode, baseline_payload)
        _with_deltas(rows, baseline_row)
        rows = _sort_rows(rows, args.sort_key)
        _print_rows(rows, mode=mode, top_n=int(args.top_n), sort_key=args.sort_key)

        report["modes"][mode] = rows[: int(args.top_n)]

    if args.write_report is not None:
        out = args.write_report.resolve()
        out.parent.mkdir(parents=True, exist_ok=True)
        with out.open("w", encoding="utf-8") as f:
            json.dump(report, f, indent=2)
        print(f"\nSaved leaderboard report: {out}")


if __name__ == "__main__":
    main()

