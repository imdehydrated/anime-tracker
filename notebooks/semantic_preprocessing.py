"""Semantic review preprocessing helpers for Notebook 02.

Goal:
- Reduce markup/noise in AniList reviews.
- Keep high-signal opinion sentences (story, characters, pacing, etc.).
- Normalize title mentions to a neutral token to reduce memorization noise.
"""

from __future__ import annotations

import re

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


def _mask_title_mentions(text: str, anime_title: str) -> str:
    out = text
    for variant in _title_variants(anime_title):
        pattern = re.compile(rf"(?<!\w){re.escape(variant)}(?!\w)", re.IGNORECASE)
        out = pattern.sub("[TITLE]", out)
    return out


def _is_noise_sentence(sentence: str) -> bool:
    s = sentence.strip().lower()
    if len(s) < 25:
        return True
    if any(phrase in s for phrase in NOISE_PHRASES):
        return True
    if s.count("!") >= 4:
        return True
    return False


def _score_sentence(sentence: str) -> float:
    s = sentence.strip()
    sl = s.lower()
    score = 0.0

    if 40 <= len(s) <= 380:
        score += 1.0
    elif len(s) < 40:
        score -= 0.8
    else:
        score -= 0.2

    aspect_hits = sum(1 for kw in ASPECT_KEYWORDS if kw in sl)
    score += min(aspect_hits, 3) * 0.7

    opinion_hits = sum(1 for kw in OPINION_WORDS if kw in sl)
    score += min(opinion_hits, 2) * 0.6

    if any(tok in sl for tok in ("because", "however", "although", "but ")):
        score += 0.3

    # Penalize list-like fragments and metadata-ish lines.
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
    max_sentences: int = 8,
    min_score: float = 0.8,
) -> list[str]:
    sentences = _split_sentences(text)
    if not sentences:
        return []

    scored: list[tuple[int, float, str]] = []
    for idx, sentence in enumerate(sentences):
        if _is_noise_sentence(sentence):
            continue
        score = _score_sentence(sentence)
        if score >= min_score:
            scored.append((idx, score, sentence))

    if not scored:
        fallback = [s for s in sentences if not _is_noise_sentence(s)]
        return fallback[:max_sentences]

    # Keep highest-signal sentences, then restore document order.
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
    return deduped


def preprocess_review_text(
    raw_text: str,
    anime_title: str = "",
    max_chars: int = 1200,
) -> str:
    if not isinstance(raw_text, str):
        return ""

    cleaned = _strip_markup(raw_text)
    if not cleaned:
        return ""

    masked = _mask_title_mentions(cleaned, anime_title)
    selected = extract_relevant_sentences(masked, max_sentences=8, min_score=0.8)

    if selected:
        out = " ".join(selected)
    else:
        out = masked

    out = _normalize_whitespace(out)
    return out[:max_chars]
