#!/usr/bin/env python3
"""Promotion gate for CF and semantic recommendation model updates.

Purpose:
- Compare CF eval snapshots (baseline vs candidate) using configurable thresholds.
- Compare semantic query-benchmark snapshots (baseline vs candidate) using Hit@K + MRR@K.
- Produce an explicit PASS/FAIL decision before promoting model changes.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class CfGateResult:
    passed: bool
    baseline_path: str
    candidate_path: str
    recall_delta: float
    ndcg_delta: float
    hit_rate_delta: float
    coverage_delta: float
    long_tail_delta: float
    novelty_delta: float
    notes: list[str]


@dataclass(frozen=True)
class SemanticQueryGateResult:
    passed: bool
    baseline_path: str
    candidate_path: str
    hit_at_k_delta: float
    mrr_at_k_delta: float
    baseline_hit_at_k: float
    candidate_hit_at_k: float
    baseline_mrr_at_k: float
    candidate_mrr_at_k: float
    target_hit_at_k_delta: float
    target_mrr_at_k_delta: float
    target_passed: bool
    notes: list[str]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Evaluate recommendation promotion gates.")
    default_eval_dir = Path(__file__).resolve().parent / "eval"
    parser.add_argument("--eval-dir", type=Path, default=default_eval_dir)

    # CF gate inputs/thresholds.
    parser.add_argument("--cf-baseline", type=Path, default=None)
    parser.add_argument("--cf-candidate", type=Path, default=None)
    parser.add_argument("--cf-min-recall-delta", type=float, default=0.0)
    parser.add_argument("--cf-min-ndcg-delta", type=float, default=0.0)
    parser.add_argument("--cf-min-hit-rate-delta", type=float, default=-0.002)
    parser.add_argument("--cf-min-coverage-delta", type=float, default=0.0)
    parser.add_argument("--cf-min-long-tail-delta", type=float, default=0.0)
    parser.add_argument("--cf-min-novelty-delta", type=float, default=0.0)

    # Semantic query gate inputs/thresholds.
    parser.add_argument("--semantic-baseline-benchmark", type=Path, default=None)
    parser.add_argument("--semantic-candidate-benchmark", type=Path, default=None)
    parser.add_argument("--semantic-min-hit-at-k-delta", type=float, default=0.0)
    parser.add_argument("--semantic-min-mrr-at-k-delta", type=float, default=0.0)
    parser.add_argument("--semantic-target-hit-at-k-delta", type=float, default=0.02)
    parser.add_argument("--semantic-target-mrr-at-k-delta", type=float, default=0.02)
    parser.add_argument("--semantic-min-evaluated-cases", type=int, default=10)

    parser.add_argument("--write-report", type=Path, default=None)
    parser.add_argument("--fail-on-reject", action="store_true")
    return parser.parse_args()


def _load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def _latest(eval_dir: Path, pattern: str) -> Path | None:
    files = sorted(eval_dir.glob(pattern), key=lambda p: p.stat().st_mtime, reverse=True)
    return files[0] if files else None


def _latest_two(eval_dir: Path, pattern: str) -> tuple[Path | None, Path | None]:
    files = sorted(eval_dir.glob(pattern), key=lambda p: p.stat().st_mtime, reverse=True)
    if not files:
        return None, None
    if len(files) == 1:
        return files[0], None
    return files[0], files[1]


def _latest_cf_alpha_zero(eval_dir: Path) -> Path | None:
    files = sorted(eval_dir.glob("baseline_metrics_*.json"), key=lambda p: p.stat().st_mtime, reverse=True)
    for path in files:
        try:
            payload = _load_json(path)
            alpha = float(payload.get("input", {}).get("cf_popularity_alpha", 0.0))
            if abs(alpha) < 1e-12:
                return path
        except Exception:
            continue
    return None


def _metric(payload: dict[str, Any], section: str, key: str) -> float:
    value = payload.get(section, {}).get(key, 0.0)
    try:
        return float(value)
    except Exception:
        return 0.0


def _semantic_summary_metric(payload: dict[str, Any], key: str) -> float:
    value = payload.get("summary", {}).get(key, 0.0)
    try:
        return float(value)
    except Exception:
        return 0.0


def run_cf_gate(args: argparse.Namespace) -> CfGateResult | None:
    baseline_path = args.cf_baseline or _latest_cf_alpha_zero(args.eval_dir)
    candidate_path = args.cf_candidate or _latest(args.eval_dir, "baseline_metrics_*.json")
    if baseline_path is None or candidate_path is None:
        return None

    baseline_path = baseline_path.resolve()
    candidate_path = candidate_path.resolve()

    b = _load_json(baseline_path)
    c = _load_json(candidate_path)
    notes: list[str] = []

    b_thresh = b.get("input", {}).get("relevance_threshold")
    c_thresh = c.get("input", {}).get("relevance_threshold")
    if b_thresh != c_thresh:
        notes.append(
            f"Warning: relevance_threshold mismatch (baseline={b_thresh}, candidate={c_thresh})."
        )

    recall_delta = _metric(c, "cf", "recall_at_k") - _metric(b, "cf", "recall_at_k")
    ndcg_delta = _metric(c, "cf", "ndcg_at_k") - _metric(b, "cf", "ndcg_at_k")
    hit_rate_delta = _metric(c, "cf", "hit_rate_at_k") - _metric(b, "cf", "hit_rate_at_k")
    coverage_delta = _metric(c, "cf", "coverage_at_k") - _metric(b, "cf", "coverage_at_k")
    long_tail_delta = _metric(c, "cf", "long_tail_share") - _metric(b, "cf", "long_tail_share")
    novelty_delta = _metric(c, "cf", "novelty") - _metric(b, "cf", "novelty")

    passed = True
    if recall_delta < float(args.cf_min_recall_delta):
        passed = False
        notes.append(
            f"Fail: recall delta {recall_delta:+.6f} < min {float(args.cf_min_recall_delta):+.6f}"
        )
    if ndcg_delta < float(args.cf_min_ndcg_delta):
        passed = False
        notes.append(
            f"Fail: ndcg delta {ndcg_delta:+.6f} < min {float(args.cf_min_ndcg_delta):+.6f}"
        )
    if hit_rate_delta < float(args.cf_min_hit_rate_delta):
        passed = False
        notes.append(
            f"Fail: hit-rate delta {hit_rate_delta:+.6f} < min {float(args.cf_min_hit_rate_delta):+.6f}"
        )
    if coverage_delta < float(args.cf_min_coverage_delta):
        passed = False
        notes.append(
            f"Fail: coverage delta {coverage_delta:+.6f} < min {float(args.cf_min_coverage_delta):+.6f}"
        )
    if long_tail_delta < float(args.cf_min_long_tail_delta):
        passed = False
        notes.append(
            f"Fail: long-tail delta {long_tail_delta:+.6f} < min {float(args.cf_min_long_tail_delta):+.6f}"
        )
    if novelty_delta < float(args.cf_min_novelty_delta):
        passed = False
        notes.append(
            f"Fail: novelty delta {novelty_delta:+.6f} < min {float(args.cf_min_novelty_delta):+.6f}"
        )

    return CfGateResult(
        passed=passed,
        baseline_path=str(baseline_path),
        candidate_path=str(candidate_path),
        recall_delta=recall_delta,
        ndcg_delta=ndcg_delta,
        hit_rate_delta=hit_rate_delta,
        coverage_delta=coverage_delta,
        long_tail_delta=long_tail_delta,
        novelty_delta=novelty_delta,
        notes=notes,
    )


def run_semantic_query_gate(args: argparse.Namespace) -> SemanticQueryGateResult | None:
    latest_candidate, latest_baseline = _latest_two(args.eval_dir, "semantic_query_benchmark_*.json")
    candidate_path = args.semantic_candidate_benchmark or latest_candidate
    baseline_path = args.semantic_baseline_benchmark or latest_baseline
    if candidate_path is None or baseline_path is None:
        return None

    candidate_path = candidate_path.resolve()
    baseline_path = baseline_path.resolve()
    c = _load_json(candidate_path)
    b = _load_json(baseline_path)
    notes: list[str] = []

    candidate_cases = int(c.get("input", {}).get("evaluated_cases", 0))
    baseline_cases = int(b.get("input", {}).get("evaluated_cases", 0))
    min_cases = int(args.semantic_min_evaluated_cases)
    if candidate_cases < min_cases:
        notes.append(
            f"Fail: candidate evaluated_cases={candidate_cases} < min {min_cases}."
        )
    if baseline_cases < min_cases:
        notes.append(
            f"Fail: baseline evaluated_cases={baseline_cases} < min {min_cases}."
        )

    cand_test_set = c.get("input", {}).get("test_set_path")
    base_test_set = b.get("input", {}).get("test_set_path")
    if cand_test_set != base_test_set:
        notes.append(
            f"Warning: test_set_path mismatch (baseline={base_test_set}, candidate={cand_test_set})."
        )

    candidate_hit = _semantic_summary_metric(c, "hit_at_k")
    baseline_hit = _semantic_summary_metric(b, "hit_at_k")
    candidate_mrr = _semantic_summary_metric(c, "mrr_at_k")
    baseline_mrr = _semantic_summary_metric(b, "mrr_at_k")

    hit_delta = candidate_hit - baseline_hit
    mrr_delta = candidate_mrr - baseline_mrr
    target_hit = float(args.semantic_target_hit_at_k_delta)
    target_mrr = float(args.semantic_target_mrr_at_k_delta)
    target_passed = hit_delta >= target_hit and mrr_delta >= target_mrr

    passed = True
    if candidate_cases < min_cases or baseline_cases < min_cases:
        passed = False
    if hit_delta < float(args.semantic_min_hit_at_k_delta):
        passed = False
        notes.append(
            f"Fail: hit@k delta {hit_delta:+.6f} < min {float(args.semantic_min_hit_at_k_delta):+.6f}"
        )
    if mrr_delta < float(args.semantic_min_mrr_at_k_delta):
        passed = False
        notes.append(
            f"Fail: mrr@k delta {mrr_delta:+.6f} < min {float(args.semantic_min_mrr_at_k_delta):+.6f}"
        )
    if not target_passed:
        notes.append(
            "Target not met: "
            f"hit@k delta {hit_delta:+.6f} (target {target_hit:+.6f}), "
            f"mrr@k delta {mrr_delta:+.6f} (target {target_mrr:+.6f})"
        )

    return SemanticQueryGateResult(
        passed=passed,
        baseline_path=str(baseline_path),
        candidate_path=str(candidate_path),
        hit_at_k_delta=hit_delta,
        mrr_at_k_delta=mrr_delta,
        baseline_hit_at_k=baseline_hit,
        candidate_hit_at_k=candidate_hit,
        baseline_mrr_at_k=baseline_mrr,
        candidate_mrr_at_k=candidate_mrr,
        target_hit_at_k_delta=target_hit,
        target_mrr_at_k_delta=target_mrr,
        target_passed=target_passed,
        notes=notes,
    )


def _print_cf(result: CfGateResult | None) -> None:
    if result is None:
        print("CF Gate: SKIPPED (missing baseline/candidate snapshots)")
        return
    print("\nCF Gate")
    print("-" * 60)
    print(f"Baseline : {result.baseline_path}")
    print(f"Candidate: {result.candidate_path}")
    print(f"Recall delta    : {result.recall_delta:+.6f}")
    print(f"NDCG delta      : {result.ndcg_delta:+.6f}")
    print(f"HitRate delta   : {result.hit_rate_delta:+.6f}")
    print(f"Coverage delta  : {result.coverage_delta:+.6f}")
    print(f"Long-tail delta : {result.long_tail_delta:+.6f}")
    print(f"Novelty delta   : {result.novelty_delta:+.6f}")
    print(f"Decision: {'PASS' if result.passed else 'FAIL'}")
    for note in result.notes:
        print(f"- {note}")


def _print_semantic(result: SemanticQueryGateResult | None) -> None:
    if result is None:
        print("Semantic Query Gate: SKIPPED (need baseline + candidate benchmark snapshots)")
        return
    print("\nSemantic Query Gate")
    print("-" * 60)
    print(f"Baseline : {result.baseline_path}")
    print(f"Candidate: {result.candidate_path}")
    print(f"Baseline hit@k : {result.baseline_hit_at_k:.6f}")
    print(f"Candidate hit@k: {result.candidate_hit_at_k:.6f}")
    print(f"Delta hit@k    : {result.hit_at_k_delta:+.6f}")
    print(f"Baseline mrr@k : {result.baseline_mrr_at_k:.6f}")
    print(f"Candidate mrr@k: {result.candidate_mrr_at_k:.6f}")
    print(f"Delta mrr@k    : {result.mrr_at_k_delta:+.6f}")
    print(f"Decision: {'PASS' if result.passed else 'FAIL'}")
    print(
        "Operational target: "
        f"{'MET' if result.target_passed else 'NOT MET'} "
        f"(hit@k>={result.target_hit_at_k_delta:+.6f}, "
        f"mrr@k>={result.target_mrr_at_k_delta:+.6f})"
    )
    for note in result.notes:
        print(f"- {note}")


def main() -> None:
    args = parse_args()
    eval_dir = args.eval_dir.resolve()
    if not eval_dir.exists():
        raise FileNotFoundError(f"Eval directory not found: {eval_dir}")
    args.eval_dir = eval_dir

    cf_result = run_cf_gate(args)
    semantic_result = run_semantic_query_gate(args)
    _print_cf(cf_result)
    _print_semantic(semantic_result)

    overall_pass = True
    if cf_result is not None and not cf_result.passed:
        overall_pass = False
    if semantic_result is not None and not semantic_result.passed:
        overall_pass = False
    print("\nOverall Gate:", "PASS" if overall_pass else "FAIL")

    report = {
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "overall_pass": overall_pass,
        "cf": None
        if cf_result is None
        else {
            "passed": cf_result.passed,
            "baseline_path": cf_result.baseline_path,
            "candidate_path": cf_result.candidate_path,
            "deltas": {
                "recall": cf_result.recall_delta,
                "ndcg": cf_result.ndcg_delta,
                "hit_rate": cf_result.hit_rate_delta,
                "coverage": cf_result.coverage_delta,
                "long_tail_share": cf_result.long_tail_delta,
                "novelty": cf_result.novelty_delta,
            },
            "notes": cf_result.notes,
        },
        "semantic_query": None
        if semantic_result is None
        else {
            "passed": semantic_result.passed,
            "baseline_path": semantic_result.baseline_path,
            "candidate_path": semantic_result.candidate_path,
            "baseline_hit_at_k": semantic_result.baseline_hit_at_k,
            "candidate_hit_at_k": semantic_result.candidate_hit_at_k,
            "delta_hit_at_k": semantic_result.hit_at_k_delta,
            "baseline_mrr_at_k": semantic_result.baseline_mrr_at_k,
            "candidate_mrr_at_k": semantic_result.candidate_mrr_at_k,
            "delta_mrr_at_k": semantic_result.mrr_at_k_delta,
            "target_hit_at_k_delta": semantic_result.target_hit_at_k_delta,
            "target_mrr_at_k_delta": semantic_result.target_mrr_at_k_delta,
            "target_passed": semantic_result.target_passed,
            "notes": semantic_result.notes,
        },
        "thresholds": {
            "cf_min_recall_delta": args.cf_min_recall_delta,
            "cf_min_ndcg_delta": args.cf_min_ndcg_delta,
            "cf_min_hit_rate_delta": args.cf_min_hit_rate_delta,
            "cf_min_coverage_delta": args.cf_min_coverage_delta,
            "cf_min_long_tail_delta": args.cf_min_long_tail_delta,
            "cf_min_novelty_delta": args.cf_min_novelty_delta,
            "semantic_min_hit_at_k_delta": args.semantic_min_hit_at_k_delta,
            "semantic_min_mrr_at_k_delta": args.semantic_min_mrr_at_k_delta,
            "semantic_target_hit_at_k_delta": args.semantic_target_hit_at_k_delta,
            "semantic_target_mrr_at_k_delta": args.semantic_target_mrr_at_k_delta,
            "semantic_min_evaluated_cases": args.semantic_min_evaluated_cases,
        },
    }

    if args.write_report is not None:
        out = args.write_report.resolve()
        out.parent.mkdir(parents=True, exist_ok=True)
        with out.open("w", encoding="utf-8") as f:
            json.dump(report, f, indent=2)
        print(f"Saved gate report: {out}")

    if args.fail_on_reject and not overall_pass:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
