#!/usr/bin/env python3
"""Materialize notebook metadata inputs from catalog snapshot artifacts.

Purpose:
- Use SP11 catalog snapshot outputs as notebook metadata source-of-truth.
- Generate `notebooks/data/anilist_anime.jsonl` in the schema expected by Notebook 02/05.
- Emit reproducibility fingerprint metadata for downstream experiment tracking.
"""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def parse_args() -> argparse.Namespace:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(
        description="Convert catalog snapshot JSONL into notebook metadata JSONL.")
    parser.add_argument(
        "--snapshot-dir",
        type=Path,
        default=None,
        help="Snapshot directory containing anime_catalog.jsonl and snapshot_manifest.json.",
    )
    parser.add_argument(
        "--snapshot-manifest",
        type=Path,
        default=None,
        help="Explicit snapshot manifest path (overrides --snapshot-dir manifest).",
    )
    parser.add_argument(
        "--catalog-jsonl",
        type=Path,
        default=None,
        help="Explicit anime_catalog JSONL path (overrides snapshot-dir default).",
    )
    parser.add_argument(
        "--output-path",
        type=Path,
        default=root / "notebooks" / "data" / "anilist_anime.jsonl",
        help="Output notebook metadata JSONL path.",
    )
    parser.add_argument(
        "--fingerprint-output",
        type=Path,
        default=root / "notebooks" / "data" / "catalog_snapshot_fingerprint.json",
        help="Output fingerprint/manifest JSON for reproducibility.",
    )
    return parser.parse_args()


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8-sig") as f:
        return json.load(f)


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8-sig") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            rows.append(json.loads(line))
    return rows


def normalize_text(value: Any) -> str | None:
    if isinstance(value, str):
        text = value.strip()
        return text or None
    return None


def normalize_str_list(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    out: list[str] = []
    for item in value:
        if isinstance(item, str):
            text = item.strip()
            if text:
                out.append(text)
    return out


def normalize_tags(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    out: list[dict[str, Any]] = []
    for item in value:
        if isinstance(item, dict):
            name = normalize_text(item.get("name"))
            if not name:
                continue
            out.append({
                "id": item.get("id"),
                "name": name,
                "rank": item.get("rank"),
                "category": normalize_text(item.get("category")),
                "isAdult": bool(item.get("isAdult")) if item.get("isAdult") is not None else None,
            })
        elif isinstance(item, str):
            name = item.strip()
            if not name:
                continue
            out.append({"name": name})
    return out


def normalize_studios(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    out: list[str] = []
    for item in value:
        if isinstance(item, str):
            text = item.strip()
            if text:
                out.append(text)
        elif isinstance(item, dict):
            name = normalize_text(item.get("name"))
            if name:
                out.append(name)
    return out


def normalize_relations(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    out: list[dict[str, Any]] = []
    for item in value:
        if isinstance(item, dict):
            rel_id = item.get("id") or item.get("anilist_id")
            if not isinstance(rel_id, int) or rel_id <= 0:
                continue
            out.append({
                "id": rel_id,
                "relationType": normalize_text(item.get("relationType") or item.get("relation_type")),
                "title": item.get("title"),
            })
        elif isinstance(item, int) and item > 0:
            out.append({"id": item})
    return out


def build_metadata_row(catalog_row: dict[str, Any]) -> dict[str, Any] | None:
    anilist_id = catalog_row.get("anilist_id")
    if not isinstance(anilist_id, int) or anilist_id <= 0:
        return None

    metadata_json = catalog_row.get("metadata_json")
    metadata = metadata_json if isinstance(metadata_json, dict) else {}

    title_romaji = normalize_text(catalog_row.get("title_romaji")) or normalize_text(metadata.get("title_romaji"))
    title_english = normalize_text(catalog_row.get("title_english")) or normalize_text(metadata.get("title_english"))
    title_native = normalize_text(catalog_row.get("title_native")) or normalize_text(metadata.get("title_native"))
    title = title_english or title_romaji or title_native or ""

    synonyms = normalize_str_list(metadata.get("synonyms") or metadata.get("aliases"))
    genres = normalize_str_list(catalog_row.get("genres") or metadata.get("genres"))
    tags = normalize_tags(metadata.get("tags"))
    studios = normalize_studios(metadata.get("studios"))
    relations = normalize_relations(metadata.get("relations"))

    popularity = catalog_row.get("anilist_popularity")
    if not isinstance(popularity, int):
        pop2 = metadata.get("popularity")
        popularity = pop2 if isinstance(pop2, int) else None

    return {
        "anilist_id": anilist_id,
        "mal_id": catalog_row.get("mal_id"),
        "title": title,
        "title_romaji": title_romaji,
        "title_english": title_english,
        "title_native": title_native,
        "synonyms": synonyms,
        "aliases": synonyms,
        "genres": genres,
        "tags": tags,
        "studios": studios,
        "relations": relations,
        "description": normalize_text(catalog_row.get("description")) or normalize_text(metadata.get("description")),
        "average_score": catalog_row.get("average_score"),
        "popularity": popularity,
        "anilist_popularity": popularity,
        "episodes": catalog_row.get("episodes"),
        "format": normalize_text(catalog_row.get("format")),
        "status": normalize_text(catalog_row.get("status")),
        "season": normalize_text(catalog_row.get("season")),
        "season_year": catalog_row.get("season_year"),
        "is_adult": bool(catalog_row.get("is_adult")) if catalog_row.get("is_adult") is not None else None,
        "cover_image": catalog_row.get("cover_image") or metadata.get("cover_image"),
        "metadata_fingerprint": catalog_row.get("metadata_fingerprint"),
        "updated_at": catalog_row.get("updated_at"),
    }


def resolve_inputs(args: argparse.Namespace) -> tuple[Path, Path | None]:
    snapshot_manifest = args.snapshot_manifest
    snapshot_dir = args.snapshot_dir
    catalog_path = args.catalog_jsonl

    if snapshot_manifest is not None:
        snapshot_manifest = snapshot_manifest.resolve()
        if snapshot_dir is None:
            snapshot_dir = snapshot_manifest.parent
    if snapshot_dir is not None:
        snapshot_dir = snapshot_dir.resolve()
        if snapshot_manifest is None:
            candidate = snapshot_dir / "snapshot_manifest.json"
            snapshot_manifest = candidate if candidate.exists() else None
        if catalog_path is None:
            catalog_path = snapshot_dir / "anime_catalog.jsonl"
    if catalog_path is None:
        raise ValueError("Provide --snapshot-dir or --catalog-jsonl.")
    catalog_path = catalog_path.resolve()
    if not catalog_path.exists():
        raise FileNotFoundError(f"Catalog JSONL not found: {catalog_path}")
    if snapshot_manifest is not None and not snapshot_manifest.exists():
        raise FileNotFoundError(f"Snapshot manifest not found: {snapshot_manifest}")
    return catalog_path, snapshot_manifest


def main() -> None:
    args = parse_args()
    catalog_path, snapshot_manifest_path = resolve_inputs(args)
    output_path = args.output_path.resolve()
    fingerprint_output = args.fingerprint_output.resolve()

    snapshot_manifest = load_json(snapshot_manifest_path) if snapshot_manifest_path else {}
    catalog_rows = load_jsonl(catalog_path)

    out_rows: list[dict[str, Any]] = []
    score_count = 0
    pop_count = 0
    tag_count = 0
    alias_count = 0
    for row in catalog_rows:
        mapped = build_metadata_row(row)
        if mapped is None:
            continue
        if isinstance(mapped.get("average_score"), int):
            score_count += 1
        if isinstance(mapped.get("popularity"), int):
            pop_count += 1
        if mapped.get("tags"):
            tag_count += 1
        if mapped.get("synonyms"):
            alias_count += 1
        out_rows.append(mapped)

    out_rows.sort(key=lambda r: int(r["anilist_id"]))
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8") as f:
        for row in out_rows:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")

    total = len(out_rows)
    fingerprint = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "source": {
            "catalog_jsonl": str(catalog_path),
            "snapshot_manifest": str(snapshot_manifest_path) if snapshot_manifest_path else None,
        },
        "snapshot_manifest": snapshot_manifest,
        "output": {
            "metadata_jsonl": str(output_path),
            "rows": total,
            "score_coverage": 0.0 if total == 0 else score_count / total,
            "popularity_coverage": 0.0 if total == 0 else pop_count / total,
            "tag_coverage": 0.0 if total == 0 else tag_count / total,
            "alias_coverage": 0.0 if total == 0 else alias_count / total,
        },
    }
    fingerprint_output.parent.mkdir(parents=True, exist_ok=True)
    with fingerprint_output.open("w", encoding="utf-8") as f:
        json.dump(fingerprint, f, ensure_ascii=False, indent=2)

    print("Materialized notebook metadata from catalog snapshot")
    print(f"  catalog: {catalog_path}")
    if snapshot_manifest_path:
        print(f"  manifest: {snapshot_manifest_path}")
    print(f"  output: {output_path}")
    print(f"  fingerprint: {fingerprint_output}")
    print(f"  rows: {total}")


if __name__ == "__main__":
    main()
