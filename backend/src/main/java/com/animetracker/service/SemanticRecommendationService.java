package com.animetracker.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.animetracker.dto.AniListResponse;
import com.animetracker.dto.RecommendationResponse;
import com.animetracker.dto.SemanticRequest;
import com.animetracker.entity.AnimeListEntry;
import com.animetracker.entity.RecommendationBlacklist;
import com.animetracker.entity.User;
import com.animetracker.exception.BadRequestException;
import com.animetracker.exception.NotFoundException;
import com.animetracker.exception.UnauthorizedException;
import com.animetracker.repository.AnimeEmbeddingRepository;
import com.animetracker.repository.CustomEmbeddingImportStateRepository;
import com.animetracker.repository.RecommendationBlacklistRepository;
import com.animetracker.repository.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Semantic recommendation engine.
 * Builds a search vector from text query and optional user-list preference vector,
 * then queries pgvector for nearest neighbors.
 */
@Service
public class SemanticRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(SemanticRecommendationService.class);
    private static final AtomicBoolean SEMANTIC_SEED_WARNING_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean POPULARITY_COVERAGE_WARNING_LOGGED = new AtomicBoolean(false);
    private static final Map<String, String> QUERY_SYNONYMS = buildQuerySynonyms();
    private static final Set<String> QUERY_STOP_WORDS = Set.of(
            "anime", "show", "shows", "with", "about", "that", "this", "and", "the", "for", "from");
    private static final Set<String> QUERY_NEGATION_TOKENS = Set.of("not", "no", "without", "exclude", "excluding");
    private static final Set<String> QUERY_NEGATION_BREAK_TOKENS = Set.of("and", "or", "but", "except");
    private static final Set<String> DEDUPE_SPECIAL_MARKERS = Set.of(
            "special", "ova", "ona", "movie", "film", "recap", "summary", "compilation", "digest");
    private static final Set<String> ADULT_BLOCKLIST_TAG_KEYWORDS = Set.of(
            "hentai", "nudity", "sex", "sexual", "erotic", "porn", "explicit");
    private static final Set<String> MUSIC_KEYWORDS = Set.of("music", "song", "idol", "concert");
    private static final Set<String> SEASON_ORDINAL_WORDS = Set.of(
            "second",
            "third",
            "fourth",
            "fifth",
            "sixth",
            "seventh",
            "eighth",
            "ninth",
            "tenth");

    private final AnimeEmbeddingRepository embeddingRepository;
    private final AnimeListEntryService animeListEntryService;
    private final RecommendationBlacklistRepository blacklistRepository;
    private final UserRepository userRepository;
    private final AniListService aniListService;
    private final AnimeEmbeddingPopulatorService populatorService;
    private final MlSidecarService mlSidecarService;
    private final CustomEmbeddingImportStateRepository customEmbeddingImportStateRepository;
    private final HttpClient explanationHttpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final ObjectMapper explanationObjectMapper = new ObjectMapper();
    @Value("${recommendations.use-custom-vectors:true}")
    private boolean useCustomVectors;
    @Value("${recommendations.default-list-weight:0.20}")
    private float defaultListWeight;
    @Value("${recommendations.default-similar-list-weight:0.00}")
    private float defaultSimilarListWeight;
    @Value("${recommendations.semantic.lexical-enabled:true}")
    private boolean semanticLexicalEnabled;
    @Value("${recommendations.semantic.lexical-candidate-limit:60}")
    private int semanticLexicalCandidateLimit;
    @Value("${recommendations.semantic.vector-candidate-limit:140}")
    private int semanticVectorCandidateLimit;
    @Value("${recommendations.semantic.merged-candidate-limit:140}")
    private int semanticMergedCandidateLimit;
    @Value("${recommendations.semantic.lexical-max-patterns:3}")
    private int semanticLexicalMaxPatterns;
    @Value("${recommendations.semantic.lexical-boost:0.08}")
    private float semanticLexicalBoost;
    @Value("${recommendations.semantic.lexical-rrf-k:60}")
    private int semanticLexicalRrfK;
    @Value("${recommendations.semantic.lexical-vector-weight:1.00}")
    private float semanticLexicalVectorWeight;
    @Value("${recommendations.semantic.lexical-weight:0.90}")
    private float semanticLexicalWeight;
    @Value("${recommendations.semantic.title-intent-lexical-boost:1.20}")
    private float semanticTitleIntentLexicalBoost;
    @Value("${recommendations.semantic.rerank-enabled:true}")
    private boolean semanticRerankEnabled;
    @Value("${recommendations.semantic.rerank-top-k:60}")
    private int semanticRerankTopK;
    @Value("${recommendations.semantic.similar-candidate-limit:90}")
    private int similarCandidateLimit;
    @Value("${recommendations.semantic.dedupe-enabled:true}")
    private boolean semanticDedupeEnabled;
    @Value("${recommendations.semantic.dedupe-max-per-franchise:1}")
    private int semanticDedupeMaxPerFranchise;
    @Value("${recommendations.semantic.dedupe-suppress-specials:true}")
    private boolean semanticDedupeSuppressSpecials;
    @Value("${recommendations.semantic.score-calibration-enabled:true}")
    private boolean semanticScoreCalibrationEnabled;
    @Value("${recommendations.semantic.score-calibration-temperature:1.00}")
    private float semanticScoreCalibrationTemperature;
    @Value("${recommendations.semantic.popularity-prior-enabled:true}")
    private boolean semanticPopularityPriorEnabled;
    @Value("${recommendations.semantic.popularity-prior-weight-logged-in:0.10}")
    private float semanticPopularityPriorWeightLoggedIn;
    @Value("${recommendations.semantic.popularity-prior-weight-logged-out:0.15}")
    private float semanticPopularityPriorWeightLoggedOut;
    @Value("${recommendations.semantic.taste-weight-logged-in:0.15}")
    private float semanticTasteWeightLoggedIn;
    @Value("${recommendations.semantic.taste-weight-logged-in-broad-query:0.18}")
    private float semanticTasteWeightLoggedInBroadQuery;
    @Value("${recommendations.semantic.popularity-prior-weight-logged-in-broad-query:0.15}")
    private float semanticPopularityPriorWeightLoggedInBroadQuery;
    @Value("${recommendations.semantic.popularity-prior-weight-logged-out-broad-query:0.25}")
    private float semanticPopularityPriorWeightLoggedOutBroadQuery;
    @Value("${recommendations.semantic.popularity-prior-guardrail-threshold:0.45}")
    private float semanticPopularityGuardrailThreshold;
    @Value("${recommendations.semantic.popularity-prior-guardrail-max-weight:0.05}")
    private float semanticPopularityGuardrailMaxWeight;
    @Value("${recommendations.semantic.broad-query-low-quality-score-threshold:72}")
    private int semanticBroadQueryLowQualityScoreThreshold;
    @Value("${recommendations.semantic.broad-query-low-quality-popularity-threshold:15000}")
    private int semanticBroadQueryLowQualityPopularityThreshold;
    @Value("${recommendations.semantic.broad-query-low-quality-penalty:0.88}")
    private float semanticBroadQueryLowQualityPenalty;
    @Value("${recommendations.semantic.popularity-prior-normalization-power:2.0}")
    private float semanticPopularityPriorNormalizationPower;
    @Value("${recommendations.semantic.list-blend-cap-with-query:0.08}")
    private float semanticListBlendCapWithQuery;
    @Value("${recommendations.semantic.list-blend-cap-broad-query:0.12}")
    private float semanticListBlendCapBroadQuery;
    @Value("${recommendations.semantic.list-blend-cap-title-intent:0.05}")
    private float semanticListBlendCapTitleIntent;
    @Value("${recommendations.semantic.second-pass-enabled:true}")
    private boolean semanticSecondPassEnabled;
    @Value("${recommendations.semantic.second-pass-context-size:25}")
    private int semanticSecondPassContextSize;
    @Value("${recommendations.semantic.second-pass-max-added-tokens:8}")
    private int semanticSecondPassMaxAddedTokens;
    @Value("${recommendations.semantic.second-pass-trigger-max-query-tokens:5}")
    private int semanticSecondPassTriggerMaxQueryTokens;
    @Value("${recommendations.semantic.second-pass-skip-top-relevance-threshold:0.80}")
    private float semanticSecondPassSkipTopRelevanceThreshold;
    @Value("${recommendations.semantic.cache-enabled:true}")
    private boolean semanticCacheEnabled;
    @Value("${recommendations.semantic.cache-size:2000}")
    private int semanticCacheSize;
    @Value("${recommendations.semantic.cache-ttl-hours:6}")
    private int semanticCacheTtlHours;
    @Value("${recommendations.semantic.model-fingerprint:semantic-v1}")
    private String semanticModelFingerprint;
    @Value("${recommendations.filters.popularity-attenuation-low:0.00}")
    private float popularityAttenuationLow;
    @Value("${recommendations.filters.popularity-attenuation-medium:0.08}")
    private float popularityAttenuationMedium;
    @Value("${recommendations.filters.popularity-attenuation-high:0.16}")
    private float popularityAttenuationHigh;
    @Value("${recommendations.filters.cf-popularity-attenuation-low:0.00}")
    private float cfPopularityAttenuationLow;
    @Value("${recommendations.filters.cf-popularity-attenuation-medium:0.05}")
    private float cfPopularityAttenuationMedium;
    @Value("${recommendations.filters.cf-popularity-attenuation-high:0.10}")
    private float cfPopularityAttenuationHigh;
    @Value("${recommendations.filters.underfill-min-ratio:0.60}")
    private float controlsUnderfillMinRatio;
    @Value("${recommendations.filters.underfill-min-floor:5}")
    private int controlsUnderfillMinFloor;
    @Value("${recommendations.explanations.cf-contributors-enabled:false}")
    private boolean cfContributorExplanationsEnabled;
    @Value("${recommendations.explanations.llm-enabled:false}")
    private boolean llmExplanationsEnabled;
    @Value("${recommendations.explanations.provider:deterministic}")
    private String explanationProvider;
    @Value("${recommendations.explanations.openai-api-key:}")
    private String openAiExplanationApiKey;
    @Value("${recommendations.explanations.openai-base-url:https://api.openai.com/v1}")
    private String openAiExplanationBaseUrl;
    @Value("${recommendations.explanations.openai-model:gpt-4o-mini}")
    private String openAiExplanationModel;
    @Value("${recommendations.explanations.openai-timeout-ms:2500}")
    private int openAiExplanationTimeoutMs;
    @Value("${recommendations.explanations.ollama-base-url:}")
    private String ollamaExplanationBaseUrl;
    @Value("${recommendations.explanations.ollama-model:llama3.2:3b}")
    private String ollamaExplanationModel;
    @Value("${recommendations.explanations.ollama-timeout-ms:2500}")
    private int ollamaExplanationTimeoutMs;
    @Value("${recommendations.explanations.llm-max-rewrites-per-request:5}")
    private int llmMaxRewritesPerRequest;
    @Value("${recommendations.explanations.llm-cache-size:2000}")
    private int llmReasonCacheSize;
    @Value("${recommendations.metadata-hydration-cache-size:5000}")
    private int metadataHydrationCacheSize;
    private final Map<String, String> llmReasonCache = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > Math.max(1, llmReasonCacheSize);
                }
            });
    private final Map<Integer, AniListResponse.AnimeInfo> metadataHydrationCache = Collections.synchronizedMap(
            new LinkedHashMap<>(512, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, AniListResponse.AnimeInfo> eldest) {
                    return size() > Math.max(1, metadataHydrationCacheSize);
                }
            });
    private final Map<SemanticCacheKey, CachedSemanticResults> semanticResponseCache = Collections.synchronizedMap(
            new LinkedHashMap<>(512, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<SemanticCacheKey, CachedSemanticResults> eldest) {
                    boolean shouldEvict = size() > Math.max(1, semanticCacheSize);
                    if (shouldEvict) {
                        semanticCacheEvictions.incrementAndGet();
                    }
                    return shouldEvict;
                }
            });
    private final AtomicLong semanticCacheHits = new AtomicLong(0L);
    private final AtomicLong semanticCacheMisses = new AtomicLong(0L);
    private final AtomicLong semanticCacheStaleInvalidations = new AtomicLong(0L);
    private final AtomicLong semanticCacheEvictions = new AtomicLong(0L);

    public SemanticRecommendationService(AnimeEmbeddingRepository embeddingRepository,
            AnimeListEntryService animeListEntryService,
            RecommendationBlacklistRepository blacklistRepository,
            UserRepository userRepository,
            AniListService aniListService,
            AnimeEmbeddingPopulatorService populatorService,
            MlSidecarService mlSidecarService,
            CustomEmbeddingImportStateRepository customEmbeddingImportStateRepository) {
        this.embeddingRepository = embeddingRepository;
        this.animeListEntryService = animeListEntryService;
        this.blacklistRepository = blacklistRepository;
        this.userRepository = userRepository;
        this.aniListService = aniListService;
        this.populatorService = populatorService;
        this.mlSidecarService = mlSidecarService;
        this.customEmbeddingImportStateRepository = customEmbeddingImportStateRepository;
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
        return recommend(
                username,
                seedIds,
                query,
                requestedLimit,
                useListOnly,
                requestedListWeight,
                mode,
                null);
    }

    public List<RecommendationResponse> recommend(
            String username,
            List<Integer> seedIds,
            String query,
            Integer requestedLimit,
            boolean useListOnly,
            Float requestedListWeight,
            String mode,
            SemanticRequest.Filters filters) {

        RecommendationControls controls = resolveRecommendationControls(filters);
        String effectiveMode = (mode == null || mode.isBlank())
                ? "semantic"
                : mode.trim().toLowerCase();
        Float effectiveRequestedListWeight = normalizeRequestedListWeightForUser(username, requestedListWeight);

        // CF-only mode: delegate entirely to sidecar
        if ("cf".equals(effectiveMode)) {
            return recommendCf(username, requestedLimit, controls);
        }

        // Similar mode: seed-driven "shows like these", with optional list influence
        if ("similar".equals(effectiveMode)) {
            return recommendSimilar(username, seedIds, requestedLimit, effectiveRequestedListWeight, controls);
        }

        List<Integer> normalizedSeeds = normalizeIds(seedIds);
        boolean hasSemanticSeedInput = !normalizedSeeds.isEmpty();
        String normalizedQuery = preprocessSemanticQuery(query);
        boolean hasQuery = !normalizedQuery.isBlank();
        boolean broadDiscoveryQuery = isBroadDiscoveryQuery(normalizedQuery, hasQuery);
        boolean titleIntentQuery = isLikelyTitleIntentQuery(normalizedQuery);
        List<String> queryKeywords = hasQuery ? extractQueryKeywords(normalizedQuery, 3) : List.of();
        boolean effectiveListOnly = useListOnly
                || (username != null
                && effectiveRequestedListWeight != null
                && effectiveRequestedListWeight >= 1.0f
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
        SemanticCacheKey semanticCacheKey = null;
        if (semanticCacheEnabled) {
            semanticCacheKey = buildSemanticCacheKey(
                    "semantic",
                    normalizedQuery,
                    limit,
                    semanticRerankTopK,
                    username != null,
                    buildSemanticUserProfileFingerprint(username, effectiveRequestedListWeight),
                    resolveSemanticModelFingerprint(),
                    resolveEmbeddingsFingerprint(),
                    controls.fingerprint());
            List<RecommendationResponse> cached = readSemanticCache(semanticCacheKey);
            if (cached != null) {
                return cached;
            }
        }

        float listWeight = (username == null)
                ? 0f
                : resolveListWeight(effectiveRequestedListWeight, defaultListWeight);
        if (effectiveListOnly) {
            listWeight = 1.0f;
        }
        float effectiveListBlendWeight = resolveSemanticListBlendWeight(
                listWeight,
                effectiveListOnly,
                hasQuery,
                broadDiscoveryQuery,
                titleIntentQuery);
        boolean usedListProfile = false;

        float[] searchVector = null;

        if (hasQuery) {
            if (normalizedQuery.length() > 500) {
                normalizedQuery = normalizedQuery.substring(0, 500);
            }
            // Use local sidecar custom embeddings.
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
                        : blend(searchVector, listVector, effectiveListBlendWeight);
            }
        }
        List<String> topTasteGenres = (username != null && usedListProfile)
                ? buildTopUserGenres(username, 3)
                : List.of();

        if (searchVector == null) {
            return List.of();
        }

        List<Integer> excludeIds = buildExcludeIds(username, List.of());
        String vectorStr = EmbeddingService.toVectorString(searchVector);
        int controlsCandidateFloor = controls.recommendedCandidateFloor(limit);
        int vectorCandidateLimit = Math.max(Math.max(limit, semanticVectorCandidateLimit), controlsCandidateFloor);
        int mergedCandidateLimit = Math.max(Math.max(limit, semanticMergedCandidateLimit), controlsCandidateFloor);
        SemanticRowSelection rowSelection = selectSemanticRows(
                vectorStr,
                excludeIds,
                vectorCandidateLimit,
                mergedCandidateLimit,
                normalizedQuery);
        List<String> baseReasonCodes = buildBaseReasonCodes(false, hasQuery, usedListProfile);
        List<FusionScoringService.ScoredCandidate> semanticCandidates = buildSemanticCandidatesFromRows(
                rowSelection.rows(),
                searchVector,
                baseReasonCodes,
                rowSelection.lexicalBoostIds(),
                semanticRerankEnabled,
                semanticRerankTopK,
                username != null);
        semanticCandidates = applyQueryScoreCalibration(semanticCandidates, hasQuery);
        semanticCandidates = applyModeBlendedScoring(
                semanticCandidates,
                "semantic",
                username != null,
                topTasteGenres,
                broadDiscoveryQuery);
        List<FusionScoringService.FusedCandidate> fused = toFusedCandidates(semanticCandidates);
        List<RecommendationResponse> results = finalizeCandidatesWithReasons(
                fused,
                "semantic",
                limit,
                username,
                new ReasoningContext(queryKeywords, topTasteGenres, List.of()),
                true);
        results = applyRecommendationControls(results, controls, "semantic", limit);
        writeSemanticCache(semanticCacheKey, results);
        return results;
    }

    /**
     * CF-only mode: get predictions entirely from the sidecar's collaborative filtering model.
     */
    private List<RecommendationResponse> recommendCf(
            String username,
            Integer requestedLimit,
            RecommendationControls controls) {
        if (username == null) {
            throw new UnauthorizedException("Login required for CF recommendations");
        }
        if (!mlSidecarService.isEnabled()) {
            throw new BadRequestException("CF model is not available");
        }

        int limit = normalizeLimit(requestedLimit);
        Map<Integer, Float> userRatings = buildUserRatingMap(username);
        List<Integer> excludeIds = buildExcludeIds(username, List.of());
        List<WatchedProfile> watchedProfiles = buildWatchedProfiles(username);

        int cfFetchLimit = Math.max(limit, Math.min(50, controls.recommendedCandidateFloor(limit)));
        List<Map<String, Object>> predictions = mlSidecarService.getCfRecommendations(
                userRatings, excludeIds, cfFetchLimit);

        if (predictions == null || predictions.isEmpty()) {
            throw new BadRequestException("CF model returned no predictions - your list may be too small");
        }

        List<Integer> predictedIds = new ArrayList<>(predictions.size());
        for (Map<String, Object> pred : predictions) {
            Object idValue = pred.get("anilist_id");
            if (idValue instanceof Number idNumber) {
                predictedIds.add(idNumber.intValue());
            }
        }

        Map<Integer, AniListResponse.AnimeInfo> localMetadataById = new HashMap<>();
        if (!predictedIds.isEmpty()) {
            List<Object[]> metadataRows = embeddingRepository.findMetadataByAnilistIds(predictedIds);
            for (Object[] row : metadataRows) {
                AniListResponse.AnimeInfo anime = mapMetadataRowToAnimeInfo(row);
                if (anime != null && anime.getId() != null) {
                    localMetadataById.put(anime.getId(), anime);
                }
            }
        }

        // Build recommendation payload; use local metadata first, AniList as fallback only when missing locally.
        List<RecommendationResponse> results = new ArrayList<>();
        List<String> topTasteGenres = buildTopUserGenres(username, 3);
        int explanationRewriteIndex = 0;
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
                AniListResponse.AnimeInfo anime = localMetadataById.get(anilistId);
                if (anime == null) {
                    anime = aniListService.getAnimeById(anilistId);
                }
                if (anime != null) {
                    List<String> reasonCodes = List.of(RecommendationResponse.CF_SIGNAL);
                    List<String> contributorTitles = findTopContributorTitles(anime, watchedProfiles, 5);
                    String reasonSentence = buildCfReasonSentence(
                            anime,
                            contributorTitles,
                            topTasteGenres,
                            canUseLlmForIndex(explanationRewriteIndex));
                    applyRecommendationMeta(
                            anime,
                            reasonCodes,
                            reasonSentence);
                    results.add(new RecommendationResponse(anime, normalizedScore, reasonCodes));
                    explanationRewriteIndex++;
                }
            } catch (Exception e) {
                log.warn("Failed to fetch anime {} for CF result: {}", anilistId, e.getMessage());
            }
        }
        return applyRecommendationControls(results, controls, "cf", limit);
    }

    /**
     * Similar mode: seed-based similarity with optional user-list influence.
     */
    private List<RecommendationResponse> recommendSimilar(
            String username,
            List<Integer> seedIds,
            Integer requestedLimit,
            Float requestedListWeight,
            RecommendationControls controls) {

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
        List<String> seedTitles = extractSeedTitlesByIds(normalizedSeeds, 5);

        float[] searchVector = averageRows(seedRows);
        // Similar mode should only use list personalization when explicitly requested.
        Float effectiveRequestedListWeight = normalizeRequestedListWeightForUser(username, requestedListWeight);
        float listWeight = (username == null)
                ? 0f
                : resolveListWeight(effectiveRequestedListWeight, defaultSimilarListWeight);
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
        int similarPoolLimit = Math.max(Math.max(limit, similarCandidateLimit), controls.recommendedCandidateFloor(limit));
        List<Object[]> candidates = findSimilarRows(vectorStr, excludeIds, similarPoolLimit);
        List<FusionScoringService.ScoredCandidate> semanticCandidates;
        List<String> baseReasonCodes = buildBaseReasonCodes(true, false, usedListProfile);

        // Sidecar reranking expects custom 384-dim vectors.
        if (useCustomVectors && mlSidecarService.isEnabled() && !candidates.isEmpty()) {
            // Extract candidate IDs and cosine distances for reranking
            List<Integer> candidateIds = new ArrayList<>();
            List<Double> candidateDistances = new ArrayList<>();
            Map<Integer, Object[]> rowById = new HashMap<>();

            for (Object[] row : candidates) {
                Integer anilistId = (Integer) row[1];
                Double distance = distanceFromRow(row);
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
                        double rerankedScore = numberValue(item.get("score"), Double.NaN);
                        double adherenceScore = numberValue(item.get("query_adherence_score"), Double.NaN);
                        FusionScoringService.ScoredCandidate candidate = toSemanticCandidate(
                                row,
                                rerankedScore,
                                adherenceScore,
                                baseReasonCodes,
                                Set.of(),
                                username != null);
                        if (candidate != null) {
                            semanticCandidates.add(candidate);
                        }
                    }
                }
            } else {
                // Rerank failed - fall back to pgvector order
                semanticCandidates = new ArrayList<>();
                for (Object[] row : candidates.subList(0, Math.min(limit, candidates.size()))) {
                    FusionScoringService.ScoredCandidate candidate = toSemanticCandidate(
                            row,
                            Double.NaN,
                            Double.NaN,
                            baseReasonCodes,
                            Set.of(),
                            username != null);
                    if (candidate != null) {
                        semanticCandidates.add(candidate);
                    }
                }
            }
        } else {
            // No sidecar - use pgvector order directly
            semanticCandidates = new ArrayList<>();
            for (Object[] row : candidates.subList(0, Math.min(limit, candidates.size()))) {
                FusionScoringService.ScoredCandidate candidate = toSemanticCandidate(
                        row,
                        Double.NaN,
                        Double.NaN,
                        baseReasonCodes,
                        Set.of(),
                        username != null);
                if (candidate != null) {
                    semanticCandidates.add(candidate);
                }
            }
        }

        List<String> topTasteGenres = (username != null && usedListProfile)
                ? buildTopUserGenres(username, 3)
                : List.of();
        semanticCandidates = applyModeBlendedScoring(
                semanticCandidates,
                "similar",
                username != null,
                topTasteGenres,
                false);
        List<FusionScoringService.FusedCandidate> fused = toFusedCandidates(semanticCandidates);
        List<RecommendationResponse> results = finalizeCandidatesWithReasons(
                fused,
                "similar",
                limit,
                username,
                new ReasoningContext(List.of(), topTasteGenres, seedTitles),
                true);
        return applyRecommendationControls(results, controls, "similar", limit);
    }

    /**
     * Embed a query string with the local sidecar custom model (no paid API fallback).
     */
    private float[] embedQuery(String text) {
        if (!useCustomVectors) {
            throw new BadRequestException(
                    "OpenAI embedding fallback is disabled. Set RECOMMENDATIONS_USE_CUSTOM_VECTORS=true.");
        }
        if (!mlSidecarService.isEnabled()) {
            throw new BadRequestException("Custom semantic retrieval requires ML sidecar to be enabled");
        }
        float[] custom = mlSidecarService.embedText(text);
        if (custom != null) {
            return custom;
        }
        throw new BadRequestException("ML sidecar failed to embed query text");
    }

    /**
     * Normalize user query text and expand common anime shorthand into richer semantic terms.
     */
    private String preprocessSemanticQuery(String rawQuery) {
        if (rawQuery == null) {
            return "";
        }

        String normalized = rawQuery.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        if (normalized.isBlank()) {
            return "";
        }

        String[] tokens = normalized.split(" ");
        List<String> expanded = new ArrayList<>(tokens.length * 2);
        int negationScopeTokensRemaining = 0;
        for (String token : tokens) {
            expanded.add(token);
            if (QUERY_NEGATION_TOKENS.contains(token)) {
                negationScopeTokensRemaining = 4;
                continue;
            }
            if (QUERY_NEGATION_BREAK_TOKENS.contains(token) && negationScopeTokensRemaining > 0) {
                negationScopeTokensRemaining = 0;
            }

            String replacement = QUERY_SYNONYMS.get(token);
            if (replacement != null && negationScopeTokensRemaining == 0) {
                expanded.add(replacement);
            }

            if (negationScopeTokensRemaining > 0) {
                negationScopeTokensRemaining--;
            }
        }

        return String.join(" ", expanded)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static Map<String, String> buildQuerySynonyms() {
        Map<String, String> synonyms = new LinkedHashMap<>();
        synonyms.put("romcom", "romance comedy");
        synonyms.put("sol", "slice of life");
        synonyms.put("iyashikei", "healing slice of life");
        synonyms.put("cgdct", "cute girls slice of life comedy");
        synonyms.put("isekai", "another world fantasy adventure");
        synonyms.put("mecha", "robot sci fi");
        synonyms.put("shonen", "battle action coming of age");
        synonyms.put("shounen", "battle action coming of age");
        synonyms.put("shojo", "romance drama");
        synonyms.put("shoujo", "romance drama");
        synonyms.put("seinen", "mature psychological action");
        synonyms.put("josei", "adult romance slice of life");
        synonyms.put("tsundere", "romantic comedy tsundere");
        return Collections.unmodifiableMap(synonyms);
    }

    private SemanticRowSelection selectSemanticRows(
            String vectorStr,
            List<Integer> excludeIds,
            int vectorCandidateLimit,
            int mergedCandidateLimit,
            String normalizedQuery) {
        int effectiveVectorLimit = Math.max(1, vectorCandidateLimit);
        int effectiveMergedLimit = Math.max(1, mergedCandidateLimit);
        List<Object[]> vectorRows = findSimilarRows(vectorStr, excludeIds, effectiveVectorLimit);
        if (!semanticLexicalEnabled || normalizedQuery == null || normalizedQuery.isBlank()) {
            return new SemanticRowSelection(vectorRows, Set.of());
        }

        String lexicalQueryText = buildLexicalQueryText(normalizedQuery);
        if (lexicalQueryText.isBlank()) {
            return new SemanticRowSelection(vectorRows, Set.of());
        }

        List<Object[]> lexicalRows = embeddingRepository.findLexicalMatches(
                lexicalQueryText,
                excludeIds,
                Math.max(5, semanticLexicalCandidateLimit));
        if (shouldRunSecondPassLexical(normalizedQuery, vectorRows)) {
            String expandedLexical = buildSecondPassLexicalQueryText(
                    lexicalQueryText,
                    vectorRows,
                    lexicalRows == null ? List.of() : lexicalRows);
            if (!expandedLexical.isBlank() && !expandedLexical.equals(lexicalQueryText)) {
                List<Object[]> secondPassLexical = embeddingRepository.findLexicalMatches(
                        expandedLexical,
                        excludeIds,
                        Math.max(5, semanticLexicalCandidateLimit));
                if (secondPassLexical != null && !secondPassLexical.isEmpty()) {
                    List<Object[]> mergedLexical = new ArrayList<>();
                    if (lexicalRows != null) {
                        mergedLexical.addAll(lexicalRows);
                    }
                    mergedLexical.addAll(secondPassLexical);
                    lexicalRows = mergedLexical;
                }
            }
        }
        if (lexicalRows == null || lexicalRows.isEmpty()) {
            return new SemanticRowSelection(vectorRows, Set.of());
        }

        Map<Integer, Object[]> rowsById = new LinkedHashMap<>();
        Map<Integer, Integer> vectorRankById = new HashMap<>();
        Map<Integer, Integer> lexicalRankById = new HashMap<>();
        Set<Integer> lexicalBoostIds = new LinkedHashSet<>();
        registerSourceRows(vectorRows, rowsById, vectorRankById, null);
        registerSourceRows(lexicalRows, rowsById, lexicalRankById, lexicalBoostIds);

        List<Object[]> mergedRows = rankRowsByReciprocalRankFusion(
                rowsById,
                vectorRankById,
                lexicalRankById,
                effectiveMergedLimit,
                isLikelyTitleIntentQuery(normalizedQuery));

        Set<Integer> keptIds = new LinkedHashSet<>();
        for (Object[] row : mergedRows) {
            keptIds.add((Integer) row[1]);
        }
        lexicalBoostIds.retainAll(keptIds);

        return new SemanticRowSelection(mergedRows, Set.copyOf(lexicalBoostIds));
    }

    private List<FusionScoringService.ScoredCandidate> buildSemanticCandidatesFromRows(
            List<Object[]> rows,
            float[] searchVector,
            List<String> baseReasonCodes,
            Set<Integer> lexicalBoostIds,
            boolean rerankEnabled,
            int rerankTopK,
            boolean loggedIn) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        Set<Integer> safeLexicalBoostIds = lexicalBoostIds == null ? Set.of() : lexicalBoostIds;
        if (rerankEnabled && useCustomVectors && mlSidecarService.isEnabled()) {
            List<FusionScoringService.ScoredCandidate> reranked = tryBuildRerankedSemanticCandidates(
                    rows,
                    searchVector,
                    baseReasonCodes,
                    safeLexicalBoostIds,
                    rerankTopK,
                    loggedIn);
            if (reranked != null && !reranked.isEmpty()) {
                return reranked;
            }
        }

        List<FusionScoringService.ScoredCandidate> semanticCandidates = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            FusionScoringService.ScoredCandidate candidate = toSemanticCandidate(
                    row,
                    Double.NaN,
                    Double.NaN,
                    baseReasonCodes,
                    safeLexicalBoostIds,
                    loggedIn);
            if (candidate != null) {
                semanticCandidates.add(candidate);
            }
        }
        return semanticCandidates;
    }

    private List<FusionScoringService.ScoredCandidate> tryBuildRerankedSemanticCandidates(
            List<Object[]> rows,
            float[] searchVector,
            List<String> baseReasonCodes,
            Set<Integer> lexicalBoostIds,
            int rerankTopK,
            boolean loggedIn) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        List<Integer> candidateIds = new ArrayList<>(rows.size());
        List<Double> candidateDistances = new ArrayList<>(rows.size());
        Map<Integer, Object[]> rowById = new LinkedHashMap<>();
        for (Object[] row : rows) {
            if (row == null || row.length <= 1 || !(row[1] instanceof Integer anilistId)) {
                continue;
            }
            candidateIds.add(anilistId);
            candidateDistances.add(distanceFromRow(row));
            rowById.putIfAbsent(anilistId, row);
        }
        if (candidateIds.isEmpty()) {
            return List.of();
        }

        int topK = Math.max(1, Math.min(candidateIds.size(), rerankTopK <= 0 ? candidateIds.size() : rerankTopK));
        List<Map<String, Object>> reranked = mlSidecarService.rerank(
                searchVector,
                candidateIds,
                candidateDistances,
                topK);
        if (reranked == null || reranked.isEmpty()) {
            return List.of();
        }

        List<FusionScoringService.ScoredCandidate> semanticCandidates = new ArrayList<>(rowById.size());
        Set<Integer> appendedIds = new LinkedHashSet<>();
        for (Map<String, Object> item : reranked) {
            if (item == null) {
                continue;
            }
            Object idValue = item.get("anilist_id");
            if (!(idValue instanceof Number idNumber)) {
                continue;
            }
            int anilistId = idNumber.intValue();
            Object[] row = rowById.get(anilistId);
            if (row == null) {
                continue;
            }
            double rerankedScore = numberValue(item.get("score"), Double.NaN);
            double adherenceScore = numberValue(item.get("query_adherence_score"), Double.NaN);
            FusionScoringService.ScoredCandidate candidate = toSemanticCandidate(
                    row,
                    rerankedScore,
                    adherenceScore,
                    baseReasonCodes,
                    lexicalBoostIds,
                    loggedIn);
            if (candidate != null) {
                semanticCandidates.add(candidate);
                appendedIds.add(anilistId);
            }
        }

        // Keep unreturned rows so downstream fusion still has overfetch headroom.
        for (Object[] row : rows) {
            if (row == null || row.length <= 1 || !(row[1] instanceof Integer anilistId)) {
                continue;
            }
            if (appendedIds.contains(anilistId)) {
                continue;
            }
            FusionScoringService.ScoredCandidate candidate = toSemanticCandidate(
                    row,
                    Double.NaN,
                    Double.NaN,
                    baseReasonCodes,
                    lexicalBoostIds,
                    loggedIn);
            if (candidate != null) {
                semanticCandidates.add(candidate);
            }
        }

        return semanticCandidates;
    }

    private FusionScoringService.ScoredCandidate toSemanticCandidate(
            Object[] row,
            double rerankedScore,
            double queryAdherenceScore,
            List<String> baseReasonCodes,
            Set<Integer> lexicalBoostIds,
            boolean loggedIn) {
        if (row == null) {
            return null;
        }

        AniListResponse.AnimeInfo anime = mapRowToAnimeInfo(row);
        if (anime == null || anime.getId() == null) {
            return null;
        }

        double normalizedScore = Double.isNaN(rerankedScore)
                ? FusionScoringService.normalizeSemanticDistance(distanceFromRow(row))
                : FusionScoringService.normalizeRerankedScore(rerankedScore);
        if (lexicalBoostIds != null && lexicalBoostIds.contains(anime.getId())) {
            normalizedScore = FusionScoringService.clamp(
                    normalizedScore + semanticLexicalBoost,
                    0.0,
                    1.0);
        }
        double resolvedAdherenceScore = Double.isNaN(queryAdherenceScore)
                ? normalizedScore
                : FusionScoringService.clamp(queryAdherenceScore, 0.0d, 1.0d);
        double queryRelevanceScore = resolvedAdherenceScore;
        PopularityBlendResult popularityBlend = applySemanticPopularityPrior(queryRelevanceScore, anime);
        normalizedScore = queryRelevanceScore;
        anime.setQueryAdherenceScore(resolvedAdherenceScore);
        anime.setQueryRelevanceScore(queryRelevanceScore);
        anime.setPopularityPriorScore(popularityBlend.popularityPriorScore());
        anime.setGuardrailApplied(Boolean.FALSE);

        return new FusionScoringService.ScoredCandidate(
                anime.getId(),
                anime,
                normalizedScore,
                baseReasonCodes);
    }

    private PopularityBlendResult applySemanticPopularityPrior(
            double baseScore,
            AniListResponse.AnimeInfo anime) {
        if (!semanticPopularityPriorEnabled || anime == null) {
            return new PopularityBlendResult(baseScore, null, null);
        }

        if (anime.getAverageScore() == null && anime.getPopularity() == null) {
            return new PopularityBlendResult(baseScore, null, null);
        }
        double scoreNorm = anime.getAverageScore() == null
                ? 0.0d
                : FusionScoringService.clamp(anime.getAverageScore() / 100.0d, 0.0d, 1.0d);
        Double popularityCountNorm = normalizeAniListPopularity(anime.getPopularity());
        double popularityNorm;
        if (popularityCountNorm == null) {
            if (anime.getAverageScore() != null) {
                popularityNorm = scoreNorm;
                if (POPULARITY_COVERAGE_WARNING_LOGGED.compareAndSet(false, true)) {
                    log.warn("Semantic popularity prior fallback active: anilist_popularity missing, using average_score-only prior");
                }
            } else {
                return new PopularityBlendResult(baseScore, null, null);
            }
        } else {
            popularityNorm = (0.55d * scoreNorm) + (0.45d * popularityCountNorm);
            popularityNorm = FusionScoringService.clamp(popularityNorm, 0.0d, 1.0d);
        }
        return new PopularityBlendResult(baseScore, popularityNorm, null);
    }

    private Double normalizeAniListPopularity(Integer popularity) {
        if (popularity == null || popularity <= 0) {
            return null;
        }
        // Compress long-tail popularity counts so top catalog entries don't dominate.
        double norm = Math.log1p(popularity) / Math.log1p(1_000_000.0d);
        double power = Math.max(1.0d, semanticPopularityPriorNormalizationPower);
        norm = Math.pow(FusionScoringService.clamp(norm, 0.0d, 1.0d), power);
        return FusionScoringService.clamp(norm, 0.0d, 1.0d);
    }

    private String buildLexicalQueryText(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return "";
        }

        int tokenBudget = Math.max(1, semanticLexicalMaxPatterns);
        Set<String> seenTokens = new LinkedHashSet<>();
        for (String token : normalizedQuery.split(" ")) {
            if (token == null || token.isBlank()) {
                continue;
            }
            boolean isKeywordCandidate = token.length() >= 4 && !QUERY_STOP_WORDS.contains(token);
            if (!isKeywordCandidate) {
                continue;
            }
            if (!seenTokens.add(token)) {
                continue;
            }
            if (seenTokens.size() >= tokenBudget) {
                break;
            }
        }

        if (seenTokens.isEmpty()) {
            return normalizedQuery;
        }
        return String.join(" ", seenTokens);
    }

    private boolean shouldRunSecondPassLexical(String normalizedQuery, List<Object[]> vectorRows) {
        if (!semanticSecondPassEnabled || normalizedQuery == null || normalizedQuery.isBlank()) {
            return false;
        }

        int tokenLimit = Math.max(1, semanticSecondPassTriggerMaxQueryTokens);
        int tokenCount = 0;
        for (String token : normalizedQuery.split(" ")) {
            if (token != null && !token.isBlank()) {
                tokenCount++;
            }
        }
        if (tokenCount > tokenLimit) {
            return false;
        }

        if (vectorRows == null || vectorRows.isEmpty()) {
            return true;
        }

        double topRelevance = FusionScoringService.normalizeSemanticDistance(distanceFromRow(vectorRows.get(0)));
        return topRelevance < FusionScoringService.clamp(semanticSecondPassSkipTopRelevanceThreshold, 0.0d, 1.0d);
    }

    private String buildSecondPassLexicalQueryText(
            String lexicalQueryText,
            List<Object[]> vectorRows,
            List<Object[]> lexicalRows) {
        if (lexicalQueryText == null || lexicalQueryText.isBlank()) {
            return "";
        }

        Set<String> baseTokens = new LinkedHashSet<>();
        for (String token : lexicalQueryText.split(" ")) {
            String norm = normalizeTokenForQuery(token);
            if (!norm.isBlank()) {
                baseTokens.add(norm);
            }
        }

        Set<String> expansionTokens = new LinkedHashSet<>();
        int contextSize = Math.max(1, semanticSecondPassContextSize);
        collectExpansionTokensFromRows(vectorRows, contextSize, baseTokens, expansionTokens);
        collectExpansionTokensFromRows(lexicalRows, contextSize, baseTokens, expansionTokens);

        int maxAdded = Math.max(0, semanticSecondPassMaxAddedTokens);
        if (expansionTokens.isEmpty() || maxAdded == 0) {
            return lexicalQueryText;
        }

        List<String> picked = new ArrayList<>(maxAdded);
        for (String token : expansionTokens) {
            picked.add(token);
            if (picked.size() >= maxAdded) {
                break;
            }
        }
        if (picked.isEmpty()) {
            return lexicalQueryText;
        }
        return (lexicalQueryText + " " + String.join(" ", picked)).trim();
    }

    private void collectExpansionTokensFromRows(
            List<Object[]> rows,
            int limit,
            Set<String> baseTokens,
            Set<String> expansionTokens) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        int maxRows = Math.min(Math.max(0, limit), rows.size());
        for (int i = 0; i < maxRows; i++) {
            Object[] row = rows.get(i);
            if (row == null || row.length <= 6) {
                continue;
            }
            addExpansionTokensFromText((String) row[2], baseTokens, expansionTokens);
            addExpansionTokensFromText((String) row[3], baseTokens, expansionTokens);
            addExpansionTokensFromText((String) row[5], baseTokens, expansionTokens);
        }
    }

    private void addExpansionTokensFromText(
            String text,
            Set<String> baseTokens,
            Set<String> expansionTokens) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (String rawToken : text.split("[^A-Za-z0-9]+")) {
            String token = normalizeTokenForQuery(rawToken);
            if (token.isBlank() || baseTokens.contains(token)) {
                continue;
            }
            expansionTokens.add(token);
        }
    }

    private String normalizeTokenForQuery(String rawToken) {
        if (rawToken == null) {
            return "";
        }
        String token = rawToken.trim().toLowerCase();
        if (token.length() < 4 || QUERY_STOP_WORDS.contains(token)) {
            return "";
        }
        return token;
    }

    private void registerSourceRows(
            List<Object[]> sourceRows,
            Map<Integer, Object[]> rowsById,
            Map<Integer, Integer> rankById,
            Set<Integer> optionalSeenIds) {
        if (sourceRows == null || sourceRows.isEmpty()) {
            return;
        }

        int rank = 1;
        for (Object[] row : sourceRows) {
            if (row == null || row.length <= 1 || !(row[1] instanceof Integer id)) {
                continue;
            }
            rankById.putIfAbsent(id, rank);
            if (optionalSeenIds != null) {
                optionalSeenIds.add(id);
            }

            Object[] existing = rowsById.get(id);
            if (existing == null || distanceFromRow(row) < distanceFromRow(existing)) {
                rowsById.put(id, row);
            }
            rank++;
        }
    }

    private List<Object[]> rankRowsByReciprocalRankFusion(
            Map<Integer, Object[]> rowsById,
            Map<Integer, Integer> vectorRankById,
            Map<Integer, Integer> lexicalRankById,
            int candidateLimit,
            boolean titleIntentQuery) {
        if (rowsById == null || rowsById.isEmpty()) {
            return List.of();
        }

        double vectorWeight = Math.max(0.0d, semanticLexicalVectorWeight);
        double lexicalWeight = Math.max(0.0d, semanticLexicalWeight);
        if (titleIntentQuery) {
            lexicalWeight *= Math.max(1.0d, semanticTitleIntentLexicalBoost);
        }
        if (vectorWeight <= 0.0d && lexicalWeight <= 0.0d) {
            vectorWeight = 1.0d;
            lexicalWeight = 1.0d;
        }
        final int rrfK = Math.max(1, semanticLexicalRrfK);
        final double effectiveVectorWeight = vectorWeight;
        final double effectiveLexicalWeight = lexicalWeight;

        List<Map.Entry<Integer, Object[]>> rankedEntries = new ArrayList<>(rowsById.entrySet());
        rankedEntries.sort((left, right) -> {
            Integer leftId = left.getKey();
            Integer rightId = right.getKey();
            double leftScore = reciprocalRankFusionScore(
                    leftId,
                    vectorRankById,
                    lexicalRankById,
                    rrfK,
                    effectiveVectorWeight,
                    effectiveLexicalWeight);
            double rightScore = reciprocalRankFusionScore(
                    rightId,
                    vectorRankById,
                    lexicalRankById,
                    rrfK,
                    effectiveVectorWeight,
                    effectiveLexicalWeight);
            int byScore = Double.compare(rightScore, leftScore);
            if (byScore != 0) {
                return byScore;
            }
            return Integer.compare(leftId, rightId);
        });

        List<Object[]> rows = new ArrayList<>(Math.min(candidateLimit, rankedEntries.size()));
        int limit = Math.min(Math.max(1, candidateLimit), rankedEntries.size());
        for (int i = 0; i < limit; i++) {
            rows.add(rankedEntries.get(i).getValue());
        }
        return rows;
    }

    private boolean isLikelyTitleIntentQuery(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return false;
        }

        List<String> tokens = new ArrayList<>();
        for (String token : normalizedQuery.split(" ")) {
            if (token != null && !token.isBlank()) {
                tokens.add(token);
            }
        }
        if (tokens.isEmpty()) {
            return false;
        }

        int contentTokenCount = 0;
        for (String token : tokens) {
            if (!QUERY_STOP_WORDS.contains(token)) {
                contentTokenCount++;
            }
        }

        boolean hasSeasonMarker = normalizedQuery.contains("season ")
                || normalizedQuery.matches(".*\\bs\\d+\\b.*");
        boolean hasStructuredConstraintLanguage = normalizedQuery.contains(" with ")
                || normalizedQuery.contains(" about ")
                || normalizedQuery.contains(" similar to ")
                || normalizedQuery.contains(",")
                || normalizedQuery.contains(" and ");
        if (hasSeasonMarker) {
            return true;
        }
        return contentTokenCount <= 3 && !hasStructuredConstraintLanguage;
    }

    private boolean isBroadDiscoveryQuery(String normalizedQuery, boolean hasQuery) {
        if (!hasQuery || normalizedQuery == null || normalizedQuery.isBlank()) {
            return false;
        }

        List<String> tokens = new ArrayList<>();
        for (String token : normalizedQuery.split(" ")) {
            if (token == null || token.isBlank() || QUERY_STOP_WORDS.contains(token)) {
                continue;
            }
            tokens.add(token);
        }
        if (tokens.isEmpty()) {
            return false;
        }

        boolean titleIntent = isLikelyTitleIntentQuery(normalizedQuery);
        if (titleIntent) {
            return false;
        }

        boolean hasStructuredConstraints = normalizedQuery.contains(",")
                || normalizedQuery.contains(" and ")
                || normalizedQuery.contains(" with ")
                || normalizedQuery.contains(" but ")
                || normalizedQuery.contains(" not ");
        return tokens.size() >= 4 || hasStructuredConstraints;
    }

    private double reciprocalRankFusionScore(
            Integer anilistId,
            Map<Integer, Integer> vectorRankById,
            Map<Integer, Integer> lexicalRankById,
            int rrfK,
            double vectorWeight,
            double lexicalWeight) {
        if (anilistId == null) {
            return 0.0d;
        }

        double score = 0.0d;
        Integer vectorRank = vectorRankById.get(anilistId);
        if (vectorRank != null) {
            score += vectorWeight * (1.0d / (rrfK + vectorRank));
        }

        Integer lexicalRank = lexicalRankById.get(anilistId);
        if (lexicalRank != null) {
            score += lexicalWeight * (1.0d / (rrfK + lexicalRank));
        }

        return score;
    }

    private List<FusionScoringService.ScoredCandidate> applyQueryScoreCalibration(
            List<FusionScoringService.ScoredCandidate> candidates,
            boolean queryDriven) {
        if (!queryDriven
                || !semanticScoreCalibrationEnabled
                || candidates == null
                || candidates.size() < 3) {
            return candidates;
        }

        double mean = 0.0d;
        for (FusionScoringService.ScoredCandidate candidate : candidates) {
            mean += candidate.score();
        }
        mean /= candidates.size();

        double variance = 0.0d;
        for (FusionScoringService.ScoredCandidate candidate : candidates) {
            double delta = candidate.score() - mean;
            variance += delta * delta;
        }
        variance /= candidates.size();
        double stdDev = Math.sqrt(variance);
        if (stdDev < 1e-6d) {
            return candidates;
        }

        double temperature = Math.max(0.1d, semanticScoreCalibrationTemperature);
        List<FusionScoringService.ScoredCandidate> calibrated = new ArrayList<>(candidates.size());
        for (FusionScoringService.ScoredCandidate candidate : candidates) {
            double zScore = (candidate.score() - mean) / (stdDev * temperature);
            double score = 1.0d / (1.0d + Math.exp(-zScore));
            calibrated.add(new FusionScoringService.ScoredCandidate(
                    candidate.anilistId(),
                    candidate.animeInfo(),
                    FusionScoringService.clamp(score, 0.0d, 1.0d),
                    candidate.reasonCodes()));
        }
        return calibrated;
    }

    private List<FusionScoringService.ScoredCandidate> applyModeBlendedScoring(
            List<FusionScoringService.ScoredCandidate> candidates,
            String mode,
            boolean loggedIn,
            List<String> topTasteGenres,
            boolean broadDiscoveryQuery) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        boolean similarMode = "similar".equals(mode);
        double guardrailThreshold = FusionScoringService.clamp(semanticPopularityGuardrailThreshold, 0.0d, 1.0d);
        double guardrailMaxPopularityWeight = FusionScoringService.clamp(
                semanticPopularityGuardrailMaxWeight,
                0.0d,
                0.35d);
        List<FusionScoringService.ScoredCandidate> rescored = new ArrayList<>(candidates.size());

        for (FusionScoringService.ScoredCandidate candidate : candidates) {
            if (candidate == null) {
                continue;
            }

            AniListResponse.AnimeInfo anime = candidate.animeInfo();
            double queryRelevance = anime != null && anime.getQueryAdherenceScore() != null
                    ? FusionScoringService.clamp(anime.getQueryAdherenceScore(), 0.0d, 1.0d)
                    : anime != null && anime.getQueryRelevanceScore() != null
                    ? FusionScoringService.clamp(anime.getQueryRelevanceScore(), 0.0d, 1.0d)
                    : candidate.score();
            Double popularityPrior = anime == null
                    ? null
                    : anime.getPopularityPriorScore();
            Double tasteScore = (loggedIn && anime != null)
                    ? computeUserTasteScore(anime, topTasteGenres)
                    : null;

            double tasteWeight;
            double popularityWeight;
            if (similarMode) {
                tasteWeight = loggedIn ? 0.10d : 0.0d;
                popularityWeight = 0.10d;
            } else if (loggedIn) {
                tasteWeight = semanticTasteWeightLoggedIn;
                popularityWeight = semanticPopularityPriorWeightLoggedIn;
                if (broadDiscoveryQuery) {
                    tasteWeight = Math.max(tasteWeight, semanticTasteWeightLoggedInBroadQuery);
                    popularityWeight = Math.max(popularityWeight, semanticPopularityPriorWeightLoggedInBroadQuery);
                }
            } else {
                tasteWeight = 0.0d;
                popularityWeight = semanticPopularityPriorWeightLoggedOut;
                if (broadDiscoveryQuery) {
                    popularityWeight = Math.max(popularityWeight, semanticPopularityPriorWeightLoggedOutBroadQuery);
                }
            }
            tasteWeight = FusionScoringService.clamp(tasteWeight, 0.0d, 0.35d);
            popularityWeight = FusionScoringService.clamp(popularityWeight, 0.0d, 0.35d);
            if (tasteScore == null) {
                tasteWeight = 0.0d;
            }
            if (popularityPrior == null) {
                popularityWeight = 0.0d;
            }

            boolean guardrailApplied = false;
            if (queryRelevance < guardrailThreshold) {
                guardrailApplied = tasteWeight > 0.0d || popularityWeight > guardrailMaxPopularityWeight;
                tasteWeight = 0.0d;
                popularityWeight = Math.min(popularityWeight, guardrailMaxPopularityWeight);
            }

            double queryWeight = Math.max(0.0d, 1.0d - tasteWeight - popularityWeight);
            double finalScore = (queryWeight * queryRelevance)
                    + (tasteWeight * (tasteScore == null ? 0.0d : FusionScoringService.clamp(tasteScore, 0.0d, 1.0d)))
                    + (popularityWeight * (popularityPrior == null
                            ? 0.0d
                            : FusionScoringService.clamp(popularityPrior, 0.0d, 1.0d)));
            if (!similarMode
                    && broadDiscoveryQuery
                    && anime != null
                    && anime.getAverageScore() != null
                    && anime.getPopularity() != null
                    && anime.getAverageScore() < semanticBroadQueryLowQualityScoreThreshold
                    && anime.getPopularity() < semanticBroadQueryLowQualityPopularityThreshold) {
                double penalty = FusionScoringService.clamp(semanticBroadQueryLowQualityPenalty, 0.50d, 1.0d);
                finalScore *= penalty;
                guardrailApplied = true;
            }
            finalScore = FusionScoringService.clamp(finalScore, 0.0d, 1.0d);

            if (anime != null) {
                anime.setUserTasteScore(tasteScore);
                anime.setGuardrailApplied(guardrailApplied);
            }
            rescored.add(new FusionScoringService.ScoredCandidate(
                    candidate.anilistId(),
                    anime,
                    finalScore,
                    candidate.reasonCodes()));
        }

        rescored.sort((left, right) -> {
            int byScore = Double.compare(right.score(), left.score());
            if (byScore != 0) {
                return byScore;
            }
            return Integer.compare(left.anilistId(), right.anilistId());
        });
        return rescored;
    }

    private List<FusionScoringService.FusedCandidate> toFusedCandidates(
            List<FusionScoringService.ScoredCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<FusionScoringService.ScoredCandidate> ordered = new ArrayList<>(candidates);
        ordered.sort((left, right) -> {
            int byScore = Double.compare(right.score(), left.score());
            if (byScore != 0) {
                return byScore;
            }
            return Integer.compare(left.anilistId(), right.anilistId());
        });

        List<FusionScoringService.FusedCandidate> fused = new ArrayList<>(ordered.size());
        for (FusionScoringService.ScoredCandidate candidate : ordered) {
            fused.add(new FusionScoringService.FusedCandidate(
                    candidate.anilistId(),
                    candidate.animeInfo(),
                    candidate.score(),
                    candidate.reasonCodes()));
        }
        return fused;
    }

    private RecommendationControls resolveRecommendationControls(SemanticRequest.Filters filters) {
        if (filters == null) {
            return RecommendationControls.defaults();
        }
        return new RecommendationControls(
                Boolean.TRUE.equals(filters.getIncludeExtraSeasons()),
                Boolean.TRUE.equals(filters.getIncludeMovies()),
                Boolean.TRUE.equals(filters.getIncludeOnasOvasSpecials()),
                Boolean.TRUE.equals(filters.getIncludeMusic()),
                Boolean.TRUE.equals(filters.getIncludeAdult()),
                parsePopularityAttenuation(filters.getPopularityAttenuation()),
                true);
    }

    private PopularityAttenuation parsePopularityAttenuation(String raw) {
        if (raw == null || raw.isBlank()) {
            return PopularityAttenuation.MEDIUM;
        }
        return switch (raw.trim().toLowerCase()) {
            case "low" -> PopularityAttenuation.LOW;
            case "high" -> PopularityAttenuation.HIGH;
            default -> PopularityAttenuation.MEDIUM;
        };
    }

    private List<RecommendationResponse> applyRecommendationControls(
            List<RecommendationResponse> input,
            RecommendationControls controls,
            String mode,
            int limit) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        RecommendationControls effectiveControls = controls == null
                ? RecommendationControls.defaults()
                : controls;
        List<RecommendationResponse> filtered = filterAndScoreRecommendations(input, effectiveControls, mode);
        int underfillTarget = resolveUnderfillTarget(limit);
        if (filtered.size() < underfillTarget && !effectiveControls.explicitUserFilters()) {
            RecommendationControls relaxedControls = effectiveControls.relaxedForUnderfill();
            List<RecommendationResponse> relaxed = filterAndScoreRecommendations(input, relaxedControls, mode);
            if (relaxed.size() > filtered.size()) {
                log.debug(
                        "Recommendation controls underfill fallback applied: mode={}, strict_size={}, relaxed_size={}, target={}",
                        mode,
                        filtered.size(),
                        relaxed.size(),
                        underfillTarget);
                filtered = relaxed;
            }
        }

        filtered.sort((left, right) -> {
            int byScore = Double.compare(
                    numberValue(right.getFusionScore(), 0.0d),
                    numberValue(left.getFusionScore(), 0.0d));
            if (byScore != 0) {
                return byScore;
            }
            Integer leftId = left.getAnime() == null ? null : left.getAnime().getId();
            Integer rightId = right.getAnime() == null ? null : right.getAnime().getId();
            if (leftId == null && rightId == null) {
                return 0;
            }
            if (leftId == null) {
                return 1;
            }
            if (rightId == null) {
                return -1;
            }
            return Integer.compare(leftId, rightId);
        });

        int safeLimit = Math.max(1, limit);
        if (filtered.size() <= safeLimit) {
            return filtered;
        }
        return List.copyOf(filtered.subList(0, safeLimit));
    }

    private List<RecommendationResponse> filterAndScoreRecommendations(
            List<RecommendationResponse> input,
            RecommendationControls controls,
            String mode) {
        List<RecommendationResponse> filtered = new ArrayList<>(input.size());
        for (RecommendationResponse row : input) {
            if (row == null || row.getAnime() == null) {
                continue;
            }
            AniListResponse.AnimeInfo anime = row.getAnime();
            if (!controls.includeAdult() && isAdultCandidate(anime)) {
                continue;
            }
            if (!controls.includeMusic() && isMusicCandidate(anime)) {
                continue;
            }
            if (!controls.includeMovies() && isMovieCandidate(anime)) {
                continue;
            }
            if (!controls.includeOnasOvasSpecials() && isOnaOvaSpecialCandidate(anime)) {
                continue;
            }
            if (!controls.includeExtraSeasons() && isExtraSeasonCandidate(anime)) {
                continue;
            }

            double baseScore = row.getFusionScore() == null
                    ? numberValue(anime.getQueryRelevanceScore(), 0.0d)
                    : row.getFusionScore();
            double adjustedScore = applyPopularityAttenuation(
                    baseScore,
                    anime.getPopularity(),
                    controls.popularityAttenuation(),
                    mode);
            filtered.add(new RecommendationResponse(anime, adjustedScore, row.getReasonCodes()));
        }
        return filtered;
    }

    private int resolveUnderfillTarget(int limit) {
        int safeLimit = Math.max(1, limit);
        int ratioTarget = (int) Math.ceil(safeLimit * FusionScoringService.clamp(controlsUnderfillMinRatio, 0.25d, 1.0d));
        int floorTarget = Math.max(1, controlsUnderfillMinFloor);
        return Math.min(safeLimit, Math.max(ratioTarget, floorTarget));
    }

    private double applyPopularityAttenuation(
            double baseScore,
            Integer popularity,
            PopularityAttenuation attenuation,
            String mode) {
        boolean cfMode = "cf".equalsIgnoreCase(mode);
        double alpha = switch (attenuation == null ? PopularityAttenuation.MEDIUM : attenuation) {
            case LOW -> FusionScoringService.clamp(
                    cfMode ? cfPopularityAttenuationLow : popularityAttenuationLow,
                    0.0d,
                    0.35d);
            case HIGH -> FusionScoringService.clamp(
                    cfMode ? cfPopularityAttenuationHigh : popularityAttenuationHigh,
                    0.0d,
                    0.35d);
            case MEDIUM -> FusionScoringService.clamp(
                    cfMode ? cfPopularityAttenuationMedium : popularityAttenuationMedium,
                    0.0d,
                    0.35d);
        };
        if (alpha <= 0.0d) {
            return FusionScoringService.clamp(baseScore, 0.0d, 1.0d);
        }
        double popularityNorm = normalizePopularityForAttenuation(popularity);
        double nicheBoost = 1.0d - popularityNorm;
        return FusionScoringService.clamp(baseScore * (1.0d + (alpha * nicheBoost)), 0.0d, 1.0d);
    }

    private double normalizePopularityForAttenuation(Integer popularity) {
        if (popularity == null || popularity <= 0) {
            return 0.50d;
        }
        double capped = Math.min(2_000_000.0d, popularity.doubleValue());
        return FusionScoringService.clamp(Math.log1p(capped) / Math.log1p(2_000_000.0d), 0.0d, 1.0d);
    }

    private boolean isAdultCandidate(AniListResponse.AnimeInfo anime) {
        if (anime == null) {
            return false;
        }
        if (Boolean.TRUE.equals(anime.getIsAdult())) {
            return true;
        }
        Set<String> genres = parseGenreList(anime.getGenres());
        if (genres.contains("hentai")) {
            return true;
        }
        boolean ecchiGenre = genres.contains("ecchi");
        if (anime.getTags() != null) {
            for (AniListResponse.AnimeTag tag : anime.getTags()) {
                if (tag == null || tag.getName() == null || tag.getName().isBlank()) {
                    continue;
                }
                String lowered = tag.getName().toLowerCase();
                boolean blocklisted = ADULT_BLOCKLIST_TAG_KEYWORDS.stream().anyMatch(lowered::contains);
                if (blocklisted) {
                    return true;
                }
                if (ecchiGenre && lowered.contains("ecchi") && tag.getRank() != null && tag.getRank() >= 80) {
                    return true;
                }
            }
        }
        if (ecchiGenre) {
            String text = animeTextBlob(anime);
            return text.contains("explicit") || text.contains("erotic") || text.contains("sexual");
        }
        return false;
    }

    private boolean isMovieCandidate(AniListResponse.AnimeInfo anime) {
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

    private boolean isOnaOvaSpecialCandidate(AniListResponse.AnimeInfo anime) {
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
        return text.contains(" ova ") || text.contains(" ona ") || text.contains(" special ");
    }

    private boolean isMusicCandidate(AniListResponse.AnimeInfo anime) {
        if (anime == null) {
            return false;
        }
        String format = anime.getFormat();
        if (format != null && "MUSIC".equalsIgnoreCase(format.trim())) {
            return true;
        }
        Set<String> genres = parseGenreList(anime.getGenres());
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

    private boolean isExtraSeasonCandidate(AniListResponse.AnimeInfo anime) {
        if (anime == null) {
            return false;
        }
        String title = animeTitleBlob(anime).trim();
        if (title.isBlank()) {
            return false;
        }
        if (title.matches(".*\\bseason\\s+([2-9]\\d*|ii|iii|iv|v|vi|vii|viii|ix|x)\\b.*")) {
            return true;
        }
        if (title.matches(".*\\b([2-9]\\d*)(st|nd|rd|th)\\s+season\\b.*")) {
            return true;
        }
        for (String ordinalWord : SEASON_ORDINAL_WORDS) {
            if (title.contains(" " + ordinalWord + " season ")) {
                return true;
            }
        }
        if (title.matches(".*\\b(part|cour)\\s+([2-9]\\d*|ii|iii|iv|v|vi|vii|viii|ix|x)\\b.*")) {
            return true;
        }
        return title.matches(".*\\b(ii|iii|iv|v|vi)\\b.*");
    }

    private String animeTitleBlob(AniListResponse.AnimeInfo anime) {
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
        return text.toString()
                .toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ");
    }

    private String animeTextBlob(AniListResponse.AnimeInfo anime) {
        StringBuilder text = new StringBuilder(animeTitleBlob(anime));
        if (anime != null && anime.getDescription() != null) {
            text.append(anime.getDescription()).append(' ');
        }
        return text.toString().toLowerCase().replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ");
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

    public Map<String, Object> populateActiveCatalogEmbeddings(int maxPages, int perPage) {
        int safePages = Math.max(1, maxPages);
        int safePerPage = Math.max(1, Math.min(50, perPage));
        AnimeEmbeddingPopulatorService.PopulationStats stats = populatorService.populateActiveCatalog(
                safePages,
                safePerPage);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", stats.source());
        out.put("startPage", stats.startPage());
        out.put("nextPageHint", stats.nextPageHint());
        out.put("exhausted", stats.exhausted());
        out.put("pagesVisited", stats.pagesVisited());
        out.put("discovered", stats.discovered());
        out.put("embedded", stats.embedded());
        out.put("skipped", stats.skipped());
        out.put("metadataRefreshed", stats.metadataRefreshed());
        out.put("failed", stats.failed());
        out.put("totalCustomEmbeddings", stats.totalCustomEmbeddings());
        out.put("scoreCoverage", stats.scoreCoverage());
        out.put("popularityCoverage", stats.popularityCoverage());
        out.put("tagCoverage", stats.tagCoverage());
        out.put("aliasCoverage", stats.aliasCoverage());
        double coverage = stats.discovered() <= 0
                ? 0.0d
                : (double) stats.embedded() / (double) stats.discovered();
        out.put("activeCatalogCoverage", coverage);
        return out;
    }

    public Map<String, Object> getPopulationFailureReport(String source, String status, int limit) {
        int safeLimit = Math.max(1, Math.min(500, limit));
        return populatorService.getFailureReport(source, status, safeLimit);
    }

    public Map<String, Object> retryPopulationFailures(String source, int limit) {
        int safeLimit = Math.max(1, Math.min(200, limit));
        return populatorService.retryFailures(source, safeLimit);
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
        if (!embedMissing || rows.size() >= normalizedIds.size()) {
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
        if (!useCustomVectors) {
            log.debug("Skipping on-the-fly embedding for {} because custom vectors are disabled", anilistId);
            return;
        }
        if (!mlSidecarService.isEnabled()) {
            log.debug("Skipping on-the-fly embedding for {} because ML sidecar is disabled", anilistId);
            return;
        }

        try {
            AniListResponse.AnimeInfo anime = aniListService.getAnimeById(anilistId);
            if (anime == null) {
                log.warn("Could not fetch anime {} from AniList", anilistId);
                return;
            }

            String embeddingText = populatorService.buildEmbeddingText(anime);
            String metadataFingerprint = computeMetadataFingerprint(embeddingText);
            float[] vector = mlSidecarService.embedText(embeddingText);
            if (vector == null || vector.length == 0) {
                log.warn("Could not generate custom embedding on-the-fly for anime {}", anilistId);
                return;
            }
            String vectorStr = EmbeddingService.toVectorString(vector);

            String titleRomaji = anime.getTitle() != null ? anime.getTitle().getRomaji() : null;
            String titleEnglish = anime.getTitle() != null ? anime.getTitle().getEnglish() : null;
            String coverImage = anime.getCoverImage() != null ? anime.getCoverImage().getLarge() : null;
            String genres = anime.getGenres() != null ? String.join(", ", anime.getGenres()) : null;
            String description = anime.getDescription() != null
                    ? anime.getDescription().replaceAll("<[^>]*>", "").trim()
                    : null;

            embeddingRepository.upsertCustomEmbedding(
                    anime.getId(), titleRomaji, titleEnglish, coverImage,
                    genres, description, anime.getAverageScore(),
                    anime.getStatus(), anime.getEpisodes(),
                    anime.getPopularity(),
                    embeddingText,
                    metadataFingerprint,
                    vectorStr);
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
        anime.setPopularity((Integer) row[10]);

        return anime;
    }

    private AniListResponse.AnimeInfo mapMetadataRowToAnimeInfo(Object[] row) {
        if (row == null || row.length < 10 || !(row[0] instanceof Number anilistIdValue)) {
            return null;
        }

        AniListResponse.AnimeInfo anime = new AniListResponse.AnimeInfo();
        anime.setId(anilistIdValue.intValue());

        AniListResponse.AnimeTitle title = new AniListResponse.AnimeTitle();
        title.setRomaji((String) row[1]);
        title.setEnglish((String) row[2]);
        anime.setTitle(title);

        AniListResponse.AnimeCoverImage cover = new AniListResponse.AnimeCoverImage();
        cover.setLarge((String) row[3]);
        anime.setCoverImage(cover);

        anime.setGenres(row[4] != null ? List.of(((String) row[4]).split(", ")) : null);
        anime.setDescription((String) row[5]);
        anime.setAverageScore((Integer) row[6]);
        anime.setStatus((String) row[7]);
        anime.setEpisodes((Integer) row[8]);
        anime.setPopularity((Integer) row[9]);
        return anime;
    }

    private AniListResponse.AnimeInfo hydrateMetadataIfMissing(AniListResponse.AnimeInfo anime, boolean allowAniListHydration) {
        if (anime == null || anime.getId() == null || !isMetadataIncomplete(anime)) {
            return anime;
        }

        try {
            AniListResponse.AnimeInfo localMetadata = loadMetadataFromStore(anime.getId());
            if (localMetadata != null) {
                anime = mergeAnimeInfo(anime, localMetadata);
                if (!isMetadataIncomplete(anime)) {
                    return anime;
                }
            }
        } catch (Exception e) {
            log.debug("Failed local metadata hydration for anime {}: {}", anime.getId(), e.getMessage());
        }

        if (!allowAniListHydration) {
            return anime;
        }

        AniListResponse.AnimeInfo cached = metadataHydrationCache.get(anime.getId());
        if (cached != null) {
            return mergeAnimeInfo(anime, cached);
        }

        try {
            AniListResponse.AnimeInfo fetched = aniListService.getAnimeById(anime.getId());
            if (fetched == null) {
                return anime;
            }

            AniListResponse.AnimeInfo merged = mergeAnimeInfo(anime, fetched);
            persistMetadata(merged);
            metadataHydrationCache.put(anime.getId(), copyAnimeInfo(fetched));
            return merged;
        } catch (Exception e) {
            log.warn("Failed to hydrate metadata for anime {}: {}", anime.getId(), e.getMessage());
            return anime;
        }
    }

    private AniListResponse.AnimeInfo loadMetadataFromStore(Integer anilistId) {
        if (anilistId == null) {
            return null;
        }
        List<Object[]> rows = embeddingRepository.findMetadataByAnilistIds(List.of(anilistId));
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return mapMetadataRowToAnimeInfo(rows.get(0));
    }

    private boolean isMetadataIncomplete(AniListResponse.AnimeInfo anime) {
        boolean missingCover = anime.getCoverImage() == null
                || anime.getCoverImage().getLarge() == null
                || anime.getCoverImage().getLarge().isBlank();
        boolean missingGenres = anime.getGenres() == null || anime.getGenres().isEmpty();
        boolean missingScore = anime.getAverageScore() == null;
        boolean missingPopularity = anime.getPopularity() == null;
        boolean missingDescription = anime.getDescription() == null || anime.getDescription().isBlank();
        boolean missingEpisodes = anime.getEpisodes() == null;
        return missingCover || missingGenres || missingScore || missingPopularity || missingDescription || missingEpisodes;
    }

    private AniListResponse.AnimeInfo mergeAnimeInfo(AniListResponse.AnimeInfo current, AniListResponse.AnimeInfo fetched) {
        if (current == null) {
            return fetched;
        }
        if (fetched == null) {
            return current;
        }
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
        if (current.getPopularity() == null) {
            current.setPopularity(fetched.getPopularity());
        }
        if (current.getStatus() == null || current.getStatus().isBlank()) {
            current.setStatus(fetched.getStatus());
        }
        if (current.getEpisodes() == null) {
            current.setEpisodes(fetched.getEpisodes());
        }
        if (current.getIsAdult() == null) {
            current.setIsAdult(fetched.getIsAdult());
        }
        if (current.getFormat() == null || current.getFormat().isBlank()) {
            current.setFormat(fetched.getFormat());
        }
        if (current.getSeason() == null || current.getSeason().isBlank()) {
            current.setSeason(fetched.getSeason());
        }
        if (current.getSeasonYear() == null) {
            current.setSeasonYear(fetched.getSeasonYear());
        }
        if (current.getTags() == null || current.getTags().isEmpty()) {
            current.setTags(fetched.getTags());
        }
        return current;
    }

    private AniListResponse.AnimeInfo copyAnimeInfo(AniListResponse.AnimeInfo source) {
        if (source == null) {
            return null;
        }
        AniListResponse.AnimeInfo copy = new AniListResponse.AnimeInfo();
        copy.setId(source.getId());

        if (source.getTitle() != null) {
            AniListResponse.AnimeTitle title = new AniListResponse.AnimeTitle();
            title.setRomaji(source.getTitle().getRomaji());
            title.setEnglish(source.getTitle().getEnglish());
            copy.setTitle(title);
        }
        if (source.getCoverImage() != null) {
            AniListResponse.AnimeCoverImage cover = new AniListResponse.AnimeCoverImage();
            cover.setLarge(source.getCoverImage().getLarge());
            copy.setCoverImage(cover);
        }

        copy.setGenres(source.getGenres() == null ? null : List.copyOf(source.getGenres()));
        copy.setDescription(source.getDescription());
        copy.setAverageScore(source.getAverageScore());
        copy.setPopularity(source.getPopularity());
        copy.setStatus(source.getStatus());
        copy.setEpisodes(source.getEpisodes());
        copy.setIsAdult(source.getIsAdult());
        copy.setFormat(source.getFormat());
        copy.setSeason(source.getSeason());
        copy.setSeasonYear(source.getSeasonYear());
        copy.setTags(source.getTags() == null ? null : List.copyOf(source.getTags()));
        return copy;
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
        String metadataFingerprint = computeMetadataFingerprint(populatorService.buildEmbeddingText(anime));

        embeddingRepository.updateMetadataByAnilistId(
                anime.getId(),
                titleRomaji,
                titleEnglish,
                coverImage,
                genres,
                description,
                anime.getAverageScore(),
                anime.getPopularity(),
                anime.getStatus(),
                anime.getEpisodes(),
                metadataFingerprint);
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

    private String computeMetadataFingerprint(String embeddingText) {
        if (embeddingText == null || embeddingText.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(embeddingText.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            log.warn("Unable to compute metadata fingerprint: {}", e.getMessage());
            return null;
        }
    }

    private List<RecommendationResponse> finalizeCandidatesWithReasons(
            List<FusionScoringService.FusedCandidate> fusedCandidates,
            String mode,
            int limit,
            String username,
            ReasoningContext reasoningContext,
            boolean allowAniListHydration) {
        if (fusedCandidates == null || fusedCandidates.isEmpty()) {
            return List.of();
        }

        List<FusionScoringService.FusedCandidate> processedCandidates = applySemanticDedupe(
                fusedCandidates,
                mode);
        List<RecommendationResponse> results = new ArrayList<>();
        int effectiveLimit = Math.min(limit, processedCandidates.size());
        List<FusionScoringService.FusedCandidate> topCandidates = processedCandidates.subList(0, effectiveLimit);
        Map<Integer, List<String>> contributorTitlesByAnimeId = "similar".equals(mode)
                ? Map.of()
                : buildContributorHints(username, topCandidates);
        int index = 0;
        for (FusionScoringService.FusedCandidate fused : topCandidates) {
            AniListResponse.AnimeInfo anime = hydrateMetadataIfMissing(fused.animeInfo(), allowAniListHydration);
            List<String> reasonCodes = fused.reasonCodes();
            anime.setUserTasteScore(computeUserTasteScore(anime, reasoningContext.topTasteGenres()));
            List<String> contributorTitles = resolveReasonAnchorTitles(
                    mode,
                    anime,
                    contributorTitlesByAnimeId,
                    reasoningContext);
            boolean allowLlmRewrite = canUseLlmForIndex(index);
            String reason = buildReasonSentence(
                    mode,
                    anime,
                    reasonCodes,
                    contributorTitles,
                    reasoningContext,
                    allowLlmRewrite);
            applyRecommendationMeta(anime, reasonCodes, reason);
            results.add(new RecommendationResponse(anime, fused.fusionScore(), reasonCodes));
            index++;
        }
        return results;
    }

    private Double computeUserTasteScore(AniListResponse.AnimeInfo anime, List<String> topTasteGenres) {
        if (anime == null || topTasteGenres == null || topTasteGenres.isEmpty()) {
            return null;
        }
        Set<String> candidateGenres = parseGenreList(anime.getGenres());
        if (candidateGenres.isEmpty()) {
            return null;
        }
        Set<String> taste = new LinkedHashSet<>();
        for (String genre : topTasteGenres) {
            if (genre == null || genre.isBlank()) {
                continue;
            }
            taste.add(genre.trim().toLowerCase());
        }
        if (taste.isEmpty()) {
            return null;
        }
        return genreJaccard(candidateGenres, taste);
    }

    private List<FusionScoringService.FusedCandidate> applySemanticDedupe(
            List<FusionScoringService.FusedCandidate> fusedCandidates,
            String mode) {
        if (!"semantic".equals(mode)
                || !semanticDedupeEnabled
                || fusedCandidates == null
                || fusedCandidates.isEmpty()) {
            return fusedCandidates;
        }

        int maxPerFranchise = Math.max(1, semanticDedupeMaxPerFranchise);
        Map<String, Integer> keptCountByKey = new HashMap<>();
        Map<String, Integer> firstIndexByKey = new HashMap<>();
        List<Boolean> keptIsSpecial = new ArrayList<>(fusedCandidates.size());
        List<FusionScoringService.FusedCandidate> deduped = new ArrayList<>(fusedCandidates.size());

        for (FusionScoringService.FusedCandidate candidate : fusedCandidates) {
            if (candidate == null || candidate.animeInfo() == null || candidate.animeInfo().getId() == null) {
                continue;
            }

            AniListResponse.AnimeInfo anime = candidate.animeInfo();
            String franchiseKey = buildSemanticFranchiseKey(anime);
            if (franchiseKey.isBlank()) {
                franchiseKey = "id:" + anime.getId();
            }
            boolean specialLike = isSpecialLikeEntry(anime);

            int keptForFranchise = keptCountByKey.getOrDefault(franchiseKey, 0);
            if (keptForFranchise < maxPerFranchise) {
                keptCountByKey.put(franchiseKey, keptForFranchise + 1);
                deduped.add(candidate);
                keptIsSpecial.add(specialLike);
                if (keptForFranchise == 0) {
                    firstIndexByKey.put(franchiseKey, deduped.size() - 1);
                }
                continue;
            }

            if (!semanticDedupeSuppressSpecials) {
                continue;
            }

            Integer firstIndex = firstIndexByKey.get(franchiseKey);
            if (firstIndex == null || firstIndex < 0 || firstIndex >= deduped.size()) {
                continue;
            }
            boolean firstIsSpecial = keptIsSpecial.get(firstIndex);
            if (firstIsSpecial && !specialLike) {
                deduped.set(firstIndex, candidate);
                keptIsSpecial.set(firstIndex, false);
            }
        }

        return deduped;
    }

    private String buildSemanticFranchiseKey(AniListResponse.AnimeInfo anime) {
        String title = pickTitleForDedupe(anime);
        if (title.isBlank()) {
            return "";
        }

        String normalized = title.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\b\\d{1,2}(st|nd|rd|th)\\s+season\\b", " ")
                .replaceAll("\\bseason\\s+\\d+\\b", " ")
                .replaceAll("\\bpart\\s+\\d+\\b", " ")
                .replaceAll("\\bcour\\s+\\d+\\b", " ")
                .replaceAll("\\b(final|last|second|third|fourth|fifth)\\s+season\\b", " ")
                .replaceAll("\\b(ova|ona|special|movie|film|recap|summary|compilation|digest)\\b", " ")
                .replaceAll("\\b\\d+\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return normalized;
    }

    private boolean isSpecialLikeEntry(AniListResponse.AnimeInfo anime) {
        String title = pickTitleForDedupe(anime).toLowerCase();
        if (title.isBlank()) {
            return false;
        }
        for (String marker : DEDUPE_SPECIAL_MARKERS) {
            if (title.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private String pickTitleForDedupe(AniListResponse.AnimeInfo anime) {
        if (anime == null || anime.getTitle() == null) {
            return "";
        }
        String english = anime.getTitle().getEnglish();
        if (english != null && !english.isBlank()) {
            return english.trim();
        }
        String romaji = anime.getTitle().getRomaji();
        return romaji == null ? "" : romaji.trim();
    }

    private Map<Integer, List<String>> buildContributorHints(
            String username,
            List<FusionScoringService.FusedCandidate> fusedCandidates) {
        if (username == null || fusedCandidates == null || fusedCandidates.isEmpty()) {
            return Map.of();
        }

        List<WatchedProfile> watchedProfiles = buildWatchedProfiles(username);
        if (watchedProfiles.isEmpty()) {
            return Map.of();
        }

        Map<Integer, List<String>> contributorByAnimeId = new HashMap<>();
        for (FusionScoringService.FusedCandidate fused : fusedCandidates) {
            if (fused == null || fused.animeInfo() == null || fused.reasonCodes() == null) {
                continue;
            }
            Integer animeId = fused.animeInfo().getId();
            if (animeId == null) {
                continue;
            }

            List<String> topContributors = findTopContributorTitles(fused.animeInfo(), watchedProfiles, 5);
            if (!topContributors.isEmpty()) {
                contributorByAnimeId.put(animeId, topContributors);
            }
        }

        return contributorByAnimeId;
    }

    private List<String> resolveReasonAnchorTitles(
            String mode,
            AniListResponse.AnimeInfo anime,
            Map<Integer, List<String>> contributorTitlesByAnimeId,
            ReasoningContext reasoningContext) {
        if ("similar".equals(mode)) {
            return reasoningContext == null
                    ? List.of()
                    : reasoningContext.seedTitles();
        }
        if (anime == null || contributorTitlesByAnimeId == null || contributorTitlesByAnimeId.isEmpty()) {
            return List.of();
        }
        return contributorTitlesByAnimeId.getOrDefault(anime.getId(), List.of());
    }

    private List<String> extractSeedTitlesByIds(List<Integer> seedIds, int maxTitles) {
        if (seedIds == null || seedIds.isEmpty() || maxTitles <= 0) {
            return List.of();
        }

        List<Object[]> metadataRows = embeddingRepository.findMetadataByAnilistIds(seedIds);
        if (metadataRows == null || metadataRows.isEmpty()) {
            return List.of();
        }

        Map<Integer, String> titleById = new HashMap<>();
        for (Object[] row : metadataRows) {
            if (row == null || row.length < 3 || !(row[0] instanceof Number idNumber)) {
                continue;
            }
            String romaji = row[1] instanceof String s ? s : null;
            String english = row[2] instanceof String s ? s : null;
            String title = (english != null && !english.isBlank()) ? english : romaji;
            if (title != null && !title.isBlank()) {
                titleById.put(idNumber.intValue(), title.trim());
            }
        }

        LinkedHashSet<String> orderedTitles = new LinkedHashSet<>();
        for (Integer seedId : seedIds) {
            if (seedId == null) {
                continue;
            }
            String title = titleById.get(seedId);
            if (title == null || title.isBlank()) {
                continue;
            }
            orderedTitles.add(title);
            if (orderedTitles.size() >= maxTitles) {
                break;
            }
        }
        return List.copyOf(orderedTitles);
    }

    private List<String> findTopContributorTitles(
            AniListResponse.AnimeInfo candidate,
            List<WatchedProfile> watchedProfiles,
            int maxTitles) {
        if (candidate == null || watchedProfiles == null || watchedProfiles.isEmpty()) {
            return List.of();
        }
        if (maxTitles <= 0) {
            return List.of();
        }

        Set<String> candidateGenres = parseGenreList(candidate.getGenres());
        List<ScoredContributor> scoredContributors = new ArrayList<>(watchedProfiles.size());

        for (WatchedProfile profile : watchedProfiles) {
            if (profile == null || profile.title() == null || profile.title().isBlank()) {
                continue;
            }

            double similarity = genreJaccard(candidateGenres, profile.genres());
            // Keep some score signal even for sparse/noisy genre metadata.
            double matchScore = (0.85d * similarity) + (0.15d * profile.scoreNorm());
            scoredContributors.add(new ScoredContributor(profile.title(), matchScore));
        }

        if (scoredContributors.isEmpty()) {
            return List.of();
        }

        scoredContributors.sort((a, b) -> {
            int byScore = Double.compare(b.score(), a.score());
            if (byScore != 0) {
                return byScore;
            }
            return a.title().compareToIgnoreCase(b.title());
        });

        LinkedHashSet<String> orderedUniqueTitles = new LinkedHashSet<>();
        for (ScoredContributor contributor : scoredContributors) {
            orderedUniqueTitles.add(contributor.title());
            if (orderedUniqueTitles.size() >= maxTitles) {
                break;
            }
        }
        return List.copyOf(orderedUniqueTitles);
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

    private List<String> buildTopUserGenres(String username, int limit) {
        if (username == null || limit <= 0) {
            return List.of();
        }
        List<AnimeListEntry> userList = animeListEntryService.getUserList(username);
        if (userList == null || userList.isEmpty()) {
            return List.of();
        }

        Map<String, Double> weightedGenres = new HashMap<>();
        for (AnimeListEntry entry : userList) {
            int score = entry.getScore() == null ? 6 : entry.getScore();
            double weight = FusionScoringService.clamp(score / 10.0d, 0.2d, 1.0d);
            for (String genre : parseGenreCsv(entry.getGenres())) {
                weightedGenres.merge(genre, weight, Double::sum);
            }
        }

        if (weightedGenres.isEmpty()) {
            return List.of();
        }

        return weightedGenres.entrySet().stream()
                .sorted((a, b) -> {
                    int byWeight = Double.compare(b.getValue(), a.getValue());
                    if (byWeight != 0) {
                        return byWeight;
                    }
                    return a.getKey().compareTo(b.getKey());
                })
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    private List<String> extractQueryKeywords(String normalizedQuery, int maxKeywords) {
        if (normalizedQuery == null || normalizedQuery.isBlank() || maxKeywords <= 0) {
            return List.of();
        }
        Set<String> keywords = new LinkedHashSet<>();
        for (String token : normalizedQuery.split(" ")) {
            if (token == null || token.isBlank()) {
                continue;
            }
            if (token.length() < 4 || QUERY_STOP_WORDS.contains(token)) {
                continue;
            }
            keywords.add(token);
            if (keywords.size() >= maxKeywords) {
                break;
            }
        }
        return List.copyOf(keywords);
    }

    private List<String> findMatchedQueryThemes(
            AniListResponse.AnimeInfo anime,
            List<String> queryKeywords,
            int maxMatches) {
        if (anime == null || queryKeywords == null || queryKeywords.isEmpty() || maxMatches <= 0) {
            return List.of();
        }
        StringBuilder text = new StringBuilder();
        if (anime.getTitle() != null) {
            if (anime.getTitle().getRomaji() != null) {
                text.append(anime.getTitle().getRomaji()).append(' ');
            }
            if (anime.getTitle().getEnglish() != null) {
                text.append(anime.getTitle().getEnglish()).append(' ');
            }
        }
        if (anime.getGenres() != null) {
            for (String genre : anime.getGenres()) {
                if (genre != null) {
                    text.append(genre).append(' ');
                }
            }
        }
        if (anime.getDescription() != null) {
            text.append(anime.getDescription());
        }
        String normalizedText = text.toString().toLowerCase();

        List<String> matches = new ArrayList<>();
        for (String keyword : queryKeywords) {
            if (normalizedText.contains(keyword.toLowerCase())) {
                matches.add(keyword);
                if (matches.size() >= maxMatches) {
                    break;
                }
            }
        }
        return matches;
    }

    private List<String> findGenreOverlap(
            AniListResponse.AnimeInfo anime,
            List<String> topTasteGenres,
            int maxGenres) {
        if (anime == null || topTasteGenres == null || topTasteGenres.isEmpty() || maxGenres <= 0) {
            return List.of();
        }
        Set<String> animeGenres = parseGenreList(anime.getGenres());
        if (animeGenres.isEmpty()) {
            return List.of();
        }
        List<String> overlap = new ArrayList<>();
        for (String genre : topTasteGenres) {
            if (animeGenres.contains(genre.toLowerCase())) {
                overlap.add(genre);
                if (overlap.size() >= maxGenres) {
                    break;
                }
            }
        }
        return overlap;
    }

    private List<String> topAnimeGenres(AniListResponse.AnimeInfo anime, int maxGenres) {
        if (anime == null || anime.getGenres() == null || anime.getGenres().isEmpty() || maxGenres <= 0) {
            return List.of();
        }
        List<String> picked = new ArrayList<>(maxGenres);
        for (String genre : anime.getGenres()) {
            if (genre == null || genre.isBlank()) {
                continue;
            }
            picked.add(genre);
            if (picked.size() >= maxGenres) {
                break;
            }
        }
        return picked;
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
            List<String> reasonCodes,
            String reasonSentence) {
        if (anime == null) {
            return;
        }

        anime.setReasonCodes((reasonCodes == null || reasonCodes.isEmpty()) ? null : List.copyOf(reasonCodes));
        anime.setRecommendationReason(reasonSentence);
    }

    private String buildReasonSentence(
            String mode,
            AniListResponse.AnimeInfo anime,
            List<String> reasonCodes,
            List<String> contributorTitles,
            ReasoningContext context,
            boolean allowLlmRewrite) {
        Set<String> codes = new LinkedHashSet<>();
        if (reasonCodes != null) {
            codes.addAll(reasonCodes);
        }
        String fallback = buildEmergencyFallback(mode, List.copyOf(codes), contributorTitles);
        return maybeRewriteReasonWithLlm(
                fallback,
                mode,
                anime,
                List.copyOf(codes),
                contributorTitles,
                context,
                allowLlmRewrite);
    }

    /**
     * Backward-compatible helper used by unit tests.
     */
    private String buildReasonSentence(String mode, List<String> reasonCodes, String cfContributor) {
        return buildReasonSentence(
                mode,
                null,
                reasonCodes,
                cfContributor == null || cfContributor.isBlank() ? List.of() : List.of(cfContributor),
                new ReasoningContext(List.of(), List.of(), List.of()),
                false);
    }

    private String buildCfReasonSentence(
            AniListResponse.AnimeInfo anime,
            List<String> contributorTitles,
            List<String> topTasteGenres,
            boolean allowLlmRewrite) {
        String fallback = buildEmergencyFallback(
                "cf",
                List.of(RecommendationResponse.CF_SIGNAL),
                contributorTitles);
        return maybeRewriteReasonWithLlm(
                fallback,
                "cf",
                anime,
                List.of(RecommendationResponse.CF_SIGNAL),
                contributorTitles,
                new ReasoningContext(List.of(), topTasteGenres, List.of()),
                allowLlmRewrite);
    }

    private String maybeRewriteReasonWithLlm(
            String fallback,
            String mode,
            AniListResponse.AnimeInfo anime,
            List<String> reasonCodes,
            List<String> contributorTitles,
            ReasoningContext context,
            boolean allowLlmRewrite) {
        if (!allowLlmRewrite
                || !llmExplanationsEnabled) {
            return fallback;
        }

        try {
            String provider = explanationProvider == null
                    ? "deterministic"
                    : explanationProvider.trim().toLowerCase();
            if (provider.isBlank() || "deterministic".equals(provider) || "none".equals(provider)) {
                return fallback;
            }

            String title = null;
            if (anime != null && anime.getTitle() != null) {
                title = anime.getTitle().getEnglish() != null
                        ? anime.getTitle().getEnglish()
                        : anime.getTitle().getRomaji();
            }

            List<String> queryKeywords = context == null ? List.of() : context.queryKeywords();
            List<String> tasteGenres = context == null ? List.of() : context.topTasteGenres();
            List<String> matchedThemes = findMatchedQueryThemes(anime, queryKeywords, 3);
            List<String> overlapGenres = findGenreOverlap(anime, tasteGenres, 3);
            List<String> animeGenres = topAnimeGenres(anime, 3);
            String cacheKey = buildLlmReasonCacheKey(
                    provider,
                    mode,
                    title,
                    reasonCodes,
                    matchedThemes,
                    overlapGenres,
                    animeGenres,
                    contributorTitles);
            String cached = llmReasonCache.get(cacheKey);
            if (cached != null && !cached.isBlank()) {
                return cached;
            }

            String prompt = """
                    You are writing a recommendation reason for an anime app.
                    Write exactly one natural sentence. Make it specific and user-facing.
                    Sound like a normal person recommending a show to a friend.
                    Use plain language and keep it conversational.
                    Avoid hype, ad copy, exaggeration, and dramatic wording.
                    Do not use exclamation points.
                    Do not mention models, algorithms, scores, confidence, or internal system details.
                    Keep the sentence under 28 words.
                    Prefer concrete language like "similar to X", "shares themes with Y", or "matches your search for Z".
                    Good style examples:
                    - "You liked Steins;Gate and Erased, so this is another tight mystery with time-loop tension."
                    - "Since you liked Haikyuu!! and Kuroko, this has the same competitive team-sports energy."
                    Bad style example:
                    - "If you enjoyed action-packed comedies, you'll love this thrilling masterpiece."

                    Candidate title: %s
                    Mode: %s
                    Reason codes: %s
                    Query keyword matches: %s
                    User taste overlap genres: %s
                    Candidate genres: %s
                    Closest liked show anchors: %s
                    If evidence is weak, still write a natural one-sentence reason without sounding generic.
                    """.formatted(
                    title == null ? "unknown" : title,
                    mode == null ? "semantic" : mode,
                    reasonCodes == null ? List.of() : reasonCodes,
                    matchedThemes,
                    overlapGenres,
                    animeGenres,
                    contributorTitles == null ? List.of() : contributorTitles);
            String rewritten = fallback;
            if ("openai".equals(provider)) {
                rewritten = rewriteWithOpenAi(prompt, fallback);
            } else if ("ollama".equals(provider)) {
                rewritten = rewriteWithOllama(prompt, fallback);
            } else {
                log.debug("Unknown explanation provider '{}', using fallback.", provider);
            }
            llmReasonCache.put(cacheKey, rewritten);
            return rewritten;
        } catch (Exception e) {
            log.debug("LLM explanation rewrite failed, using fallback: {}", e.getMessage());
            return fallback;
        }
    }

    private String rewriteWithOpenAi(String prompt, String fallback) {
        if (openAiExplanationApiKey == null
                || openAiExplanationApiKey.isBlank()
                || openAiExplanationModel == null
                || openAiExplanationModel.isBlank()) {
            return fallback;
        }

        try {
            List<Map<String, String>> messages = List.of(
                    Map.of(
                            "role", "system",
                            "content", "Write one short, natural recommendation sentence in plain conversational tone. No marketing language, no hype, no exclamation marks."),
                    Map.of("role", "user", "content", prompt));

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", openAiExplanationModel);
            requestBody.put("messages", messages);
            requestBody.put("temperature", 0.2);
            requestBody.put("max_tokens", 80);
            String requestJson = explanationObjectMapper.writeValueAsString(requestBody);

            String base = openAiExplanationBaseUrl == null || openAiExplanationBaseUrl.isBlank()
                    ? "https://api.openai.com/v1"
                    : openAiExplanationBaseUrl.trim();
            String endpoint = base.endsWith("/")
                    ? base + "chat/completions"
                    : base + "/chat/completions";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + openAiExplanationApiKey)
                    .timeout(Duration.ofMillis(Math.max(500, openAiExplanationTimeoutMs)))
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

            HttpResponse<String> response = explanationHttpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.debug("OpenAI explanation call returned {}: {}", response.statusCode(), response.body());
                return fallback;
            }

            Map<String, Object> payload = explanationObjectMapper.readValue(
                    response.body(),
                    new TypeReference<>() {});
            Object choicesObj = payload.get("choices");
            if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
                return fallback;
            }
            Object firstChoiceObj = choices.get(0);
            if (!(firstChoiceObj instanceof Map<?, ?> firstChoice)) {
                return fallback;
            }
            Object messageObj = firstChoice.get("message");
            if (!(messageObj instanceof Map<?, ?> message)) {
                return fallback;
            }
            Object contentObj = message.get("content");
            if (!(contentObj instanceof String content) || content.isBlank()) {
                return fallback;
            }
            return sanitizeOneSentence(content, fallback);
        } catch (Exception e) {
            log.debug("OpenAI explanation rewrite failed, using fallback: {}", e.getMessage());
            return fallback;
        }
    }

    private String rewriteWithOllama(String prompt, String fallback) {
        if (ollamaExplanationBaseUrl == null
                || ollamaExplanationBaseUrl.isBlank()
                || ollamaExplanationModel == null
                || ollamaExplanationModel.isBlank()) {
            return fallback;
        }

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", ollamaExplanationModel,
                    "prompt", prompt,
                    "stream", false,
                    "options", Map.of("temperature", 0.2, "num_predict", 60));
            String requestJson = explanationObjectMapper.writeValueAsString(requestBody);

            String endpoint = ollamaExplanationBaseUrl.endsWith("/")
                    ? ollamaExplanationBaseUrl + "api/generate"
                    : ollamaExplanationBaseUrl + "/api/generate";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofMillis(Math.max(500, ollamaExplanationTimeoutMs)))
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

            HttpResponse<String> response = explanationHttpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.debug("Ollama explanation call returned {}: {}", response.statusCode(), response.body());
                return fallback;
            }

            Map<String, Object> payload = explanationObjectMapper.readValue(
                    response.body(),
                    new TypeReference<>() {});
            Object responseText = payload.get("response");
            if (!(responseText instanceof String text) || text.isBlank()) {
                return fallback;
            }
            return sanitizeOneSentence(text, fallback);
        } catch (Exception e) {
            log.debug("Ollama explanation rewrite failed, using fallback: {}", e.getMessage());
            return fallback;
        }
    }

    private String sanitizeOneSentence(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String text = raw
                .replace("\n", " ")
                .replace("\r", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (text.startsWith("\"") && text.endsWith("\"") && text.length() > 1) {
            text = text.substring(1, text.length() - 1).trim();
        }
        if (text.isBlank()) {
            return fallback;
        }

        String[] sentences = text.split("(?<=[.!?])\\s+");
        String first = sentences.length == 0 ? text : sentences[0].trim();
        if (first.isBlank()) {
            return fallback;
        }
        if (!first.endsWith(".") && !first.endsWith("!") && !first.endsWith("?")) {
            first = first + ".";
        }
        if (first.length() > 240) {
            return fallback;
        }
        if (isSalesyReason(first)) {
            return fallback;
        }
        return first;
    }

    private boolean isSalesyReason(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String lowered = text.toLowerCase();
        return lowered.contains("you'll love")
                || lowered.contains("you will love")
                || lowered.contains("must-watch")
                || lowered.contains("masterpiece")
                || lowered.contains("action-packed")
                || lowered.contains("thrilling")
                || lowered.contains("epic");
    }

    private boolean canUseLlmForIndex(int index) {
        if (!llmExplanationsEnabled) {
            return false;
        }
        int limit = llmMaxRewritesPerRequest;
        if (limit <= 0) {
            return true;
        }
        return index < limit;
    }

    private String buildLlmReasonCacheKey(
            String provider,
            String mode,
            String title,
            List<String> reasonCodes,
            List<String> matchedThemes,
            List<String> overlapGenres,
            List<String> animeGenres,
            List<String> contributorTitles) {
        List<String> sortedReasonCodes = new ArrayList<>(reasonCodes == null ? List.of() : reasonCodes);
        Collections.sort(sortedReasonCodes);
        return String.join("|",
                provider == null ? "" : provider,
                mode == null ? "" : mode,
                title == null ? "" : title,
                String.join(",", sortedReasonCodes),
                String.join(",", matchedThemes == null ? List.of() : matchedThemes),
                String.join(",", overlapGenres == null ? List.of() : overlapGenres),
                String.join(",", animeGenres == null ? List.of() : animeGenres),
                String.join(",", contributorTitles == null ? List.of() : contributorTitles));
    }

    private String buildEmergencyFallback(String mode, List<String> reasonCodes, List<String> contributorTitles) {
        if ("similar".equals(mode)) {
            if (contributorTitles != null && !contributorTitles.isEmpty()) {
                return "Recommended because it is similar to " + formatNaturalTitleList(contributorTitles) + ".";
            }
            return "Recommended because it is close to the style of your selected seed shows.";
        }
        if (contributorTitles != null && !contributorTitles.isEmpty()) {
            return "Recommended because you liked " + formatNaturalTitleList(contributorTitles) + ".";
        }
        if (reasonCodes != null && reasonCodes.contains(RecommendationResponse.MATCHES_QUERY)) {
            return "Recommended because it matches your search intent.";
        }
        if (reasonCodes != null && reasonCodes.contains(RecommendationResponse.MATCHES_TASTE_PROFILE)) {
            return "Recommended because it aligns with your watch and rating history.";
        }
        if ("cf".equals(mode) || (reasonCodes != null && reasonCodes.contains(RecommendationResponse.CF_SIGNAL))) {
            return "Recommended because users with similar taste patterns tend to enjoy it.";
        }
        return "Recommended because it is a strong match for your current preferences.";
    }

    private String formatNaturalTitleList(List<String> titles) {
        if (titles == null || titles.isEmpty()) {
            return "similar shows";
        }
        List<String> cleaned = new ArrayList<>(5);
        for (String title : titles) {
            if (title == null || title.isBlank()) {
                continue;
            }
            cleaned.add(title.trim());
            if (cleaned.size() >= 5) {
                break;
            }
        }
        if (cleaned.isEmpty()) {
            return "similar shows";
        }
        if (cleaned.size() == 1) {
            return cleaned.get(0);
        }
        if (cleaned.size() == 2) {
            return cleaned.get(0) + " and " + cleaned.get(1);
        }
        String head = String.join(", ", cleaned.subList(0, cleaned.size() - 1));
        return head + ", and " + cleaned.get(cleaned.size() - 1);
    }

    private List<RecommendationResponse> readSemanticCache(SemanticCacheKey cacheKey) {
        if (!semanticCacheEnabled || cacheKey == null) {
            return null;
        }
        CachedSemanticResults cached = semanticResponseCache.get(cacheKey);
        if (cached == null) {
            semanticCacheMisses.incrementAndGet();
            return null;
        }
        if (cached.expiresAt().isBefore(Instant.now())) {
            semanticResponseCache.remove(cacheKey);
            semanticCacheStaleInvalidations.incrementAndGet();
            semanticCacheMisses.incrementAndGet();
            return null;
        }
        semanticCacheHits.incrementAndGet();
        return copyRecommendationResponses(cached.results());
    }

    private void writeSemanticCache(SemanticCacheKey cacheKey, List<RecommendationResponse> results) {
        if (!semanticCacheEnabled || cacheKey == null || results == null || results.isEmpty()) {
            return;
        }
        Instant expiresAt = Instant.now().plus(Duration.ofHours(Math.max(1, semanticCacheTtlHours)));
        semanticResponseCache.put(
                cacheKey,
                new CachedSemanticResults(copyRecommendationResponses(results), expiresAt));
        if (log.isDebugEnabled()) {
            long hits = semanticCacheHits.get();
            long misses = semanticCacheMisses.get();
            long evictions = semanticCacheEvictions.get();
            long staleInvalidations = semanticCacheStaleInvalidations.get();
            double hitRate = (hits + misses) <= 0 ? 0.0d : (double) hits / (double) (hits + misses);
            log.debug(
                    "Semantic cache stats: hit_rate={}, hits={}, misses={}, evictions={}, stale_invalidations={}",
                    hitRate,
                    hits,
                    misses,
                    evictions,
                    staleInvalidations);
        }
    }

    private List<RecommendationResponse> copyRecommendationResponses(List<RecommendationResponse> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        List<RecommendationResponse> copies = new ArrayList<>(results.size());
        for (RecommendationResponse result : results) {
            if (result == null) {
                continue;
            }
            AniListResponse.AnimeInfo sourceAnime = result.getAnime();
            AniListResponse.AnimeInfo animeCopy = copyAnimeInfo(sourceAnime);
            if (animeCopy != null) {
                animeCopy.setRecommendationReason(sourceAnime.getRecommendationReason());
                animeCopy.setReasonCodes(sourceAnime.getReasonCodes());
                animeCopy.setQueryRelevanceScore(sourceAnime.getQueryRelevanceScore());
                animeCopy.setUserTasteScore(sourceAnime.getUserTasteScore());
                animeCopy.setPopularityPriorScore(sourceAnime.getPopularityPriorScore());
                animeCopy.setGuardrailApplied(sourceAnime.getGuardrailApplied());
            }
            copies.add(new RecommendationResponse(
                    animeCopy,
                    result.getFusionScore(),
                    result.getReasonCodes()));
        }
        return copies;
    }

    private SemanticCacheKey buildSemanticCacheKey(
            String mode,
            String normalizedQuery,
            int limit,
            int topK,
            boolean authenticated,
            String userProfileFingerprint,
            String modelFingerprint,
            String embeddingsFingerprint,
            String controlsFingerprint) {
        return new SemanticCacheKey(
                mode == null ? "semantic" : mode,
                normalizedQuery == null ? "" : normalizedQuery,
                limit,
                topK,
                authenticated,
                userProfileFingerprint == null ? "na" : userProfileFingerprint,
                modelFingerprint == null ? "default" : modelFingerprint,
                embeddingsFingerprint == null ? "unknown" : embeddingsFingerprint,
                controlsFingerprint == null ? "default" : controlsFingerprint);
    }

    private String buildSemanticUserProfileFingerprint(String username, Float requestedListWeight) {
        if (username == null) {
            return "anon";
        }
        try {
            List<AnimeListEntry> userList = animeListEntryService.getUserList(username);
            int hash = 17;
            for (AnimeListEntry entry : userList) {
                if (entry == null || entry.getAnilistId() == null) {
                    continue;
                }
                hash = 31 * hash + Objects.hash(
                        entry.getAnilistId(),
                        entry.getScore(),
                        entry.getStatus(),
                        entry.getUpdatedAt());
            }
            return username + "|" + hash + "|lw=" + (requestedListWeight == null ? "default" : requestedListWeight);
        } catch (Exception ex) {
            log.debug("Could not build user profile fingerprint for semantic cache key: {}", ex.getMessage());
            return username + "|unknown|lw=" + (requestedListWeight == null ? "default" : requestedListWeight);
        }
    }

    private String resolveSemanticModelFingerprint() {
        return String.join("|",
                semanticModelFingerprint == null ? "semantic-v1" : semanticModelFingerprint,
                "useCustomVectors=" + useCustomVectors,
                "rerankTopK=" + semanticRerankTopK,
                "rrfK=" + semanticLexicalRrfK);
    }

    private String resolveEmbeddingsFingerprint() {
        try {
            return customEmbeddingImportStateRepository.findCurrent()
                    .map(CustomEmbeddingImportStateRepository.ImportState::sourceSha256)
                    .filter(value -> value != null && !value.isBlank())
                    .orElse("unknown");
        } catch (Exception ex) {
            log.debug("Could not resolve embedding fingerprint for semantic cache key: {}", ex.getMessage());
            return "unknown";
        }
    }

    private double distanceFromRow(Object[] row) {
        if (row == null || row.length == 0) {
            return 1.0d;
        }
        int distanceIndex = row.length - 1;
        if (row[distanceIndex] instanceof Number distance) {
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

    private float resolveSemanticListBlendWeight(
            float requestedListWeight,
            boolean effectiveListOnly,
            boolean hasQuery,
            boolean broadDiscoveryQuery,
            boolean titleIntentQuery) {
        if (effectiveListOnly || !hasQuery) {
            return requestedListWeight;
        }
        float capped = clampListWeight(semanticListBlendCapWithQuery);
        if (broadDiscoveryQuery) {
            capped = Math.max(capped, clampListWeight(semanticListBlendCapBroadQuery));
        }
        if (titleIntentQuery) {
            capped = Math.min(capped, clampListWeight(semanticListBlendCapTitleIntent));
        }
        return Math.min(requestedListWeight, capped);
    }

    private Float normalizeRequestedListWeightForUser(String username, Float requestedListWeight) {
        if (username != null || requestedListWeight == null) {
            return requestedListWeight;
        }
        if (Math.abs(requestedListWeight) > 1e-6f) {
            log.debug("Ignoring listWeight for anonymous request");
        }
        return null;
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

    private record SemanticRowSelection(
            List<Object[]> rows,
            Set<Integer> lexicalBoostIds) {
    }

    private record SemanticCacheKey(
            String mode,
            String normalizedQuery,
            int limit,
            int topK,
            boolean authenticated,
            String userProfileFingerprint,
            String modelFingerprint,
            String embeddingsFingerprint,
            String controlsFingerprint) {
    }

    private record CachedSemanticResults(
            List<RecommendationResponse> results,
            Instant expiresAt) {
    }

    private record PopularityBlendResult(
            double score,
            Double popularityPriorScore,
            Boolean guardrailApplied) {
    }

    private record WatchedProfile(
            String title,
            Set<String> genres,
            double scoreNorm) {
    }

    private record ScoredContributor(
            String title,
            double score) {
    }

    private record ReasoningContext(
            List<String> queryKeywords,
            List<String> topTasteGenres,
            List<String> seedTitles) {
    }

    private enum PopularityAttenuation {
        LOW,
        MEDIUM,
        HIGH
    }

    private record RecommendationControls(
            boolean includeExtraSeasons,
            boolean includeMovies,
            boolean includeOnasOvasSpecials,
            boolean includeMusic,
            boolean includeAdult,
            PopularityAttenuation popularityAttenuation,
            boolean explicitUserFilters) {
        static RecommendationControls defaults() {
            return new RecommendationControls(
                    false,
                    false,
                    false,
                    false,
                    false,
                    PopularityAttenuation.MEDIUM,
                    false);
        }

        RecommendationControls relaxedForUnderfill() {
            return new RecommendationControls(
                    true,
                    true,
                    true,
                    true,
                    includeAdult,
                    popularityAttenuation == null ? PopularityAttenuation.MEDIUM : popularityAttenuation,
                    explicitUserFilters);
        }

        int recommendedCandidateFloor(int requestedLimit) {
            int safeLimit = Math.max(1, requestedLimit);
            int restrictive = 0;
            if (!includeExtraSeasons) {
                restrictive++;
            }
            if (!includeMovies) {
                restrictive++;
            }
            if (!includeOnasOvasSpecials) {
                restrictive++;
            }
            if (!includeMusic) {
                restrictive++;
            }
            if (!includeAdult) {
                restrictive++;
            }
            double multiplier = 1.0d + (0.35d * restrictive);
            int bonus = restrictive >= 3 ? 8 : 4;
            return (int) Math.ceil(safeLimit * multiplier) + bonus;
        }

        String fingerprint() {
            return String.join(":",
                    Boolean.toString(includeExtraSeasons),
                    Boolean.toString(includeMovies),
                    Boolean.toString(includeOnasOvasSpecials),
                    Boolean.toString(includeMusic),
                    Boolean.toString(includeAdult),
                    popularityAttenuation == null ? PopularityAttenuation.MEDIUM.name() : popularityAttenuation.name(),
                    Boolean.toString(explicitUserFilters));
        }
    }
}


