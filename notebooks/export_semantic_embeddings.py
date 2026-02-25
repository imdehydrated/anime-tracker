#!/usr/bin/env python3
"""Export runtime-ready semantic embeddings with enriched AniList metadata.

Purpose:
- Replace notebook-only export steps with a reproducible CLI workflow.
- Encode corpus text with the selected semantic model.
- Join metadata needed by runtime ranking (aliases, tags, popularity signals).

Primary output:
- `ml-models/anime_embeddings.jsonl`
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from sentence_transformers import SentenceTransformer
from tqdm.auto import tqdm


def parse_args() -> argparse.Namespace:
    root = Path(__file__).resolve().parents[1]
    default_model = root / "ml-models" / "semantic"
    if not default_model.exists():
        default_model = root / "notebooks" / "models" / "anime_semantic_multipos"

    parser = argparse.ArgumentParser(
        description="Export semantic embeddings JSONL with AniList metadata fields.")
    parser.add_argument(
        "--model-path",
        type=Path,
        default=default_model,
        help="Path to sentence-transformer model directory.",
    )
    parser.add_argument(
        "--corpus-path",
        type=Path,
        default=root / "notebooks" / "data" / "corpus.jsonl",
        help="Corpus JSONL with anilist_id/title/text.",
    )
    parser.add_argument(
        "--metadata-path",
        type=Path,
        default=root / "notebooks" / "data" / "anilist_anime.jsonl",
        help="AniList metadata JSONL used to enrich export rows.",
    )
    parser.add_argument(
        "--output-path",
        type=Path,
        default=root / "ml-models" / "anime_embeddings.jsonl",
        help="Output embedding JSONL path.",
    )
    parser.add_argument(
        "--batch-size",
        type=int,
        default=64,
        help="Embedding batch size.",
    )
    parser.add_argument(
        "--max-items",
        type=int,
        default=0,
        help="Optional debug limit (0 means all corpus rows).",
    )
    return parser.parse_args()


def load_jsonl_rows(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as f:
        for line in f:
            if not line.strip():
                continue
            rows.append(json.loads(line))
    return rows


def metadata_index(rows: list[dict[str, Any]]) -> dict[int, dict[str, Any]]:
    out: dict[int, dict[str, Any]] = {}
    for row in rows:
        aid = row.get("anilist_id")
        if isinstance(aid, int) and aid > 0:
            out[aid] = row
    return out


def normalize_str_list(value: Any) -> list[str]:
    if isinstance(value, list):
        out: list[str] = []
        for item in value:
            if isinstance(item, str):
                text = item.strip()
                if text:
                    out.append(text)
        return out
    return []


def normalize_tags(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    out: list[str] = []
    for item in value:
        if isinstance(item, str):
            text = item.strip()
            if text:
                out.append(text)
        elif isinstance(item, dict):
            name = item.get("name")
            if isinstance(name, str) and name.strip():
                out.append(name.strip())
    return out


def normalize_relations(value: Any) -> list[int]:
    if not isinstance(value, list):
        return []
    out: list[int] = []
    for item in value:
        if isinstance(item, int) and item > 0:
            out.append(item)
    return out


def row_with_metadata(
        corpus_row: dict[str, Any],
        meta_row: dict[str, Any] | None,
        embedding: list[float]) -> dict[str, Any]:
    aid = int(corpus_row["anilist_id"])
    title = corpus_row.get("title")
    if not isinstance(title, str) or not title.strip():
        title = ""

    meta = meta_row or {}
    title_romaji = meta.get("title_romaji") or title
    title_english = meta.get("title_english")
    title_native = meta.get("title_native")
    synonyms = normalize_str_list(meta.get("synonyms"))
    genres = normalize_str_list(meta.get("genres"))
    tags = normalize_tags(meta.get("tags"))
    studios = normalize_str_list(meta.get("studios"))
    relations = normalize_relations(meta.get("relations"))
    average_score = meta.get("average_score")
    anilist_popularity = meta.get("popularity")
    if not isinstance(anilist_popularity, int):
        anilist_popularity = meta.get("anilist_popularity")

    out: dict[str, Any] = {
        "anilist_id": aid,
        "mal_id": meta.get("mal_id", corpus_row.get("mal_id")),
        "title": title,
        "title_romaji": title_romaji,
        "title_english": title_english,
        "title_native": title_native,
        "synonyms": synonyms,
        "genres": genres,
        "tags": tags,
        "format": meta.get("format"),
        "status": meta.get("status"),
        "season": meta.get("season"),
        "season_year": meta.get("season_year"),
        "studios": studios,
        "relations": relations,
        "average_score": average_score,
        "anilist_popularity": anilist_popularity,
        "popularity": anilist_popularity,
        "description": meta.get("description"),
        "embedding_text": corpus_row.get("text"),
        "embedding": embedding,
    }
    return out


def main() -> None:
    args = parse_args()

    model_path = args.model_path.resolve()
    corpus_path = args.corpus_path.resolve()
    metadata_path = args.metadata_path.resolve()
    output_path = args.output_path.resolve()

    if not model_path.exists():
        raise FileNotFoundError(f"Model path not found: {model_path}")
    if not corpus_path.exists():
        raise FileNotFoundError(f"Corpus file not found: {corpus_path}")
    if not metadata_path.exists():
        raise FileNotFoundError(f"Metadata file not found: {metadata_path}")

    corpus = load_jsonl_rows(corpus_path)
    if args.max_items > 0:
        corpus = corpus[: int(args.max_items)]
    if not corpus:
        raise RuntimeError("Corpus is empty; nothing to export.")

    meta_by_id = metadata_index(load_jsonl_rows(metadata_path))
    model = SentenceTransformer(str(model_path))

    texts = [str(row.get("text", "")) for row in corpus]
    all_embeddings = model.encode(
        texts,
        batch_size=max(1, int(args.batch_size)),
        show_progress_bar=True,
        normalize_embeddings=True,
    )

    output_path.parent.mkdir(parents=True, exist_ok=True)

    total = 0
    with_score = 0
    with_popularity = 0
    with_tags = 0
    with_aliases = 0

    with output_path.open("w", encoding="utf-8") as f:
        for i, corpus_row in tqdm(
                enumerate(corpus),
                total=len(corpus),
                desc="Writing anime_embeddings.jsonl"):
            aid = corpus_row.get("anilist_id")
            if not isinstance(aid, int) or aid <= 0:
                continue
            row = row_with_metadata(
                corpus_row,
                meta_by_id.get(aid),
                all_embeddings[i].tolist(),
            )
            if isinstance(row.get("average_score"), int):
                with_score += 1
            if isinstance(row.get("anilist_popularity"), int):
                with_popularity += 1
            if row.get("tags"):
                with_tags += 1
            if row.get("synonyms"):
                with_aliases += 1
            f.write(json.dumps(row) + "\n")
            total += 1

    size_mb = output_path.stat().st_size / 1e6
    score_cov = 0.0 if total == 0 else with_score / total
    pop_cov = 0.0 if total == 0 else with_popularity / total
    tag_cov = 0.0 if total == 0 else with_tags / total
    alias_cov = 0.0 if total == 0 else with_aliases / total

    print("Export complete")
    print(f"  output: {output_path}")
    print(f"  rows: {total}")
    print(f"  size_mb: {size_mb:.2f}")
    print(
        "  coverage:"
        f" score={score_cov:.4f}"
        f", popularity={pop_cov:.4f}"
        f", tags={tag_cov:.4f}"
        f", aliases={alias_cov:.4f}")


if __name__ == "__main__":
    main()
