package com.animetracker.service;

import com.animetracker.dto.AniListResponse;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared filter heuristics for anime format/adult/season classification.
 * Keeps filtering logic consistent across search and recommendation services.
 */
public final class AnimeFilterPolicy {

    private static final Set<String> ADULT_BLOCKLIST_TAG_KEYWORDS = Set.of(
            "hentai", "nudity", "sex", "sexual", "erotic", "porn", "explicit");
    private static final Set<String> MUSIC_KEYWORDS = Set.of("music", "song", "idol", "concert");
    private static final Set<String> ENTRYPOINT_RELATION_TYPES = Set.of(
            "PREQUEL",
            "PARENT",
            "PARENT_STORY");
    private static final Set<String> EXCLUDED_STATUSES = Set.of(
            "CANCELLED");

    private AnimeFilterPolicy() {
    }

    public static boolean isAdultCandidate(AniListResponse.AnimeInfo anime, int ecchiRankThreshold) {
        if (anime == null) {
            return false;
        }
        if (Boolean.TRUE.equals(anime.getIsAdult())) {
            return true;
        }
        Set<String> genres = parseGenreSet(anime.getGenres());
        boolean ecchiGenre = genres.contains("ecchi");
        if (genres.contains("hentai")) {
            return true;
        }

        if (anime.getTags() != null) {
            for (AniListResponse.AnimeTag tag : anime.getTags()) {
                if (tag == null || tag.getName() == null || tag.getName().isBlank()) {
                    continue;
                }
                String lowered = tag.getName().toLowerCase();
                if (containsAdultTagKeyword(lowered)) {
                    return true;
                }
                if (ecchiGenre && lowered.contains("ecchi")
                        && tag.getRank() != null
                        && tag.getRank() >= Math.max(0, ecchiRankThreshold)) {
                    return true;
                }
            }
        }
        if (!ecchiGenre) {
            return false;
        }
        String text = animeTextBlob(anime);
        return text.contains(" explicit ")
                || text.contains(" erotic ")
                || text.contains(" sexual ");
    }

    private static boolean containsAdultTagKeyword(String tagName) {
        if (tagName == null || tagName.isBlank()) {
            return false;
        }
        Set<String> tokens = Arrays.stream(tagName.split("[^a-z0-9]+"))
                .filter(token -> token != null && !token.isBlank())
                .collect(Collectors.toSet());
        if (tokens.isEmpty()) {
            return false;
        }
        return ADULT_BLOCKLIST_TAG_KEYWORDS.stream().anyMatch(tokens::contains);
    }

    public static boolean isMovieCandidate(AniListResponse.AnimeInfo anime) {
        if (anime == null) {
            return false;
        }
        String format = anime.getFormat();
        if (format != null && "MOVIE".equalsIgnoreCase(format.trim())) {
            return true;
        }
        String text = animeTextBlob(anime);
        return text.contains(" movie ") || text.contains(" film ");
    }

    public static boolean isOnaOvaSpecialCandidate(AniListResponse.AnimeInfo anime) {
        if (anime == null) {
            return false;
        }
        String format = anime.getFormat();
        if (format != null) {
            String normalized = format.trim().toUpperCase();
            if ("ONA".equals(normalized) || "OVA".equals(normalized) || "SPECIAL".equals(normalized)) {
                return true;
            }
        }
        String text = animeTextBlob(anime);
        return text.contains(" ona ") || text.contains(" ova ") || text.contains(" special ");
    }

    public static boolean isMusicCandidate(AniListResponse.AnimeInfo anime) {
        if (anime == null) {
            return false;
        }
        String format = anime.getFormat();
        if (format != null && "MUSIC".equalsIgnoreCase(format.trim())) {
            return true;
        }
        Set<String> genres = parseGenreSet(anime.getGenres());
        if (genres.contains("music")) {
            return true;
        }
        String text = animeTextBlob(anime);
        for (String keyword : MUSIC_KEYWORDS) {
            if (text.contains(" " + keyword + " ")) {
                return true;
            }
        }
        return false;
    }

    public static boolean isExtraSeasonCandidate(AniListResponse.AnimeInfo anime) {
        if (anime == null || anime.getRelations() == null || anime.getRelations().isEmpty()) {
            return false;
        }
        for (AniListResponse.AnimeRelation relation : anime.getRelations()) {
            if (relation == null || relation.getId() == null || relation.getId() <= 0) {
                continue;
            }
            String relationType = normalizeRelationType(relation.getRelationType());
            if (ENTRYPOINT_RELATION_TYPES.contains(relationType)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isExcludedByStatus(AniListResponse.AnimeInfo anime) {
        if (anime == null || anime.getStatus() == null || anime.getStatus().isBlank()) {
            return false;
        }
        String normalized = anime.getStatus().trim().toUpperCase().replace(' ', '_').replace('-', '_');
        return EXCLUDED_STATUSES.contains(normalized);
    }

    public static Set<String> parseGenreSet(List<String> genres) {
        if (genres == null || genres.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new HashSet<>();
        for (String genre : genres) {
            if (genre == null || genre.isBlank()) {
                continue;
            }
            normalized.add(genre.toLowerCase());
        }
        return normalized;
    }

    public static String animeTitleBlob(AniListResponse.AnimeInfo anime) {
        StringBuilder text = new StringBuilder(" ");
        if (anime != null && anime.getTitle() != null) {
            if (anime.getTitle().getEnglish() != null) {
                text.append(anime.getTitle().getEnglish()).append(' ');
            }
            if (anime.getTitle().getRomaji() != null) {
                text.append(anime.getTitle().getRomaji()).append(' ');
            }
            if (anime.getTitle().getNativeTitle() != null) {
                text.append(anime.getTitle().getNativeTitle()).append(' ');
            }
        }
        if (anime != null && anime.getSynonyms() != null) {
            for (String synonym : anime.getSynonyms()) {
                if (synonym == null || synonym.isBlank()) {
                    continue;
                }
                text.append(synonym).append(' ');
            }
        }
        return text.toString().toLowerCase().replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ");
    }

    public static String animeTextBlob(AniListResponse.AnimeInfo anime) {
        StringBuilder text = new StringBuilder(animeTitleBlob(anime));
        if (anime != null && anime.getDescription() != null) {
            text.append(anime.getDescription()).append(' ');
        }
        return text.toString().toLowerCase().replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ");
    }

    private static String normalizeRelationType(String relationType) {
        if (relationType == null || relationType.isBlank()) {
            return "";
        }
        return relationType.trim()
                .toUpperCase()
                .replace(' ', '_')
                .replace('-', '_');
    }
}
