#!/usr/bin/env python3
"""Normalize raw Kaggle datasets into canonical notebook input CSVs.

Supported source layouts:
- `hernan4444/anime-recommendation-database-2020` (already canonical)
- `marlesson/myanimelist-dataset-animes-profiles-reviews` (adapted)

Outputs written to `--output`:
- `anime.csv`
- `anime_with_synopsis.csv`
- `rating_complete.csv`
"""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path

import numpy as np
import pandas as pd


CANONICAL_FILES = ("anime.csv", "anime_with_synopsis.csv", "rating_complete.csv")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Prepare canonical Kaggle CSVs for notebooks.")
    parser.add_argument(
        "--source",
        type=Path,
        default=Path("data/raw-kaggle"),
        help="Directory containing raw Kaggle CSVs.",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("data/kaggle"),
        help="Directory where canonical CSVs will be written.",
    )
    return parser.parse_args()


def first_existing(source: Path, names: list[str]) -> Path | None:
    for name in names:
        path = source / name
        if path.exists():
            return path
    return None


def pick_column(
    df: pd.DataFrame,
    candidates: list[str],
    label: str,
    required: bool = True,
) -> str | None:
    for col in candidates:
        if col in df.columns:
            return col
    if required:
        raise ValueError(f"Missing required {label} column. Tried: {candidates}")
    return None


def ensure_output_dir(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True)


def copy_canonical(source: Path, output: Path) -> None:
    for name in CANONICAL_FILES:
        src = source / name
        dst = output / name
        if src.resolve() == dst.resolve():
            continue
        shutil.copy2(src, dst)


def load_csv(path: Path) -> pd.DataFrame:
    return pd.read_csv(path, low_memory=False)


def coerce_rating(rating_series: pd.Series) -> pd.Series:
    ratings = pd.to_numeric(rating_series, errors="coerce")
    if ratings.notna().any():
        max_rating = float(ratings.max())
        if 10.0 < max_rating <= 100.0:
            ratings = ratings / 10.0
    return ratings


def factorize_user_ids(user_series: pd.Series) -> pd.Series:
    # Stable integer IDs starting at 1, regardless of source user key format.
    labels = user_series.fillna("").astype(str).str.strip()
    encoded, _ = pd.factorize(labels, sort=False)
    return pd.Series(encoded + 1, index=user_series.index)


def adapt_marlesson(source: Path, output: Path) -> None:
    anime_path = first_existing(source, ["animes.csv", "anime.csv"])
    reviews_path = first_existing(source, ["reviews.csv", "rating_complete.csv", "ratings.csv"])
    if anime_path is None or reviews_path is None:
        raise ValueError(
            "Could not find required source files for Marlesson-style adaptation. "
            "Expected anime file (animes.csv/anime.csv) and ratings file (reviews.csv/ratings.csv)."
        )

    anime_df = load_csv(anime_path)
    reviews_df = load_csv(reviews_path)

    mal_col = pick_column(anime_df, ["MAL_ID", "mal_id", "anime_id", "uid", "id"], "anime id")
    uid_col = pick_column(anime_df, ["uid", "anime_uid"], "anime uid", required=False)
    name_col = pick_column(
        anime_df,
        ["Name", "name", "title", "title_english", "english_title", "title_romaji"],
        "anime title",
    )
    score_col = pick_column(anime_df, ["Score", "score", "mean_score"], "anime score", required=False)
    genres_col = pick_column(anime_df, ["Genres", "genres", "genre", "tags"], "genres", required=False)
    synopsis_col = pick_column(
        anime_df,
        ["sypnopsis", "synopsis", "Synopsis", "description", "Description"],
        "synopsis",
        required=False,
    )

    mal_ids = pd.to_numeric(anime_df[mal_col], errors="coerce")
    if mal_ids.notna().sum() == 0:
        raise ValueError(f"Anime ID column '{mal_col}' has no usable numeric values.")
    anime_df = anime_df.assign(_mal_id=mal_ids).dropna(subset=["_mal_id"]).copy()
    anime_df["_mal_id"] = anime_df["_mal_id"].astype(np.int64)

    anime_out = pd.DataFrame(
        {
            "MAL_ID": anime_df["_mal_id"],
            "Name": anime_df[name_col].fillna("Unknown").astype(str),
            "Score": pd.to_numeric(anime_df[score_col], errors="coerce") if score_col else np.nan,
            "Genres": anime_df[genres_col].fillna("").astype(str) if genres_col else "",
        }
    ).drop_duplicates(subset=["MAL_ID"])

    synopsis_out = pd.DataFrame(
        {
            "MAL_ID": anime_df["_mal_id"],
            "Name": anime_df[name_col].fillna("Unknown").astype(str),
            "Score": pd.to_numeric(anime_df[score_col], errors="coerce") if score_col else np.nan,
            "sypnopsis": anime_df[synopsis_col].fillna("").astype(str) if synopsis_col else "",
        }
    ).drop_duplicates(subset=["MAL_ID"])

    user_col = pick_column(
        reviews_df,
        ["user_id", "profile", "profile_id", "username", "reviewer", "member"],
        "review user id",
    )
    anime_ref_col = pick_column(
        reviews_df,
        ["anime_id", "anime_uid", "uid", "MAL_ID", "mal_id"],
        "review anime id",
    )
    rating_col = pick_column(reviews_df, ["rating", "score", "Score", "overall"], "rating")

    user_ids = factorize_user_ids(reviews_df[user_col])
    anime_refs = pd.to_numeric(reviews_df[anime_ref_col], errors="coerce")

    # If reviews reference uid/anime_uid and anime has MAL IDs, remap to MAL IDs.
    if uid_col and anime_ref_col in {"uid", "anime_uid"} and uid_col in anime_df.columns:
        uid_to_mal = (
            anime_df[[uid_col, "_mal_id"]]
            .assign(**{uid_col: pd.to_numeric(anime_df[uid_col], errors="coerce")})
            .dropna(subset=[uid_col])
            .drop_duplicates(subset=[uid_col])
            .set_index(uid_col)["_mal_id"]
        )
        anime_refs = anime_refs.map(uid_to_mal).astype("float64")

    ratings = coerce_rating(reviews_df[rating_col])

    ratings_out = pd.DataFrame(
        {
            "user_id": user_ids,
            "anime_id": anime_refs,
            "rating": ratings,
        }
    )
    ratings_out = ratings_out.dropna(subset=["anime_id", "rating"])
    ratings_out = ratings_out[(ratings_out["rating"] > 0) & (ratings_out["rating"] <= 10)]
    ratings_out["anime_id"] = ratings_out["anime_id"].astype(np.int64)
    ratings_out["rating"] = ratings_out["rating"].astype(float)
    ratings_out = ratings_out.drop_duplicates(subset=["user_id", "anime_id"], keep="last")

    if ratings_out.empty:
        raise ValueError("No valid ratings were produced after adaptation.")

    anime_out.to_csv(output / "anime.csv", index=False)
    synopsis_out.to_csv(output / "anime_with_synopsis.csv", index=False)
    ratings_out.to_csv(output / "rating_complete.csv", index=False)

    print("Prepared Marlesson-style dataset into canonical schema:")
    print(f"  anime.csv rows: {len(anime_out):,}")
    print(f"  anime_with_synopsis.csv rows: {len(synopsis_out):,}")
    print(f"  rating_complete.csv rows: {len(ratings_out):,}")


def main() -> None:
    args = parse_args()
    source = args.source.resolve()
    output = args.output.resolve()

    if not source.exists():
        raise FileNotFoundError(f"Source directory not found: {source}")

    ensure_output_dir(output)

    if all((source / name).exists() for name in CANONICAL_FILES):
        copy_canonical(source, output)
        print("Canonical Kaggle files detected and copied:")
        for name in CANONICAL_FILES:
            print(f"  {output / name}")
        return

    adapt_marlesson(source, output)
    print(f"Output directory: {output}")


if __name__ == "__main__":
    main()
