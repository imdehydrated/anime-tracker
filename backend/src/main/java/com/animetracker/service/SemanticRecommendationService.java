package com.animetracker.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.animetracker.dto.AniListResponse;
import com.animetracker.dto.RecommendationResponse;
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
 * Builds a search vector from text query and optional user-list preference vector,
 * then queries pgvector for nearest neighbors.
 */
@Service
public class SemanticRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(SemanticRecommendationService.class);
    private static final AtomicBoolean SEMANTIC_SEED_WARNING_LOGGED = new AtomicBoolean(false);

    private final EmbeddingService embeddingService;
    private final AnimeEmbeddingRepository embeddingRepository;
    private final AnimeListEntryService animeListEntryService;
    private final RecommendationBlacklistRepository blacklistRepository;
    private final UserRepository userRepository;
    private final AniListService aniListService;
    private final AnimeEmbeddingPopulatorService populatorService;
    private final MlSidecarService mlSidecarService;
    private final FusionScoringService fusionScoringService;
    @Value("${recommendations.use-custom-vectors:false}")
    private boolean useCustomVectors;
    @Value("${recommendations.default-list-weight:0.20}")
    private float defaultListWeight;
    @Value("${recommendations.default-similar-list-weight:0.00}")
    private float defaultSimilarListWeight;
    @Value("${recommendations.fusion.dynamic-blend-enabled:true}")
    private boolean dynamicBlendEnabled;
    @Value("${recommendations.fusion.dynamic-blend-min-rated-anime:10}")
    private int dynamicBlendMinRatedAnime;
    @Value("${recommendations.fusion.dynamic-blend-max-rated-anime:80}")
    private int dynamicBlendMaxRatedAnime;
    @Value("${recommendations.fusion.dynamic-blend-min-cf-weight:0.15}")
    private float dynamicBlendMinCfWeight;
    @Value("${recommendations.fusion.dynamic-blend-max-cf-weight:0.55}")
    private float dynamicBlendMaxCfWeight;
    @Value("${recommendations.explanations.cf-contributors-enabled:false}")
    private boolean cfContributorExplanationsEnabled;

    public SemanticRecommendationService(EmbeddingService embeddingService,
            AnimeEmbeddingRepository embeddingRepository,
            AnimeListEntryService animeListEntryService,
            RecommendationBlacklistRepository blacklistRepository,
            UserRepository userRepository,
            AniListService aniListService,
            AnimeEmbeddingPopulatorService populatorService,
            MlSidecarService mlSidecarService,
            FusionScoringService fusionScoringService) {
        this.embeddingService = embeddingService;
        this.embeddingRepository = embeddingRepository;
        this.animeListEntryService = animeListEntryService;
        this.blacklistRepository = blacklistRepository;
        this.userRepository = userRepository;
        this.aniListService = aniListService;
        this.populatorService = populatorService;
        this.mlSidecarService = mlSidecarService;
        this.fusionScoringService = fusionScoringService;
    }

    /**
     * Primary recommendation entrypoint used by recommendation endpoints.
     */
    public List<RecommendationResponse> recommend(
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
        boolean hasSemanticSeedInput = !normalizedSeeds.isEmpty();
        boolean hasQuery = query != null && !query.isBlank();
        boolean effectiveListOnly = useListOnly
                || (username != null
                && requestedListWeight != null
                && requestedListWeight >= 1.0f
                && !hasQuery);

        if (hasSemanticSeedInput && SEMANTIC_SEED_WARNING_LOGGED.compareAndSet(false, true)) {
            log.warn("Semantic mode now ignores seedIds for /api/users/recommendations/semantic*; use mode=similar for seed-based recommendations");
        }

        if (effectiveListOnly && username == null) {
            throw new UnauthorizedException("Login required for list-only recommendations");
        }
        if (!effectiveListOnly && !hasQuery) {
            throw new BadRequestException("Provide a text query for Smart Search");
        }

        int limit = normalizeLimit(requestedLimit);
        float listWeight = resolveListWeight(requestedListWeight, defaultListWeight);
        if (effectiveListOnly) {
            listWeight = 1.0f;
        }
        boolean usedListProfile = false;

        float[] searchVector = null;

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
                usedListProfile = true;
                searchVector = (searchVector == null)
                        ? listVector
                        : blend(searchVector, listVector, listWeight);
            }
        }

        if (searchVector == null) {
            return List.of();
        }

        List<Integer> excludeIds = buildExcludeIds(username, List.of());
        String vectorStr = EmbeddingService.toVectorString(searchVector);
        int candidateLimit = Math.min(150, Math.max(limit, limit * 3));
        List<Object[]> rows = findSimilarRows(vectorStr, excludeIds, candidateLimit);
        List<String> baseReasonCodes = buildBaseReasonCodes(false, hasQuery, usedListProfile);
        List<FusionScoringService.ScoredCandidate> semanticCandidates = new ArrayList<>();
        for (Object[] row : rows) {
            AniListResponse.AnimeInfo anime = hydrateMetadataIfMissing(mapRowToAnimeInfo(row));
            double distance = distanceFromRow(row);
            double normalizedScore = FusionScoringService.normalizeSemanticDistance(distance);
            semanticCandidates.add(new FusionScoringService.ScoredCandidate(
                    anime.getId(),
                    anime,
                    normalizedScore,
                    baseReasonCodes));
        }

        List<FusionScoringService.FusedCandidate> fused = blendSemanticWithCfIfAvailable(
                semanticCandidates, username, excludeIds, limit, requestedListWeight, listWeight);
        return finalizeCandidatesWithReasons(fused, "semantic", limit, username);
    }

    /**
     * CF-only mode: get predictions entirely from the sidecar's collaborative filtering model.
     */
    private List<RecommendationResponse> recommendCf(String username, Integer requestedLimit) {
        if (username == null) {
            throw new UnauthorizedException("Login required for CF recommendations");
        }
        if (!mlSidecarService.isEnabled()) {
            throw new BadRequestException("CF model is not available");
        }

        int limit = normalizeLimit(requestedLimit);
        Map<Integer, Float> userRatings = buildUserRatingMap(username);
        List<Integer> excludeIds = buildExcludeIds(username, List.of());
        List<WatchedProfile> watchedProfiles = cfContributorExplanationsEnabled
                ? buildWatchedProfiles(username)
                : List.of();

        List<Map<String, Object>> predictions = mlSidecarService.getCfRecommendations(
                userRatings, excludeIds, limit);

        if (predictions == null || predictions.isEmpty()) {
            throw new BadRequestException("CF model returned no predictions - your list may be too small");
        }

        // Fetch full anime info for each predicted AniList ID
        List<RecommendationResponse> results = new ArrayList<>();
        for (Map<String, Object> pred : predictions) {
            Object idValue = pred.get("anilist_id");
            if (!(idValue instanceof Number idNumber)) {
                continue;
            }
            int anilistId = idNumber.intValue();
            double predictedScore = numberValue(pred.get("predicted_score"), 1.0d);
            double watchConfidence = numberValue(pred.get("watch_confidence"), 0.0d);
            double normalizedScore = FusionScoringService.normalizeCfScore(predictedScore, watchConfidence);
            try {
                AniListResponse.AnimeInfo anime = aniListService.getAnimeById(anilistId);
                if (anime != null) {
                    List<String> reasonCodes = List.of(RecommendationResponse.CF_SIGNAL);
                    String cfContributor = findTopContributorTitle(anime, watchedProfiles);
                    String reasonSentence = buildReasonSentence("cf", reasonCodes, cfContributor);
                    applyRecommendationMeta(
                            anime,
                            normalizedScore,
                            reasonCodes,
                            reasonSentence);
                    results.add(new RecommendationResponse(anime, normalizedScore, reasonCodes));
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
    private List<RecommendationResponse> recommendSimilar(
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
        float listWeight = resolveListWeight(requestedListWeight, defaultSimilarListWeight);
        boolean usedListProfile = false;

        // Optional personalization: blend seed centroid with user's preference vector.
        if (username != null && listWeight > 0f) {
            float[] listVector = buildUserPreferenceVector(username);
            if (listVector != null) {
                usedListProfile = true;
                searchVector = blend(searchVector, listVector, listWeight);
            }
        }

        List<Integer> excludeIds = buildExcludeIds(username, normalizedSeeds);
        int limit = normalizeLimit(requestedLimit);
        String vectorStr = EmbeddingService.toVectorString(searchVector);

        // Overfetch for reranking headroom
        List<Object[]> candidates = findSimilarRows(vectorStr, excludeIds, limit * 3);
        List<FusionScoringService.ScoredCandidate> semanticCandidates;
        List<String> baseReasonCodes = buildBaseReasonCodes(true, false, usedListProfile);

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
                semanticCandidates = new ArrayList<>();
                for (Map<String, Object> item : reranked) {
                    int anilistId = ((Number) item.get("anilist_id")).intValue();
                    Object[] row = rowById.get(anilistId);
                    if (row != null) {
                        AniListResponse.AnimeInfo anime = hydrateMetadataIfMissing(mapRowToAnimeInfo(row));
                        double rerankedScore = numberValue(item.get("score"), Double.NaN);
                        double normalizedScore = Double.isNaN(rerankedScore)
                                ? FusionScoringService.normalizeSemanticDistance(distanceFromRow(row))
                                : FusionScoringService.normalizeRerankedScore(rerankedScore);
                        semanticCandidates.add(new FusionScoringService.ScoredCandidate(
                                anime.getId(),
                                anime,
                                normalizedScore,
                                baseReasonCodes));
                    }
                }
            } else {
                // Rerank failed - fall back to pgvector order
                semanticCandidates = new ArrayList<>();
                for (Object[] row : candidates.subList(0, Math.min(limit, candidates.size()))) {
                    AniListResponse.AnimeInfo anime = hydrateMetadataIfMissing(mapRowToAnimeInfo(row));
                    double normalizedScore = FusionScoringService.normalizeSemanticDistance(distanceFromRow(row));
                    semanticCandidates.add(new FusionScoringService.ScoredCandidate(
                            anime.getId(),
                            anime,
                            normalizedScore,
                            baseReasonCodes));
                }
            }
        } else {
            // No sidecar - use pgvector order directly
            semanticCandidates = new ArrayList<>();
            for (Object[] row : candidates.subList(0, Math.min(limit, candidates.size()))) {
                AniListResponse.AnimeInfo anime = hydrateMetadataIfMissing(mapRowToAnimeInfo(row));
                double normalizedScore = FusionScoringService.normalizeSemanticDistance(distanceFromRow(row));
                semanticCandidates.add(new FusionScoringService.ScoredCandidate(
                        anime.getId(),
                        anime,
                        normalizedScore,
                        baseReasonCodes));
            }
        }

        List<FusionScoringService.FusedCandidate> fused = blendSemanticWithCfIfAvailable(
                semanticCandidates, username, excludeIds, limit, requestedListWeight, listWeight);
        return finalizeCandidatesWithReasons(fused, "similar", limit, username);
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
            Integer score = entry.getScore();
            if (score != null && score > 0) {
                ratings.put(entry.getAnilistId(), score.floatValue());
            }
        }
        return ratings;
    }

    private List<WatchedProfile> buildWatchedProfiles(String username) {
        if (username == null) {
            return List.of();
        }

        List<AnimeListEntry> userList = animeListEntryService.getUserList(username);
        if (userList.isEmpty()) {
            return List.of();
        }

        List<WatchedProfile> profiles = new ArrayList<>(userList.size());
        for (AnimeListEntry entry : userList) {
            String title = entry.getTitle();
            Set<String> genres = parseGenreCsv(entry.getGenres());
            if ((title == null || title.isBlank()) && genres.isEmpty()) {
                continue;
            }

            int score = entry.getScore() == null ? 0 : entry.getScore();
            double scoreNorm = FusionScoringService.clamp(score / 10.0d, 0.0, 1.0);
            if (scoreNorm <= 0.0d) {
                scoreNorm = 0.6d;
            }

            profiles.add(new WatchedProfile(
                    title == null ? null : title.trim(),
                    genres,
                    scoreNorm));
        }

        return profiles;
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

    private AniListResponse.AnimeInfo hydrateMetadataIfMissing(AniListResponse.AnimeInfo anime) {
        if (anime == null || anime.getId() == null || !isMetadataIncomplete(anime)) {
            return anime;
        }

        try {
            AniListResponse.AnimeInfo fetched = aniListService.getAnimeById(anime.getId());
            if (fetched == null) {
                return anime;
            }

            AniListResponse.AnimeInfo merged = mergeAnimeInfo(anime, fetched);
            persistMetadata(merged);
            return merged;
        } catch (Exception e) {
            log.warn("Failed to hydrate metadata for anime {}: {}", anime.getId(), e.getMessage());
            return anime;
        }
    }

    private boolean isMetadataIncomplete(AniListResponse.AnimeInfo anime) {
        boolean missingCover = anime.getCoverImage() == null
                || anime.getCoverImage().getLarge() == null
                || anime.getCoverImage().getLarge().isBlank();
        boolean missingGenres = anime.getGenres() == null || anime.getGenres().isEmpty();
        boolean missingScore = anime.getAverageScore() == null;
        boolean missingDescription = anime.getDescription() == null || anime.getDescription().isBlank();
        boolean missingEpisodes = anime.getEpisodes() == null;
        return missingCover || missingGenres || missingScore || missingDescription || missingEpisodes;
    }

    private AniListResponse.AnimeInfo mergeAnimeInfo(AniListResponse.AnimeInfo current, AniListResponse.AnimeInfo fetched) {
        if (current.getTitle() == null) {
            current.setTitle(fetched.getTitle());
        } else if (fetched.getTitle() != null) {
            if (current.getTitle().getRomaji() == null || current.getTitle().getRomaji().isBlank()) {
                current.getTitle().setRomaji(fetched.getTitle().getRomaji());
            }
            if (current.getTitle().getEnglish() == null || current.getTitle().getEnglish().isBlank()) {
                current.getTitle().setEnglish(fetched.getTitle().getEnglish());
            }
        }

        if (current.getCoverImage() == null
                || current.getCoverImage().getLarge() == null
                || current.getCoverImage().getLarge().isBlank()) {
            current.setCoverImage(fetched.getCoverImage());
        }
        if (current.getGenres() == null || current.getGenres().isEmpty()) {
            current.setGenres(fetched.getGenres());
        }
        if (current.getDescription() == null || current.getDescription().isBlank()) {
            current.setDescription(fetched.getDescription());
        }
        if (current.getAverageScore() == null) {
            current.setAverageScore(fetched.getAverageScore());
        }
        if (current.getStatus() == null || current.getStatus().isBlank()) {
            current.setStatus(fetched.getStatus());
        }
        if (current.getEpisodes() == null) {
            current.setEpisodes(fetched.getEpisodes());
        }
        return current;
    }

    private void persistMetadata(AniListResponse.AnimeInfo anime) {
        String titleRomaji = anime.getTitle() != null ? anime.getTitle().getRomaji() : null;
        String titleEnglish = anime.getTitle() != null ? anime.getTitle().getEnglish() : null;
        String coverImage = anime.getCoverImage() != null ? anime.getCoverImage().getLarge() : null;
        String genres = (anime.getGenres() == null || anime.getGenres().isEmpty())
                ? null
                : String.join(", ", anime.getGenres());
        String description = anime.getDescription() != null
                ? anime.getDescription().replaceAll("<[^>]*>", "").trim()
                : null;

        embeddingRepository.updateMetadataByAnilistId(
                anime.getId(),
                titleRomaji,
                titleEnglish,
                coverImage,
                genres,
                description,
                anime.getAverageScore(),
                anime.getStatus(),
                anime.getEpisodes());
    }

    private List<FusionScoringService.FusedCandidate> blendSemanticWithCfIfAvailable(
            List<FusionScoringService.ScoredCandidate> semanticCandidates,
            String username,
            List<Integer> excludeIds,
            int limit,
            Float requestedListWeight,
            float resolvedListWeight) {
        if (semanticCandidates == null || semanticCandidates.isEmpty()) {
            return List.of();
        }

        CfOverlapResult cfOverlap = buildCfOverlapCandidates(
                username, excludeIds, limit, semanticCandidates);
        List<FusionScoringService.ScoredCandidate> cfOverlapCandidates = cfOverlap.candidates();
        List<FusionScoringService.FusedCandidate> fused;
        if (cfOverlapCandidates.isEmpty()) {
            fused = fusionScoringService.fuseAndRank(semanticCandidates, cfOverlapCandidates);
        } else {
            double cfWeightOverride = resolveDynamicCfBlendWeight(
                    requestedListWeight,
                    resolvedListWeight,
                    cfOverlap.ratedAnimeCount());
            double semanticWeightOverride = 1.0d - cfWeightOverride;
            fused = fusionScoringService.fuseAndRank(
                    semanticCandidates,
                    cfOverlapCandidates,
                    semanticWeightOverride,
                    cfWeightOverride);
        }

        if (!cfOverlapCandidates.isEmpty()) {
            fused = fusionScoringService.applyDiversityPass(fused);
        }

        return fused;
    }

    private CfOverlapResult buildCfOverlapCandidates(
            String username,
            List<Integer> excludeIds,
            int limit,
            List<FusionScoringService.ScoredCandidate> semanticCandidates) {
        if (username == null || !mlSidecarService.isEnabled() || semanticCandidates == null || semanticCandidates.isEmpty()) {
            return new CfOverlapResult(List.of(), 0);
        }

        Map<Integer, Float> userRatings = buildUserRatingMap(username);
        if (userRatings.isEmpty()) {
            return new CfOverlapResult(List.of(), 0);
        }

        Set<Integer> semanticIds = new LinkedHashSet<>();
        Map<Integer, AniListResponse.AnimeInfo> animeById = new HashMap<>();
        for (FusionScoringService.ScoredCandidate candidate : semanticCandidates) {
            semanticIds.add(candidate.anilistId());
            animeById.put(candidate.anilistId(), candidate.animeInfo());
        }

        int cfTopK = Math.max(limit, limit * Math.max(1, fusionScoringService.getCfCandidateMultiplier()));
        List<Map<String, Object>> predictions = mlSidecarService.getCfRecommendations(userRatings, excludeIds, cfTopK);
        if (predictions == null || predictions.isEmpty()) {
            return new CfOverlapResult(List.of(), userRatings.size());
        }

        List<FusionScoringService.ScoredCandidate> cfOverlapCandidates = new ArrayList<>();
        for (Map<String, Object> pred : predictions) {
            Object idValue = pred.get("anilist_id");
            if (!(idValue instanceof Number idNumber)) {
                continue;
            }
            int anilistId = idNumber.intValue();
            if (!semanticIds.contains(anilistId)) {
                continue;
            }

            AniListResponse.AnimeInfo anime = animeById.get(anilistId);
            if (anime == null) {
                continue;
            }

            double predictedScore = numberValue(pred.get("predicted_score"), 1.0d);
            double watchConfidence = numberValue(pred.get("watch_confidence"), 0.0d);
            double normalizedScore = FusionScoringService.normalizeCfScore(predictedScore, watchConfidence);

            cfOverlapCandidates.add(new FusionScoringService.ScoredCandidate(
                    anilistId,
                    anime,
                    normalizedScore,
                    List.of(RecommendationResponse.CF_SIGNAL)));
        }
        return new CfOverlapResult(cfOverlapCandidates, userRatings.size());
    }

    private double resolveDynamicCfBlendWeight(
            Float requestedListWeight,
            float resolvedListWeight,
            int ratedAnimeCount) {
        // Explicit request keeps existing API behavior predictable.
        if (requestedListWeight != null) {
            return clampListWeight(requestedListWeight);
        }
        if (!dynamicBlendEnabled) {
            return clampListWeight(resolvedListWeight);
        }

        float minWeight = clampListWeight(dynamicBlendMinCfWeight);
        float maxWeight = clampListWeight(dynamicBlendMaxCfWeight);
        if (maxWeight < minWeight) {
            float tmp = minWeight;
            minWeight = maxWeight;
            maxWeight = tmp;
        }

        int minCount = Math.max(0, dynamicBlendMinRatedAnime);
        int maxCount = Math.max(minCount + 1, dynamicBlendMaxRatedAnime);
        double t = (double) (ratedAnimeCount - minCount) / (double) (maxCount - minCount);
        t = FusionScoringService.clamp(t, 0.0, 1.0);

        return FusionScoringService.clamp(minWeight + ((maxWeight - minWeight) * t), 0.0, 1.0);
    }

    private List<String> buildBaseReasonCodes(boolean hasSeeds, boolean hasQuery, boolean usedListProfile) {
        List<String> reasonCodes = new ArrayList<>(4);
        if (hasSeeds) {
            reasonCodes.add(RecommendationResponse.SIMILAR_TO_SEED);
        }
        if (hasQuery) {
            reasonCodes.add(RecommendationResponse.MATCHES_QUERY);
        }
        if (usedListProfile) {
            reasonCodes.add(RecommendationResponse.MATCHES_TASTE_PROFILE);
        }
        return reasonCodes;
    }

    private List<RecommendationResponse> finalizeCandidatesWithReasons(
            List<FusionScoringService.FusedCandidate> fusedCandidates,
            String mode,
            int limit,
            String username) {
        if (fusedCandidates == null || fusedCandidates.isEmpty()) {
            return List.of();
        }

        List<RecommendationResponse> results = new ArrayList<>();
        int effectiveLimit = Math.min(limit, fusedCandidates.size());
        List<FusionScoringService.FusedCandidate> topCandidates = fusedCandidates.subList(0, effectiveLimit);
        Map<Integer, String> cfContributorsByAnimeId = buildCfContributorHints(username, topCandidates);
        for (FusionScoringService.FusedCandidate fused : topCandidates) {
            AniListResponse.AnimeInfo anime = fused.animeInfo();
            List<String> reasonCodes = fused.reasonCodes();
            String cfContributor = anime == null ? null : cfContributorsByAnimeId.get(anime.getId());
            String reason = buildReasonSentence(mode, reasonCodes, cfContributor);
            applyRecommendationMeta(anime, fused.fusionScore(), reasonCodes, reason);
            results.add(new RecommendationResponse(anime, fused.fusionScore(), reasonCodes));
        }
        return results;
    }

    private Map<Integer, String> buildCfContributorHints(
            String username,
            List<FusionScoringService.FusedCandidate> fusedCandidates) {
        if (!cfContributorExplanationsEnabled || username == null || fusedCandidates == null || fusedCandidates.isEmpty()) {
            return Map.of();
        }

        List<WatchedProfile> watchedProfiles = buildWatchedProfiles(username);
        if (watchedProfiles.isEmpty()) {
            return Map.of();
        }

        Map<Integer, String> contributorByAnimeId = new HashMap<>();
        for (FusionScoringService.FusedCandidate fused : fusedCandidates) {
            if (fused == null || fused.animeInfo() == null || fused.reasonCodes() == null) {
                continue;
            }
            if (!fused.reasonCodes().contains(RecommendationResponse.CF_SIGNAL)) {
                continue;
            }
            Integer animeId = fused.animeInfo().getId();
            if (animeId == null) {
                continue;
            }

            String topContributor = findTopContributorTitle(fused.animeInfo(), watchedProfiles);
            if (topContributor != null && !topContributor.isBlank()) {
                contributorByAnimeId.put(animeId, topContributor);
            }
        }

        return contributorByAnimeId;
    }

    private String findTopContributorTitle(AniListResponse.AnimeInfo candidate, List<WatchedProfile> watchedProfiles) {
        if (candidate == null || watchedProfiles == null || watchedProfiles.isEmpty()) {
            return null;
        }

        Set<String> candidateGenres = parseGenreList(candidate.getGenres());
        double bestScore = -1.0d;
        String bestTitle = null;

        for (WatchedProfile profile : watchedProfiles) {
            if (profile == null || profile.title() == null || profile.title().isBlank()) {
                continue;
            }

            double similarity = genreJaccard(candidateGenres, profile.genres());
            // Keep some score signal even for sparse/noisy genre metadata.
            double matchScore = (0.85d * similarity) + (0.15d * profile.scoreNorm());
            if (matchScore > bestScore) {
                bestScore = matchScore;
                bestTitle = profile.title();
            }
        }

        return bestTitle;
    }

    private Set<String> parseGenreCsv(String genresCsv) {
        if (genresCsv == null || genresCsv.isBlank()) {
            return Set.of();
        }

        Set<String> genres = new LinkedHashSet<>();
        for (String token : genresCsv.split(",")) {
            if (token == null) {
                continue;
            }
            String normalized = token.trim().toLowerCase();
            if (!normalized.isBlank()) {
                genres.add(normalized);
            }
        }
        return genres.isEmpty() ? Set.of() : Set.copyOf(genres);
    }

    private Set<String> parseGenreList(List<String> genreList) {
        if (genreList == null || genreList.isEmpty()) {
            return Set.of();
        }

        Set<String> genres = new LinkedHashSet<>();
        for (String token : genreList) {
            if (token == null) {
                continue;
            }
            String normalized = token.trim().toLowerCase();
            if (!normalized.isBlank()) {
                genres.add(normalized);
            }
        }
        return genres.isEmpty() ? Set.of() : Set.copyOf(genres);
    }

    private double genreJaccard(Set<String> left, Set<String> right) {
        if (left == null || left.isEmpty() || right == null || right.isEmpty()) {
            return 0.0d;
        }

        int intersection = 0;
        for (String genre : left) {
            if (right.contains(genre)) {
                intersection++;
            }
        }

        int union = left.size() + right.size() - intersection;
        if (union <= 0) {
            return 0.0d;
        }
        return (double) intersection / (double) union;
    }

    private void applyRecommendationMeta(
            AniListResponse.AnimeInfo anime,
            Double fusionScore,
            List<String> reasonCodes,
            String reasonSentence) {
        if (anime == null) {
            return;
        }

        anime.setFusionScore(fusionScore);
        anime.setReasonCodes((reasonCodes == null || reasonCodes.isEmpty()) ? null : List.copyOf(reasonCodes));
        anime.setRecommendationReason(reasonSentence);
    }

    private String buildReasonSentence(String mode, List<String> reasonCodes, String cfContributor) {
        Set<String> codes = new LinkedHashSet<>();
        if (reasonCodes != null) {
            codes.addAll(reasonCodes);
        }

        List<String> clauses = new ArrayList<>(4);
        if (codes.contains(RecommendationResponse.SIMILAR_TO_SEED)) {
            clauses.add("it is similar to your selected seed anime");
        }
        if (codes.contains(RecommendationResponse.MATCHES_QUERY)) {
            clauses.add("it matches your search description");
        }
        if (codes.contains(RecommendationResponse.MATCHES_TASTE_PROFILE)) {
            clauses.add("it aligns with your rating history");
        }
        if (codes.contains(RecommendationResponse.CF_SIGNAL)) {
            if (cfContributor != null && !cfContributor.isBlank()) {
                clauses.add("users with similar rating patterns and your interest in " + cfContributor + " both point to it");
            } else {
                clauses.add("users with similar rating patterns also favor it");
            }
        }

        if (clauses.isEmpty()) {
            if ("cf".equals(mode)) {
                return "Recommended because collaborative filtering found a strong match for your profile.";
            }
            if ("similar".equals(mode)) {
                return "Recommended because it is close to your selected seed anime.";
            }
            return "Recommended because it matches your current recommendation signals.";
        }

        return "Recommended because " + joinClauses(clauses) + ".";
    }

    private String joinClauses(List<String> clauses) {
        if (clauses.size() == 1) {
            return clauses.get(0);
        }
        if (clauses.size() == 2) {
            return clauses.get(0) + " and " + clauses.get(1);
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < clauses.size(); i++) {
            if (i == clauses.size() - 1) {
                sb.append("and ").append(clauses.get(i));
            } else {
                sb.append(clauses.get(i)).append(", ");
            }
        }
        return sb.toString();
    }

    private double distanceFromRow(Object[] row) {
        if (row != null && row.length > 13 && row[13] instanceof Number distance) {
            return distance.doubleValue();
        }
        return 1.0d;
    }

    private double numberValue(Object value, double fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return fallback;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0 || limit > 50) {
            return 15;
        }
        return limit;
    }

    private float resolveListWeight(Float requestedListWeight, float defaultWeight) {
        float value = (requestedListWeight == null) ? defaultWeight : requestedListWeight;
        return clampListWeight(value);
    }

    private float clampListWeight(float value) {
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

    private record CfOverlapResult(
            List<FusionScoringService.ScoredCandidate> candidates,
            int ratedAnimeCount) {
    }

    private record WatchedProfile(
            String title,
            Set<String> genres,
            double scoreNorm) {
    }
}
