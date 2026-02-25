"""Review preprocessing helpers used by Notebook 02 semantic data generation.

Goals:
- Remove markup/noise from AniList review text.
- Retain higher-signal opinion and constraint sentences.
- Apply deterministic partial title masking to reduce title memorization.

Public entrypoint:
- `preprocess_review_text(...)` returns cleaned review text, and optional diagnostics.
"""

from __future__ import annotations

import hashlib
import re
from typing import Any

SENTENCE_SPLIT_RE = re.compile(r"(?<=[.!?])\s+")
WHITESPACE_RE = re.compile(r"\s+")

ASPECT_KEYWORDS = {
    "story",
    "plot",
    "narrative",
    "character",
    "characters",
    "protagonist",
    "antagonist",
    "development",
    "writing",
    "dialogue",
    "theme",
    "themes",
    "world",
    "worldbuilding",
    "setting",
    "pacing",
    "ending",
    "animation",
    "visuals",
    "art",
    "soundtrack",
    "music",
    "voice",
    "acting",
    "comedy",
    "drama",
    "romance",
    "action",
    "mystery",
    "psychological",
}

OPINION_WORDS = {
    "great",
    "excellent",
    "amazing",
    "good",
    "solid",
    "decent",
    "bad",
    "weak",
    "poor",
    "boring",
    "interesting",
    "enjoyable",
    "disappointing",
    "favorite",
    "recommend",
    "recommended",
    "worth",
    "overrated",
    "underrated",
}

NOISE_PHRASES = (
    "thanks for reading",
    "if you liked this review",
    "follow me for more",
    "check out my profile",
    "like and subscribe",
)

NEGATION_TOKENS = (
    " not ",
    " without ",
    " except ",
    " but ",
    " however ",
)


def _normalize_whitespace(text: str) -> str:
    return WHITESPACE_RE.sub(" ", text).strip()


def _strip_markup(text: str) -> str:
    t = text
    t = re.sub(r"~~~img[^~]*~~~", " ", t, flags=re.IGNORECASE)
    t = re.sub(r"<br\s*/?>", "\n", t, flags=re.IGNORECASE)
    t = re.sub(r"\[(.*?)\]\((.*?)\)", r"\1", t)
    t = re.sub(r"`{1,3}[^`]*`{1,3}", " ", t)
    t = re.sub(r"\*{2,}|_{2,}|~{2,}", " ", t)
    t = re.sub(r"\[/?spoiler\]", " ", t, flags=re.IGNORECASE)
    t = re.sub(r"https?://\S+", " ", t)
    return _normalize_whitespace(t)


def _title_variants(anime_title: str) -> list[str]:
    if not anime_title:
        return []
    base = anime_title.strip()
    variants = {base}
    if ":" in base:
        left = base.split(":", 1)[0].strip()
        if len(left) >= 3:
            variants.add(left)
    simple = re.sub(r"[^\w\s]", " ", base)
    simple = _normalize_whitespace(simple)
    if len(simple) >= 3:
        variants.add(simple)
    return [v for v in variants if len(v) >= 3]


def _stable_fraction(anime_id: int | None, raw_text: str) -> float:
    material = f"{anime_id or 0}::{raw_text}".encode("utf-8", errors="ignore")
    digest = hashlib.sha256(material).digest()
    return int.from_bytes(digest[:8], "big") / float(2**64 - 1)


def _should_mask_title(anime_id: int | None, raw_text: str, mask_title_probability: float) -> bool:
    p = max(0.0, min(1.0, float(mask_title_probability)))
    return _stable_fraction(anime_id, raw_text) <= p


def _mask_title_mentions(text: str, anime_title: str) -> str:
    out = text
    for variant in _title_variants(anime_title):
        pattern = re.compile(rf"(?<!\w){re.escape(variant)}(?!\w)", re.IGNORECASE)
        out = pattern.sub("[TITLE]", out)
    return out


def _contains_constraint_language(sentence: str) -> bool:
    s = f" {sentence.lower()} "
    return any(token in s for token in NEGATION_TOKENS)


def _is_noise_sentence(sentence: str, min_sentence_chars: int = 35) -> bool:
    s = sentence.strip().lower()
    if not s:
        return True
    if len(s) < min_sentence_chars and not _contains_constraint_language(s):
        return True
    if any(phrase in s for phrase in NOISE_PHRASES):
        return True
    if s.count("!") >= 4:
        return True
    return False


def _score_sentence(sentence: str, min_sentence_chars: int = 35) -> float:
    s = sentence.strip()
    sl = s.lower()
    score = 0.0

    if min_sentence_chars <= len(s) <= 420:
        score += 1.0
    elif len(s) < min_sentence_chars:
        score -= 0.8
    else:
        score -= 0.2

    aspect_hits = sum(1 for kw in ASPECT_KEYWORDS if kw in sl)
    score += min(aspect_hits, 3) * 0.7

    opinion_hits = sum(1 for kw in OPINION_WORDS if kw in sl)
    score += min(opinion_hits, 2) * 0.6

    if any(tok in sl for tok in ("because", "however", "although", "but ", "while ")):
        score += 0.3

    if _contains_constraint_language(sl):
        score += 0.4

    if re.search(r"\b\d+/\d+\b", sl):
        score -= 0.5
    if re.search(r"\bepisode\s+\d+\b", sl):
        score -= 0.2
    if re.search(r"^[\-\*\d\.\)\s]+$", s):
        score -= 0.8

    return score


def _split_sentences(text: str) -> list[str]:
    chunks = SENTENCE_SPLIT_RE.split(text)
    return [_normalize_whitespace(c) for c in chunks if _normalize_whitespace(c)]


def extract_relevant_sentences(
    text: str,
    max_sentences: int = 10,
    min_score: float = 0.9,
    min_sentence_chars: int = 35,
) -> tuple[list[str], dict[str, int]]:
    sentences = _split_sentences(text)
    if not sentences:
        return [], {
            "total_sentences": 0,
            "noise_filtered": 0,
            "low_score_filtered": 0,
            "kept_sentences": 0,
        }

    scored: list[tuple[int, float, str]] = []
    noise_filtered = 0
    low_score_filtered = 0

    for idx, sentence in enumerate(sentences):
        if _is_noise_sentence(sentence, min_sentence_chars=min_sentence_chars):
            noise_filtered += 1
            continue
        score = _score_sentence(sentence, min_sentence_chars=min_sentence_chars)
        if score >= min_score:
            scored.append((idx, score, sentence))
        else:
            low_score_filtered += 1

    if not scored:
        fallback = [
            s
            for s in sentences
            if not _is_noise_sentence(s, min_sentence_chars=min_sentence_chars)
        ]
        chosen = fallback[:max_sentences]
        return chosen, {
            "total_sentences": len(sentences),
            "noise_filtered": noise_filtered,
            "low_score_filtered": low_score_filtered,
            "kept_sentences": len(chosen),
        }

    scored.sort(key=lambda row: row[1], reverse=True)
    top = scored[:max_sentences]
    top.sort(key=lambda row: row[0])

    deduped: list[str] = []
    seen: set[str] = set()
    for _, _, sentence in top:
        key = sentence.lower()
        if key in seen:
            continue
        seen.add(key)
        deduped.append(sentence)

    return deduped, {
        "total_sentences": len(sentences),
        "noise_filtered": noise_filtered,
        "low_score_filtered": low_score_filtered,
        "kept_sentences": len(deduped),
    }


def preprocess_review_text(
    raw_text: str,
    anime_title: str = "",
    anime_id: int | None = None,
    max_chars: int = 1200,
    min_output_chars: int = 140,
    mask_title_probability: float = 0.45,
    max_sentences: int = 10,
    min_score: float = 0.9,
    min_sentence_chars: int = 35,
    return_diagnostics: bool = False,
) -> str | tuple[str, dict[str, Any]]:
    diagnostics: dict[str, Any] = {
        "dropped_short": 0,
        "dropped_noise": 0,
        "kept_reviews": 0,
        "mask_applied": False,
        "total_sentences": 0,
        "noise_filtered": 0,
        "low_score_filtered": 0,
        "kept_sentences": 0,
    }

    if not isinstance(raw_text, str):
        diagnostics["dropped_noise"] = 1
        return ("", diagnostics) if return_diagnostics else ""

    cleaned = _strip_markup(raw_text)
    if not cleaned:
        diagnostics["dropped_noise"] = 1
        return ("", diagnostics) if return_diagnostics else ""

    if _should_mask_title(anime_id, raw_text, mask_title_probability):
        processed = _mask_title_mentions(cleaned, anime_title)
        diagnostics["mask_applied"] = True
    else:
        processed = cleaned

    selected, sentence_stats = extract_relevant_sentences(
        processed,
        max_sentences=max_sentences,
        min_score=min_score,
        min_sentence_chars=min_sentence_chars,
    )
    diagnostics.update(sentence_stats)

    out = " ".join(selected) if selected else processed
    out = _normalize_whitespace(out)
    out = out[:max_chars]

    if len(out) < min_output_chars:
        diagnostics["dropped_short"] = 1
        return ("", diagnostics) if return_diagnostics else ""

    diagnostics["kept_reviews"] = 1
    return (out, diagnostics) if return_diagnostics else out
