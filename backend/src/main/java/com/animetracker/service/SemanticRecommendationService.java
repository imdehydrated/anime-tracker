package com.animetracker.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.animetracker.dto.AniListResponse;
import com.animetracker.entity.AnimeListEntry;
import com.animetracker.entity.RecommendationBlacklist;
import com.animetracker.entity.User;
import com.animetracker.exception.BadRequestException;
import com.animetracker.exception.NotFoundException;
import com.animetracker.exception.UnauthorizedException;
import com.animetracker.repository.AnimeEmbeddingRepository;
import com.animetracker.repository.RecommendationBlacklistRepository;
import com.animetracker.repository.UserRepository;

/**
 * Semantic recommendation engine.
 * Builds a search vector from seed anime, optional text query, and optional user-list preference vector,
 * then queries pgvector for nearest neighbors.
 */
@Service
public class SemanticRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(SemanticRecommendationService.class);

    private final EmbeddingService embeddingService;
    private final AnimeEmbeddingRepository embeddingRepository;
    private final AnimeListEntryService animeListEntryService;
    private final RecommendationBlacklistRepository blacklistRepository;
    private final UserRepository userRepository;
    private final AniListService aniListService;
    private final AnimeEmbeddingPopulatorService populatorService;
    private final MlSidecarService mlSidecarService;
    @Value("${recommendations.use-custom-vectors:false}")
    private boolean useCustomVectors;

    public SemanticRecommendationService(EmbeddingService embeddingService,
            AnimeEmbeddingRepository embeddingRepository,
            AnimeListEntryService animeListEntryService,
            RecommendationBlacklistRepository blacklistRepository,
            UserRepository userRepository,
            AniListService aniListService,
            AnimeEmbeddingPopulatorService populatorService,
            MlSidecarService mlSidecarService) {
        this.embeddingService = embeddingService;
        this.embeddingRepository = embeddingRepository;
        this.animeListEntryService = animeListEntryService;
        this.blacklistRepository = blacklistRepository;
        this.userRepository = userRepository;
        this.aniListService = aniListService;
        this.populatorService = populatorService;
        this.mlSidecarService = mlSidecarService;
    }

    /**
     * Primary recommendation entrypoint used by recommendation endpoints.
     */
    public List<AniListResponse.AnimeInfo> recommend(
            String username,
            List<Integer> seedIds,
            String query,
            Integer requestedLimit,
            boolean useListOnly,
            Float requestedListWeight,
            String mode) {

        String effectiveMode = (mode == null || mode.isBlank())
                ? "semantic"
                : mode.trim().toLowerCase();

        // CF-only mode: delegate entirely to sidecar
        if ("cf".equals(effectiveMode)) {
            return recommendCf(username, requestedLimit);
        }

        // Similar mode: seed-driven "shows like these", with optional list influence
        if ("similar".equals(effectiveMode)) {
            return recommendSimilar(username, seedIds, requestedLimit, requestedListWeight);
        }

        List<Integer> normalizedSeeds = normalizeIds(seedIds);
        boolean hasSeeds = !normalizedSeeds.isEmpty();
        boolean hasQuery = query != null && !query.isBlank();
        boolean effectiveListOnly = useListOnly
                || (username != null
                && requestedListWeight != null
                && requestedListWeight >= 1.0f
                && !hasSeeds
                && !hasQuery);

        if (effectiveListOnly && username == null) {
            throw new UnauthorizedException("Login required for list-only recommendations");
        }
        if (!effectiveListOnly && !hasSeeds && !hasQuery) {
            throw new BadRequestException("Provide at least one seed anime or a text query");
        }
        if (normalizedSeeds.size() > 5) {
            throw new BadRequestException("Maximum 5 seed anime allowed");
        }

        int limit = normalizeLimit(requestedLimit);
        float listWeight = normalizeListWeight(requestedListWeight);
        if (effectiveListOnly) {
            listWeight = 1.0f;
        }

        float[] searchVector = null;
        if (hasSeeds) {
            List<Object[]> seedRows = loadEmbeddings(normalizedSeeds, true);
            if (seedRows.isEmpty()) {
                log.warn("No seed embeddings available for seeds {}", normalizedSeeds);
                return List.of();
            }
            searchVector = averageRows(seedRows);
        }

        if (hasQuery) {
            String normalizedQuery = query.trim();
            if (normalizedQuery.length() > 500) {
                normalizedQuery = normalizedQuery.substring(0, 500);
            }
            // Use sidecar for embedding if available, else fall back to OpenAI
            float[] queryVector = embedQuery(normalizedQuery);
            searchVector = (searchVector == null)
                    ? queryVector
                    : blend(searchVector, queryVector, 0.50f);
        }

        if (username != null && (effectiveListOnly || listWeight > 0f)) {
            float[] listVector = buildUserPreferenceVector(username);
            if (listVector == null) {
                if (effectiveListOnly) {
                    throw new BadRequestException("Your list does not have enough embedded anime yet");
                }
            } else {
                searchVector = (searchVector == null)
                        ? listVector
                        : blend(searchVector, listVector, listWeight);
            }
        }

        if (searchVector == null) {
            return List.of();
        }

        List<Integer> excludeIds = buildExcludeIds(username, normalizedSeeds);
        String vectorStr = EmbeddingService.toVectorString(searchVector);
        List<Object[]> rows = findSimilarRows(vectorStr, excludeIds, limit);

        List<AniListResponse.AnimeInfo> semanticResults = new ArrayList<>();
        for (Object[] row : rows) {
            semanticResults.add(mapRowToAnimeInfo(row));
        }

        return semanticResults;
    }

    /**
     * CF-only mode: get predictions entirely from the sidecar's collaborative filtering model.
     */
    private List<AniListResponse.AnimeInfo> recommendCf(String username, Integer requestedLimit) {
        if (username == null) {
            throw new UnauthorizedException("Login required for CF recommendations");
        }
        if (!mlSidecarService.isEnabled()) {
            throw new BadRequestException("CF model is not available");
        }

        int limit = normalizeLimit(requestedLimit);
        Map<Integer, Float> userRatings = buildUserRatingMap(username);
        List<Integer> excludeIds = buildExcludeIds(username, List.of());

        List<Map<String, Object>> predictions = mlSidecarService.getCfRecommendations(
                userRatings, excludeIds, limit);

        if (predictions == null || predictions.isEmpty()) {
            throw new BadRequestException("CF model returned no predictions - your list may be too small");
        }

        // Fetch full anime info for each predicted AniList ID
        List<AniListResponse.AnimeInfo> results = new ArrayList<>();
        for (Map<String, Object> pred : predictions) {
            int anilistId = ((Number) pred.get("anilist_id")).intValue();
            try {
                AniListResponse.AnimeInfo anime = aniListService.getAnimeById(anilistId);
                if (anime != null) {
                    results.add(anime);
                }
            } catch (Exception e) {
                log.warn("Failed to fetch anime {} for CF result: {}", anilistId, e.getMessage());
            }
        }
        return results;
    }

    /**
     * Similar mode: seed-based similarity with optional user-list influence.
     */
    private List<AniListResponse.AnimeInfo> recommendSimilar(
            String username,
            List<Integer> seedIds,
            Integer requestedLimit,
            Float requestedListWeight) {

        List<Integer> normalizedSeeds = normalizeIds(seedIds);
        if (normalizedSeeds.isEmpty()) {
            throw new BadRequestException("Provide at least one seed anime for Similar Shows");
        }
        if (normalizedSeeds.size() > 5) {
            throw new BadRequestException("Maximum 5 seed anime allowed");
        }

        List<Object[]> seedRows = loadEmbeddings(normalizedSeeds, true);
        if (seedRows.isEmpty()) {
            log.warn("No seed embeddings available for similar mode, seeds={}", normalizedSeeds);
            return List.of();
        }

        float[] searchVector = averageRows(seedRows);
        // Similar mode should only use list personalization when explicitly requested.
        float listWeight = (requestedListWeight == null) ? 0f : normalizeListWeight(requestedListWeight);

        // Optional personalization: blend seed centroid with user's preference vector.
        if (username != null && listWeight > 0f) {
            float[] listVector = buildUserPreferenceVector(username);
            if (listVector != null) {
                searchVector = blend(searchVector, listVector, listWeight);
            }
        }

        List<Integer> excludeIds = buildExcludeIds(username, normalizedSeeds);
        int limit = normalizeLimit(requestedLimit);
        String vectorStr = EmbeddingService.toVectorString(searchVector);

        // Overfetch for reranking headroom
        List<Object[]> candidates = findSimilarRows(vectorStr, excludeIds, limit * 3);

        List<AniListResponse.AnimeInfo> results;

        // Sidecar reranking expects custom 384-dim vectors.
        // Skip reranking when using OpenAI 1536-dim retrieval to avoid dimension mismatch.
        if (useCustomVectors && mlSidecarService.isEnabled() && !candidates.isEmpty()) {
            // Extract candidate IDs and cosine distances for reranking
            List<Integer> candidateIds = new ArrayList<>();
            List<Double> candidateDistances = new ArrayList<>();
            Map<Integer, Object[]> rowById = new HashMap<>();

            for (Object[] row : candidates) {
                Integer anilistId = (Integer) row[1];
                Double distance = ((Number) row[13]).doubleValue();
                candidateIds.add(anilistId);
                candidateDistances.add(distance);
                rowById.put(anilistId, row);
            }

            List<Map<String, Object>> reranked = mlSidecarService.rerank(
                    searchVector, candidateIds, candidateDistances, limit);

            if (reranked != null && !reranked.isEmpty()) {
                results = new ArrayList<>();
                for (Map<String, Object> item : reranked) {
                    int anilistId = ((Number) item.get("anilist_id")).intValue();
                    Object[] row = rowById.get(anilistId);
                    if (row != null) {
                        results.add(mapRowToAnimeInfo(row));
                    }
                }
            } else {
                // Rerank failed - fall back to pgvector order
                results = new ArrayList<>();
                for (Object[] row : candidates.subList(0, Math.min(limit, candidates.size()))) {
                    results.add(mapRowToAnimeInfo(row));
                }
            }
        } else {
            // No sidecar - use pgvector order directly
            results = new ArrayList<>();
            for (Object[] row : candidates.subList(0, Math.min(limit, candidates.size()))) {
                results.add(mapRowToAnimeInfo(row));
            }
        }

        return results;
    }

    /**
     * Embed a query string - prefers sidecar custom model, falls back to OpenAI.
     */
    private float[] embedQuery(String text) {
        if (useCustomVectors) {
            if (!mlSidecarService.isEnabled()) {
                throw new BadRequestException("Custom semantic retrieval requires ML sidecar to be enabled");
            }
            float[] custom = mlSidecarService.embedText(text);
            if (custom != null) {
                return custom;
            }
            throw new BadRequestException("ML sidecar failed to embed query text");
        }
        return embeddingService.embed(text);
    }

    /**
     * Build a map of {anilistId -> score} for the sidecar CF model.
     */
    private Map<Integer, Float> buildUserRatingMap(String username) {
        List<AnimeListEntry> userList = animeListEntryService.getUserList(username);
        Map<Integer, Float> ratings = new HashMap<>();
        for (AnimeListEntry entry : userList) {
            if (entry.getScore() > 0) {
                ratings.put(entry.getAnilistId(), (float) entry.getScore());
            }
        }
        return ratings;
    }

    public void blacklistAnime(String username, Integer anilistId, String title, String coverImage) {
        if (anilistId == null) {
            throw new BadRequestException("anilistId is required");
        }
        User user = getUser(username);
        if (!blacklistRepository.existsByUserAndAnilistId(user, anilistId)) {
            blacklistRepository.save(new RecommendationBlacklist(user, anilistId, title, coverImage));
        }
    }

    public List<Map<String, Object>> getBlacklist(String username) {
        User user = getUser(username);
        return blacklistRepository.findByUser(user).stream().map(entry -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", entry.getId());
            item.put("anilistId", entry.getAnilistId());
            item.put("title", entry.getTitle());
            item.put("coverImage", entry.getCoverImage());
            item.put("createdAt", entry.getCreatedAt());
            return item;
        }).toList();
    }

    public void removeFromBlacklist(String username, Long id) {
        User user = getUser(username);
        RecommendationBlacklist entry = blacklistRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Blacklist entry not found"));

        if (!entry.getUser().getId().equals(user.getId())) {
            throw new UnauthorizedException("Not your blacklist entry");
        }
        blacklistRepository.delete(entry);
    }

    private User getUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    private List<Integer> buildExcludeIds(String username, List<Integer> seedIds) {
        Set<Integer> excluded = new LinkedHashSet<>(seedIds);

        if (username != null) {
            User user = getUser(username);
            List<AnimeListEntry> userList = animeListEntryService.getUserList(username);
            for (AnimeListEntry entry : userList) {
                excluded.add(entry.getAnilistId());
            }
            blacklistRepository.findByUser(user)
                    .forEach(entry -> excluded.add(entry.getAnilistId()));
        }

        if (excluded.isEmpty()) {
            excluded.add(-1);
        }
        return new ArrayList<>(excluded);
    }

    private float[] buildUserPreferenceVector(String username) {
        List<AnimeListEntry> userList = animeListEntryService.getUserList(username);
        if (userList.isEmpty()) {
            return null;
        }

        Map<Integer, Integer> scoreById = new HashMap<>();
        List<Integer> listIds = new ArrayList<>();
        for (AnimeListEntry entry : userList) {
            listIds.add(entry.getAnilistId());
            scoreById.put(entry.getAnilistId(), entry.getScore());
        }

        List<Object[]> rows = loadEmbeddings(listIds, true);
        if (rows.isEmpty()) {
            return null;
        }

        float[] weighted = null;
        float weightSum = 0f;
        List<float[]> allVectors = new ArrayList<>();

        for (Object[] row : rows) {
            Integer anilistId = (Integer) row[0];
            String vectorStr = (String) row[1];
            float[] vector = EmbeddingService.fromVectorString(vectorStr);
            allVectors.add(vector);

            Integer score = scoreById.get(anilistId);
            if (score == null) {
                continue;
            }

            float weight = score - 6.5f;
            if (Math.abs(weight) < 0.01f) {
                continue;
            }

            if (weighted == null) {
                weighted = new float[vector.length];
            }
            for (int i = 0; i < vector.length; i++) {
                weighted[i] += vector[i] * weight;
            }
            weightSum += Math.abs(weight);
        }

        if (weighted != null && weightSum > 0f) {
            for (int i = 0; i < weighted.length; i++) {
                weighted[i] /= weightSum;
            }
            return weighted;
        }

        return average(allVectors);
    }

    private List<Object[]> loadEmbeddings(List<Integer> ids, boolean embedMissing) {
        List<Integer> normalizedIds = normalizeIds(ids);
        if (normalizedIds.isEmpty()) {
            return List.of();
        }

        List<Object[]> rows = findEmbeddingRowsByIds(normalizedIds);
        if (!embedMissing || rows.size() >= normalizedIds.size() || useCustomVectors) {
            return rows;
        }

        Set<Integer> foundIds = new LinkedHashSet<>();
        for (Object[] row : rows) {
            foundIds.add((Integer) row[0]);
        }

        for (Integer id : normalizedIds) {
            if (!foundIds.contains(id)) {
                embedOnTheFly(id);
            }
        }

        return findEmbeddingRowsByIds(normalizedIds);
    }

    private void embedOnTheFly(Integer anilistId) {
        try {
            AniListResponse.AnimeInfo anime = aniListService.getAnimeById(anilistId);
            if (anime == null) {
                log.warn("Could not fetch anime {} from AniList", anilistId);
                return;
            }

            String embeddingText = populatorService.buildEmbeddingText(anime);
            float[] vector = embeddingService.embed(embeddingText);
            String vectorStr = EmbeddingService.toVectorString(vector);

            String titleRomaji = anime.getTitle() != null ? anime.getTitle().getRomaji() : null;
            String titleEnglish = anime.getTitle() != null ? anime.getTitle().getEnglish() : null;
            String coverImage = anime.getCoverImage() != null ? anime.getCoverImage().getLarge() : null;
            String genres = anime.getGenres() != null ? String.join(", ", anime.getGenres()) : null;
            String description = anime.getDescription() != null
                    ? anime.getDescription().replaceAll("<[^>]*>", "").trim()
                    : null;

            embeddingRepository.upsertWithEmbedding(
                    anime.getId(), titleRomaji, titleEnglish, coverImage,
                    genres, description, anime.getAverageScore(),
                    anime.getStatus(), anime.getEpisodes(),
                    embeddingText, vectorStr);
        } catch (Exception e) {
            log.error("Failed to embed anime {} on the fly: {}", anilistId, e.getMessage());
        }
    }

    private AniListResponse.AnimeInfo mapRowToAnimeInfo(Object[] row) {
        AniListResponse.AnimeInfo anime = new AniListResponse.AnimeInfo();
        anime.setId((Integer) row[1]);

        AniListResponse.AnimeTitle title = new AniListResponse.AnimeTitle();
        title.setRomaji((String) row[2]);
        title.setEnglish((String) row[3]);
        anime.setTitle(title);

        AniListResponse.AnimeCoverImage cover = new AniListResponse.AnimeCoverImage();
        cover.setLarge((String) row[4]);
        anime.setCoverImage(cover);

        anime.setGenres(row[5] != null ? List.of(((String) row[5]).split(", ")) : null);
        anime.setDescription((String) row[6]);
        anime.setAverageScore((Integer) row[7]);
        anime.setStatus((String) row[8]);
        anime.setEpisodes((Integer) row[9]);

        return anime;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0 || limit > 50) {
            return 15;
        }
        return limit;
    }

    private float normalizeListWeight(Float listWeight) {
        float value = (listWeight == null) ? 0.20f : listWeight;
        if (value < 0f) {
            return 0f;
        }
        if (value > 1f) {
            return 1f;
        }
        return value;
    }

    private List<Integer> normalizeIds(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
    }

    private float[] averageRows(List<Object[]> rows) {
        List<float[]> vectors = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            vectors.add(EmbeddingService.fromVectorString((String) row[1]));
        }
        return average(vectors);
    }

    private float[] average(List<float[]> vectors) {
        if (vectors == null || vectors.isEmpty()) {
            return null;
        }
        float[] avg = new float[vectors.get(0).length];
        for (float[] vector : vectors) {
            for (int i = 0; i < vector.length; i++) {
                avg[i] += vector[i];
            }
        }
        for (int i = 0; i < avg.length; i++) {
            avg[i] /= vectors.size();
        }
        return avg;
    }

    private float[] blend(float[] base, float[] overlay, float overlayWeight) {
        float[] blended = new float[base.length];
        for (int i = 0; i < base.length; i++) {
            blended[i] = (1f - overlayWeight) * base[i] + overlayWeight * overlay[i];
        }
        return blended;
    }

    private List<Object[]> findSimilarRows(String vectorStr, List<Integer> excludeIds, int limit) {
        if (useCustomVectors) {
            return embeddingRepository.findSimilarCustom(vectorStr, excludeIds, limit);
        }
        return embeddingRepository.findSimilar(vectorStr, excludeIds, limit);
    }

    private List<Object[]> findEmbeddingRowsByIds(List<Integer> anilistIds) {
        if (useCustomVectors) {
            return embeddingRepository.findCustomEmbeddingsByAnilistIds(anilistIds);
        }
        return embeddingRepository.findEmbeddingsByAnilistIds(anilistIds);
    }
}
