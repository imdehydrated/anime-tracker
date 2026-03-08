package com.animetracker.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
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
import com.animetracker.dto.RecommendationPageResponse;
import com.animetracker.dto.RecommendationFeedbackRequest;
import com.animetracker.dto.RecommendationResponse;
import com.animetracker.dto.SemanticRequest;
import com.animetracker.entity.AnimeListEntry;
import com.animetracker.entity.RecommendationFeedback;
import com.animetracker.entity.User;
import com.animetracker.exception.BadRequestException;
import com.animetracker.exception.NotFoundException;
import com.animetracker.exception.UnauthorizedException;
import com.animetracker.repository.AnimeEmbeddingRepository;
import com.animetracker.repository.AnimeRelationGraphRepository;
import com.animetracker.repository.AniListSyncStateRepository;
import com.animetracker.repository.CustomEmbeddingImportStateRepository;
import com.animetracker.repository.RecommendationFeedbackRepository;
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
            "anime", "show", "shows", "with", "about", "that", "this", "and", "the", "for", "from",
            "where", "what", "which", "when", "why", "how", "who", "whose", "whom");
    private static final Set<String> QUERY_NEGATION_TOKENS = Set.of("not", "no", "without", "exclude", "excluding");
    private static final Set<String> QUERY_NEGATION_BREAK_TOKENS = Set.of("and", "or", "but", "except");
    private static final Set<String> DEDUPE_SPECIAL_MARKERS = Set.of(
            "special", "ova", "ona", "movie", "film", "recap", "summary", "compilation", "digest");
    private static final Set<String> ENTRYPOINT_RELATION_TYPES = Set.of(
            "PREQUEL",
            "PARENT",
            "PARENT_STORY");
    private static final String CATALOG_POPULATE_SYNC_SOURCE = "catalog_populate";
    private static final int MAX_RECOMMENDATION_RESULTS = 100;

    private final AnimeEmbeddingRepository embeddingRepository;
    private final AnimeRelationGraphRepository relationGraphRepository;
    private final AnimeListEntryService animeListEntryService;
    private final RecommendationFeedbackRepository feedbackRepository;
    private final UserRepository userRepository;
    private final AniListService aniListService;
    private final AnimeEmbeddingPopulatorService populatorService;
    private final MlSidecarService mlSidecarService;
    private final CustomEmbeddingImportStateRepository customEmbeddingImportStateRepository;
    private final AniListSyncStateRepository aniListSyncStateRepository;
    private final RecommendationCandidateTuning candidateTuning;
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
    @Value("${recommendations.semantic.quality-gate-enabled:true}")
    private boolean semanticQualityGateEnabled;
    @Value("${recommendations.semantic.quality-gate-min-score:65}")
    private int semanticQualityGateMinScore;
    @Value("${recommendations.semantic.quality-gate-min-popularity:2000}")
    private int semanticQualityGateMinPopularity;
    @Value("${recommendations.semantic.quality-gate-high-relevance-override:0.82}")
    private float semanticQualityGateHighRelevanceOverride;
    @Value("${recommendations.semantic.sparse-metadata-relevance-floor:0.62}")
    private float semanticSparseMetadataRelevanceFloor;
    @Value("${recommendations.semantic.popularity-prior-normalization-power:2.0}")
    private float semanticPopularityPriorNormalizationPower;
    @Value("${recommendations.semantic.list-blend-cap-with-query:0.08}")
    private float semanticListBlendCapWithQuery;
    @Value("${recommendations.semantic.list-blend-cap-broad-query:0.12}")
    private float semanticListBlendCapBroadQuery;
    @Value("${recommendations.semantic.list-blend-cap-title-intent:0.05}")
    private float semanticListBlendCapTitleIntent;
    @Value("${recommendations.feedback.taste-thumbs-up-weight:1.50}")
    private float feedbackTasteThumbsUpWeight;
    @Value("${recommendations.feedback.taste-thumbs-down-weight:1.00}")
    private float feedbackTasteThumbsDownWeight;
    @Value("${recommendations.feedback.taste-rating-weight:0.70}")
    private float feedbackTasteRatingWeight;
    @Value("${recommendations.feedback.score-adjustment-thumbs-up:0.04}")
    private float feedbackScoreAdjustmentThumbsUp;
    @Value("${recommendations.feedback.score-adjustment-thumbs-down:0.06}")
    private float feedbackScoreAdjustmentThumbsDown;
    @Value("${recommendations.cf.taste-vector-weight:0.20}")
    private float cfTasteVectorWeight;
    @Value("${recommendations.cf.popular-fallback-enabled:true}")
    private boolean cfPopularFallbackEnabled;
    @Value("${recommendations.cf.popular-fallback-min-rated-items:3}")
    private int cfPopularFallbackMinRatedItems;
    @Value("${recommendations.cf.popular-fallback-candidate-limit:100}")
    private int cfPopularFallbackCandidateLimit;
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
    @Value("${recommendations.semantic.catalog-seeding-enabled:true}")
    private boolean semanticCatalogSeedingEnabled;
    @Value("${recommendations.semantic.catalog-seeding-max-per-query:12}")
    private int semanticCatalogSeedingMaxPerQuery;
    @Value("${recommendations.similar.catalog-seeding-max-per-request:12}")
    private int similarCatalogSeedingMaxPerRequest;
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
    @Value("${recommendations.filters.entrypoint-remap-max-hydrations:8}")
    private int controlsEntrypointRemapMaxHydrations;
    @Value("${recommendations.filters.entrypoint-remap-max-hydrations-hard-cap:12}")
    private int controlsEntrypointRemapMaxHydrationsHardCap;
    @Value("${recommendations.filters.entrypoint-remap-max-hydrations-cf:3}")
    private int controlsEntrypointRemapMaxHydrationsCf;
    @Value("${recommendations.filters.entrypoint-remap-failure-circuit-threshold:3}")
    private int controlsEntrypointRemapFailureCircuitThreshold;
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
            AnimeRelationGraphRepository relationGraphRepository,
            AnimeListEntryService animeListEntryService,
            RecommendationFeedbackRepository feedbackRepository,
            UserRepository userRepository,
            AniListService aniListService,
            AnimeEmbeddingPopulatorService populatorService,
            MlSidecarService mlSidecarService,
            CustomEmbeddingImportStateRepository customEmbeddingImportStateRepository,
            AniListSyncStateRepository aniListSyncStateRepository,
            RecommendationCandidateTuning candidateTuning) {
        this.embeddingRepository = embeddingRepository;
        this.relationGraphRepository = relationGraphRepository;
        this.animeListEntryService = animeListEntryService;
        this.feedbackRepository = feedbackRepository;
        this.userRepository = userRepository;
        this.aniListService = aniListService;
        this.populatorService = populatorService;
        this.mlSidecarService = mlSidecarService;
        this.customEmbeddingImportStateRepository = customEmbeddingImportStateRepository;
        this.aniListSyncStateRepository = aniListSyncStateRepository;
        this.candidateTuning = candidateTuning;
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

    /**
     * Additive paged recommendation contract used by lazy-loading clients.
     * Cursor is context-bound and auto-resets when request context changes.
     */
    public RecommendationPageResponse recommendPaged(
            String username,
            List<Integer> seedIds,
            String query,
            Integer requestedLimit,
            boolean useListOnly,
            Float requestedListWeight,
            String mode,
            SemanticRequest.Filters filters,
            String cursor,
            Integer requestedPageSize) {
        int pageSize = normalizePageSize(requestedPageSize);
        String pagingContextFingerprint = buildPagingContextFingerprint(
                username,
                seedIds,
                query,
                useListOnly,
                requestedListWeight,
                mode,
                filters,
                pageSize);
        int offset = decodePagingCursorOffset(cursor, pagingContextFingerprint);
        int targetWindow = Math.max(1, offset + pageSize + 1);
        int fetchLimit = Math.min(MAX_RECOMMENDATION_RESULTS, Math.max(
                targetWindow,
                requestedLimit == null ? pageSize : normalizeLimit(requestedLimit)));

        List<RecommendationResponse> ranked = recommend(
                username,
                seedIds,
                query,
                fetchLimit,
                useListOnly,
                requestedListWeight,
                mode,
                filters);

        if (ranked == null || ranked.isEmpty() || offset >= ranked.size()) {
            return new RecommendationPageResponse(List.of(), null, false, Map.of(
                    "cursor_reset", Boolean.TRUE,
                    "offset", offset,
                    "page_size", pageSize));
        }

        int endExclusive = Math.min(ranked.size(), offset + pageSize);
        List<RecommendationResponse> items = List.copyOf(ranked.subList(offset, endExclusive));
        boolean hasMore = ranked.size() > endExclusive && endExclusive < MAX_RECOMMENDATION_RESULTS;
        String nextCursor = hasMore
                ? encodePagingCursor(endExclusive, pagingContextFingerprint)
                : null;
        return new RecommendationPageResponse(items, nextCursor, hasMore, Map.of(
                "offset", offset,
                "page_size", pageSize,
                "fetched", ranked.size(),
                "cursor_bound", Boolean.TRUE));
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
            log.warn("Semantic mode ignores seedIds; use mode=similar for seed-based recommendations");
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
                    controls.fingerprint(),
                    resolveExplanationFingerprint());
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
        Map<Integer, String> feedbackSignals = username == null ? Map.of() : loadFeedbackSignalMap(username);

        if (searchVector == null) {
            return List.of();
        }

        List<Integer> excludeIds = buildExcludeIds(username, List.of());
        String vectorStr = EmbeddingService.toVectorString(searchVector);
        int controlsCandidateFloor = controls.recommendedCandidateFloor(limit);
        int vectorCandidateLimit = Math.max(
                Math.max(limit, candidateTuning.semanticVectorCandidateLimit()),
                controlsCandidateFloor);
        int mergedCandidateLimit = Math.max(
                Math.max(limit, candidateTuning.semanticMergedCandidateLimit()),
                controlsCandidateFloor);
        SemanticRowSelection rowSelection = selectSemanticRows(
                vectorStr,
                excludeIds,
                vectorCandidateLimit,
                mergedCandidateLimit,
                normalizedQuery);
        if (hasQuery && rowSelection.rows().size() < controlsCandidateFloor) {
            int shortfall = controlsCandidateFloor - rowSelection.rows().size();
            int seeded = seedCatalogEmbeddingsForSemanticQuery(
                    normalizedQuery,
                    controls,
                    excludeIds,
                    shortfall);
            if (seeded > 0) {
                rowSelection = selectSemanticRows(
                        vectorStr,
                        excludeIds,
                        vectorCandidateLimit,
                        mergedCandidateLimit,
                        normalizedQuery);
            }
        }
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
        results = applyRecommendationControls(results, controls, "semantic", limit, feedbackSignals);
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
        Map<Integer, String> feedbackSignals = loadFeedbackSignalMap(username);
        double cfTasteBlendWeight = FusionScoringService.clamp(cfTasteVectorWeight, 0.0d, 0.35d);
        float[] tasteVector = cfTasteBlendWeight > 0.0d ? buildUserPreferenceVector(username) : null;

        int minRatingsForCf = Math.max(0, cfPopularFallbackMinRatedItems);
        if (cfPopularFallbackEnabled && userRatings.size() < minRatingsForCf) {
            return recommendCfPopularFallback(username, excludeIds, limit, controls, feedbackSignals, watchedProfiles);
        }

        int cfFetchLimit = Math.max(
                limit,
                Math.min(MAX_RECOMMENDATION_RESULTS, controls.recommendedCandidateFloor(limit)));
        List<Map<String, Object>> predictions = mlSidecarService.getCfRecommendations(
                userRatings, excludeIds, cfFetchLimit);

        if (predictions == null || predictions.isEmpty()) {
            if (cfPopularFallbackEnabled) {
                return recommendCfPopularFallback(username, excludeIds, limit, controls, feedbackSignals, watchedProfiles);
            }
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
        Map<Integer, float[]> candidateEmbeddingVectors = (tasteVector == null || predictedIds.isEmpty())
                ? Map.of()
                : loadEmbeddingVectorMap(predictedIds);

        // Build recommendation payload using local metadata only.
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
            double tasteSimilarity = Double.NaN;
            if (tasteVector != null && !candidateEmbeddingVectors.isEmpty()) {
                tasteSimilarity = normalizedCosineSimilarity(tasteVector, candidateEmbeddingVectors.get(anilistId));
                normalizedScore = blendCfWithTasteVectorScore(normalizedScore, tasteSimilarity, cfTasteBlendWeight);
            }
            try {
                AniListResponse.AnimeInfo anime = localMetadataById.get(anilistId);
                if (anime == null) {
                    anime = loadMetadataFromStore(anilistId);
                }
                if (anime != null) {
                    if (!Double.isNaN(tasteSimilarity)) {
                        anime.setUserTasteScore(tasteSimilarity);
                    }
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
                log.warn("Failed to resolve local metadata for anime {} in CF result: {}", anilistId, e.getMessage());
            }
        }
        return applyRecommendationControls(results, controls, "cf", limit, feedbackSignals);
    }

    private List<RecommendationResponse> recommendCfPopularFallback(
            String username,
            List<Integer> excludeIds,
            int limit,
            RecommendationControls controls,
            Map<Integer, String> feedbackSignals,
            List<WatchedProfile> watchedProfiles) {
        int configuredLimit = Math.max(1, Math.min(MAX_RECOMMENDATION_RESULTS, cfPopularFallbackCandidateLimit));
        int targetLimit = Math.max(limit, controls.recommendedCandidateFloor(limit));
        int fetchLimit = Math.max(1, Math.min(MAX_RECOMMENDATION_RESULTS, Math.min(configuredLimit, targetLimit)));

        List<Object[]> rows = embeddingRepository.findTopPopularMetadataExcluding(excludeIds, fetchLimit);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        List<String> topTasteGenres = buildTopUserGenres(username, 3);
        List<RecommendationResponse> results = new ArrayList<>(rows.size());
        int explanationRewriteIndex = 0;
        for (Object[] row : rows) {
            AniListResponse.AnimeInfo anime = mapMetadataRowToAnimeInfo(row);
            if (anime == null || anime.getId() == null) {
                continue;
            }
            double popularityNorm = normalizePopularityForAttenuation(anime.getPopularity(), anime.getAverageScore());
            double scoreNorm = anime.getAverageScore() == null
                    ? 0.60d
                    : FusionScoringService.clamp(anime.getAverageScore() / 100.0d, 0.0d, 1.0d);
            double fallbackScore = FusionScoringService.clamp((0.60d * popularityNorm) + (0.40d * scoreNorm), 0.0d, 1.0d);
            List<String> reasonCodes = List.of(RecommendationResponse.CF_SIGNAL);
            List<String> contributorTitles = findTopContributorTitles(anime, watchedProfiles, 5);
            String reasonSentence = buildCfReasonSentence(
                    anime,
                    contributorTitles,
                    topTasteGenres,
                    canUseLlmForIndex(explanationRewriteIndex));
            applyRecommendationMeta(anime, reasonCodes, reasonSentence);
            results.add(new RecommendationResponse(anime, fallbackScore, reasonCodes));
            explanationRewriteIndex++;
        }

        return applyRecommendationControls(results, controls, "cf", limit, feedbackSignals);
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
        int similarPoolLimit = Math.max(
                Math.max(limit, candidateTuning.semanticSimilarCandidateLimit()),
                controls.recommendedCandidateFloor(limit));
        List<Object[]> candidates = findSimilarRows(vectorStr, excludeIds, similarPoolLimit);
        if (candidates.size() < similarPoolLimit) {
            int shortfall = similarPoolLimit - candidates.size();
            int seeded = seedCatalogEmbeddingsForSimilarRequest(
                    normalizedSeeds,
                    seedTitles,
                    controls,
                    excludeIds,
                    shortfall);
            if (seeded > 0) {
                candidates = findSimilarRows(vectorStr, excludeIds, similarPoolLimit);
            }
        }
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
        Map<Integer, String> feedbackSignals = username == null ? Map.of() : loadFeedbackSignalMap(username);
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
        return applyRecommendationControls(results, controls, "similar", limit, feedbackSignals);
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

    private int seedCatalogEmbeddingsForSemanticQuery(
            String normalizedQuery,
            RecommendationControls controls,
            List<Integer> excludeIds,
            int requestedBudget) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return 0;
        }
        List<Integer> candidateIds = collectCatalogCandidateIdsByQuery(
                normalizedQuery,
                controls,
                excludeIds,
                requestedBudget);
        return seedMissingEmbeddings(candidateIds, requestedBudget);
    }

    private int seedCatalogEmbeddingsForSimilarRequest(
            List<Integer> seedIds,
            List<String> seedTitles,
            RecommendationControls controls,
            List<Integer> excludeIds,
            int requestedBudget) {
        int budget = resolveCatalogSeedingBudget(
                Math.max(0, requestedBudget),
                Math.max(0, similarCatalogSeedingMaxPerRequest));
        if (budget <= 0) {
            return 0;
        }

        LinkedHashSet<String> queryTerms = new LinkedHashSet<>();
        if (seedTitles != null) {
            for (String title : seedTitles) {
                if (title != null && !title.isBlank()) {
                    queryTerms.add(title.trim());
                }
            }
        }
        if ((queryTerms.isEmpty()) && seedIds != null) {
            for (Integer seedId : seedIds) {
                if (seedId == null || seedId <= 0) {
                    continue;
                }
                AniListResponse.AnimeInfo seed = aniListService.getAnimeByIdLocalOnly(seedId);
                if (seed == null || seed.getTitle() == null) {
                    continue;
                }
                String title = seed.getTitle().getEnglish();
                if (title == null || title.isBlank()) {
                    title = seed.getTitle().getRomaji();
                }
                if (title != null && !title.isBlank()) {
                    queryTerms.add(title.trim());
                }
                if (queryTerms.size() >= 5) {
                    break;
                }
            }
        }

        if (queryTerms.isEmpty()) {
            return 0;
        }

        List<Integer> candidateIds = new ArrayList<>();
        for (String term : queryTerms) {
            if (candidateIds.size() >= budget * 4) {
                break;
            }
            List<Integer> ids = collectCatalogCandidateIdsByQuery(term, controls, excludeIds, budget);
            for (Integer id : ids) {
                if (id == null || candidateIds.contains(id)) {
                    continue;
                }
                candidateIds.add(id);
            }
        }
        return seedMissingEmbeddings(candidateIds, budget);
    }

    private List<Integer> collectCatalogCandidateIdsByQuery(
            String queryText,
            RecommendationControls controls,
            List<Integer> excludeIds,
            int requestedBudget) {
        int budget = resolveCatalogSeedingBudget(
                Math.max(0, requestedBudget),
                Math.max(0, semanticCatalogSeedingMaxPerQuery));
        if (budget <= 0 || queryText == null || queryText.isBlank()) {
            return List.of();
        }

        AniListService.SearchFilters filters = toSearchFilters(controls);
        int searchLimit = Math.min(
                MAX_RECOMMENDATION_RESULTS,
                Math.max(budget * 4, budget));
        List<AniListResponse.AnimeInfo> hits = aniListService.searchAnime(queryText, filters, 0, searchLimit);
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }

        Set<Integer> excluded = excludeIds == null ? Set.of() : new LinkedHashSet<>(excludeIds);
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        for (AniListResponse.AnimeInfo hit : hits) {
            if (hit == null || hit.getId() == null || hit.getId() <= 0) {
                continue;
            }
            if (excluded.contains(hit.getId())) {
                continue;
            }
            ids.add(hit.getId());
            if (ids.size() >= searchLimit) {
                break;
            }
        }
        return ids.isEmpty() ? List.of() : new ArrayList<>(ids);
    }

    private int seedMissingEmbeddings(List<Integer> candidateIds, int requestedBudget) {
        int budget = resolveCatalogSeedingBudget(
                Math.max(0, requestedBudget),
                Math.max(0, semanticCatalogSeedingMaxPerQuery));
        if (!semanticCatalogSeedingEnabled
                || budget <= 0
                || candidateIds == null
                || candidateIds.isEmpty()
                || !useCustomVectors
                || !mlSidecarService.isEnabled()) {
            return 0;
        }

        List<Integer> normalizedIds = normalizeIds(candidateIds);
        if (normalizedIds.isEmpty()) {
            return 0;
        }

        Set<Integer> embeddedIds = new LinkedHashSet<>();
        for (Object[] row : findEmbeddingRowsByIds(normalizedIds)) {
            if (row != null && row.length > 0 && row[0] instanceof Integer anilistId) {
                embeddedIds.add(anilistId);
            }
        }

        int seeded = 0;
        for (Integer anilistId : normalizedIds) {
            if (anilistId == null || anilistId <= 0 || embeddedIds.contains(anilistId)) {
                continue;
            }
            embedOnTheFly(anilistId);
            seeded++;
            if (seeded >= budget) {
                break;
            }
        }
        if (seeded > 0) {
            log.info("Catalog embedding seeding: seeded={} budget={} candidates={}", seeded, budget, normalizedIds.size());
        }
        return seeded;
    }

    private int resolveCatalogSeedingBudget(int requestedBudget, int maxBudget) {
        int cappedMax = Math.max(0, maxBudget);
        if (cappedMax <= 0) {
            return 0;
        }
        return Math.min(cappedMax, Math.max(0, requestedBudget));
    }

    private AniListService.SearchFilters toSearchFilters(RecommendationControls controls) {
        RecommendationControls effective = controls == null ? RecommendationControls.defaults() : controls;
        return AniListService.SearchFilters.fromNullable(
                effective.includeExtraSeasons(),
                effective.includeMovies(),
                effective.includeOnasOvasSpecials(),
                effective.includeMusic(),
                effective.includeAdult());
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
                Math.max(5, candidateTuning.semanticLexicalCandidateLimit()));
        if (shouldRunSecondPassLexical(normalizedQuery, vectorRows)) {
            String expandedLexical = buildSecondPassLexicalQueryText(
                    lexicalQueryText,
                    vectorRows,
                    lexicalRows == null ? List.of() : lexicalRows);
            if (!expandedLexical.isBlank() && !expandedLexical.equals(lexicalQueryText)) {
                List<Object[]> secondPassLexical = embeddingRepository.findLexicalMatches(
                        expandedLexical,
                        excludeIds,
                        Math.max(5, candidateTuning.semanticLexicalCandidateLimit()));
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
            if (!similarMode
                    && anime != null
                    && isSparseRecommendationMetadata(anime)
                    && queryRelevance < FusionScoringService.clamp(semanticSparseMetadataRelevanceFloor, 0.45d, 0.90d)) {
                guardrailApplied = tasteWeight > 0.0d || popularityWeight > 0.0d;
                tasteWeight = 0.0d;
                popularityWeight = 0.0d;
            }
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
        return applyRecommendationControls(input, controls, mode, limit, Map.of());
    }

    private List<RecommendationResponse> applyRecommendationControls(
            List<RecommendationResponse> input,
            RecommendationControls controls,
            String mode,
            int limit,
            Map<Integer, String> feedbackSignals) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        RecommendationControls effectiveControls = controls == null
                ? RecommendationControls.defaults()
                : controls;
        Map<Integer, String> effectiveFeedbackSignals = feedbackSignals == null ? Map.of() : feedbackSignals;
        List<RecommendationResponse> filtered = filterAndScoreRecommendations(
                input,
                effectiveControls,
                mode,
                effectiveFeedbackSignals,
                limit);
        int underfillTarget = resolveUnderfillTarget(limit);
        if (filtered.size() < underfillTarget && !effectiveControls.explicitUserFilters()) {
            RecommendationControls relaxedControls = effectiveControls.relaxedForUnderfill();
            List<RecommendationResponse> relaxed = filterAndScoreRecommendations(
                    input,
                    relaxedControls,
                    mode,
                    effectiveFeedbackSignals,
                    limit);
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
        filtered = applySemanticDedupeOnResponses(filtered, mode);

        int safeLimit = Math.max(1, limit);
        if (filtered.size() <= safeLimit) {
            return filtered;
        }
        return List.copyOf(filtered.subList(0, safeLimit));
    }

    private List<RecommendationResponse> applySemanticDedupeOnResponses(
            List<RecommendationResponse> candidates,
            String mode) {
        if (!"semantic".equals(mode)
                || !semanticDedupeEnabled
                || candidates == null
                || candidates.isEmpty()) {
            return candidates;
        }

        int maxPerFranchise = Math.max(1, semanticDedupeMaxPerFranchise);
        Map<String, Integer> keptCountByKey = new HashMap<>();
        Map<String, Integer> firstIndexByKey = new HashMap<>();
        List<Boolean> keptIsSpecial = new ArrayList<>(candidates.size());
        List<RecommendationResponse> deduped = new ArrayList<>(candidates.size());

        for (RecommendationResponse candidate : candidates) {
            if (candidate == null || candidate.getAnime() == null || candidate.getAnime().getId() == null) {
                continue;
            }
            AniListResponse.AnimeInfo anime = candidate.getAnime();
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

    private List<RecommendationResponse> filterAndScoreRecommendations(
            List<RecommendationResponse> input,
            RecommendationControls controls,
            String mode) {
        return filterAndScoreRecommendations(input, controls, mode, Map.of(), 15);
    }

    private List<RecommendationResponse> filterAndScoreRecommendations(
            List<RecommendationResponse> input,
            RecommendationControls controls,
            String mode,
            Map<Integer, String> feedbackSignals,
            int requestedLimit) {
        List<RecommendationResponse> filtered = new ArrayList<>(input.size());
        int controlHydrationBudget = resolveControlHydrationBudget(input, controls, requestedLimit, mode);
        RelationResolutionState relationResolutionState = new RelationResolutionState(
                new HashMap<>(),
                new HashMap<>(),
                controlHydrationBudget);
        Map<Integer, RecommendationResponse> dedupedByAnimeId = new LinkedHashMap<>();
        for (RecommendationResponse row : input) {
            if (row == null || row.getAnime() == null) {
                continue;
            }
            AniListResponse.AnimeInfo anime = enrichAnimeForControlFiltering(
                    row.getAnime(),
                    controls,
                    relationResolutionState);
            if (AnimeFilterPolicy.isExcludedByStatus(anime)) {
                continue;
            }
            if (isExcludedBySemanticQualityGate(anime, mode)) {
                continue;
            }
            if (!controls.includeAdult() && AnimeFilterPolicy.isAdultCandidate(anime, 80)) {
                continue;
            }

            anime = remapToEntrypointIfExcludedByControls(
                    anime,
                    controls,
                    relationResolutionState);
            if (anime == null) {
                continue;
            }
            if (!controls.includeMusic() && AnimeFilterPolicy.isMusicCandidate(anime)) {
                continue;
            }
            if (isExcludedByFormatAndSeasonControls(anime, controls, relationResolutionState)) {
                continue;
            }

            double baseScore = row.getFusionScore() == null
                    ? numberValue(anime.getQueryRelevanceScore(), 0.0d)
                    : row.getFusionScore();
            double adjustedScore = applyPopularityAttenuation(
                    baseScore,
                    anime.getPopularity(),
                    anime.getAverageScore(),
                    controls.popularityAttenuation(),
                    mode);
            String feedbackSignal = anime.getId() == null ? null : feedbackSignals.get(anime.getId());
            adjustedScore = applyFeedbackScoreAdjustment(adjustedScore, feedbackSignal);
            RecommendationResponse candidate = new RecommendationResponse(anime, adjustedScore, row.getReasonCodes());
            Integer dedupeId = anime.getId();
            if (dedupeId == null) {
                filtered.add(candidate);
                continue;
            }
            RecommendationResponse existing = dedupedByAnimeId.get(dedupeId);
            if (existing == null
                    || numberValue(candidate.getFusionScore(), 0.0d) > numberValue(existing.getFusionScore(), 0.0d)) {
                dedupedByAnimeId.put(dedupeId, candidate);
            }
        }
        filtered.addAll(dedupedByAnimeId.values());
        return filtered;
    }

    private boolean isExcludedBySemanticQualityGate(
            AniListResponse.AnimeInfo anime,
            String mode) {
        if (!semanticQualityGateEnabled || anime == null) {
            return false;
        }
        String normalizedMode = mode == null ? "semantic" : mode.trim().toLowerCase();
        if (!"semantic".equals(normalizedMode)) {
            return false;
        }

        double relevance = numberValue(anime.getQueryRelevanceScore(), Double.NaN);
        double overrideThreshold = FusionScoringService.clamp(
                semanticQualityGateHighRelevanceOverride,
                0.60d,
                0.98d);
        if (!Double.isNaN(relevance) && relevance >= overrideThreshold) {
            return false;
        }

        boolean missingDescription = anime.getDescription() == null || anime.getDescription().isBlank();
        boolean missingTags = anime.getTags() == null || anime.getTags().isEmpty();
        boolean missingGenres = anime.getGenres() == null || anime.getGenres().isEmpty();
        boolean sparseMetadata = missingDescription && missingTags && missingGenres;
        if (!sparseMetadata) {
            return false;
        }

        Integer score = anime.getAverageScore();
        Integer popularity = anime.getPopularity();
        boolean lowOrMissingScore = score == null || score < Math.max(0, semanticQualityGateMinScore);
        boolean lowOrMissingPopularity = popularity == null || popularity < Math.max(0, semanticQualityGateMinPopularity);
        return lowOrMissingScore && lowOrMissingPopularity;
    }

    private boolean isSparseRecommendationMetadata(AniListResponse.AnimeInfo anime) {
        if (anime == null) {
            return true;
        }
        boolean missingDescription = anime.getDescription() == null || anime.getDescription().isBlank();
        boolean missingTags = anime.getTags() == null || anime.getTags().isEmpty();
        boolean missingGenres = anime.getGenres() == null || anime.getGenres().isEmpty();
        return missingDescription && missingTags && missingGenres;
    }

    private int resolveControlHydrationBudget(
            List<RecommendationResponse> input,
            RecommendationControls controls,
            int fallbackLimit,
            String mode) {
        int configured = Math.max(0, controlsEntrypointRemapMaxHydrations);
        int hardCap = Math.max(configured, Math.max(0, controlsEntrypointRemapMaxHydrationsHardCap));
        if (input == null || input.isEmpty()) {
            return Math.min(configured, hardCap);
        }
        if (controls == null) {
            return Math.min(configured, hardCap);
        }
        if ("cf".equalsIgnoreCase(mode)) {
            int cfBudget = Math.max(0, controlsEntrypointRemapMaxHydrationsCf);
            return Math.min(input.size(), Math.min(cfBudget, hardCap));
        }
        int strictToggleCount = 0;
        if (!controls.includeExtraSeasons()) {
            strictToggleCount++;
        }
        if (!controls.includeMovies()) {
            strictToggleCount++;
        }
        if (!controls.includeOnasOvasSpecials()) {
            strictToggleCount++;
        }
        if (!controls.includeMusic()) {
            strictToggleCount++;
        }
        if (!controls.includeAdult()) {
            strictToggleCount++;
        }
        if (strictToggleCount == 0) {
            return Math.min(configured, hardCap);
        }
        int safeLimit = Math.max(1, fallbackLimit);
        int adaptive = Math.max(safeLimit * 3, controls.recommendedCandidateFloor(safeLimit));
        int cappedAdaptive = Math.min(input.size(), adaptive);
        return Math.min(Math.max(configured, cappedAdaptive), hardCap);
    }

    private AniListResponse.AnimeInfo remapToEntrypointIfExcludedByControls(
            AniListResponse.AnimeInfo anime,
            RecommendationControls controls,
            RelationResolutionState relationResolutionState) {
        if (anime == null || anime.getId() == null) {
            return anime;
        }
        if (!isExcludedByFormatAndSeasonControls(anime, controls, relationResolutionState)) {
            return anime;
        }

        Integer entrypointId = resolveEntrypointAnimeId(anime.getId(), relationResolutionState);
        if (entrypointId == null || Objects.equals(entrypointId, anime.getId())) {
            return null;
        }
        AniListResponse.AnimeInfo entrypoint = loadAnimeWithRelations(entrypointId, relationResolutionState);
        if (entrypoint == null) {
            return null;
        }
        AniListResponse.AnimeInfo remapped = remapScoredCandidateToEntrypoint(anime, entrypoint);
        if (isExcludedByFormatAndSeasonControls(remapped, controls, relationResolutionState)) {
            return null;
        }
        return remapped;
    }

    private AniListResponse.AnimeInfo enrichAnimeForControlFiltering(
            AniListResponse.AnimeInfo anime,
            RecommendationControls controls,
            RelationResolutionState relationResolutionState) {
        if (anime == null || anime.getId() == null || controls == null || relationResolutionState == null) {
            return anime;
        }

        boolean missingFormat = anime.getFormat() == null || anime.getFormat().isBlank();
        boolean missingAdultSignals = anime.getIsAdult() == null
                && (anime.getTags() == null || anime.getTags().isEmpty());
        boolean missingRelations = anime.getRelations() == null || anime.getRelations().isEmpty();

        boolean needsFormatHydration = missingFormat
                && (!controls.includeMovies() || !controls.includeOnasOvasSpecials() || !controls.includeMusic());
        boolean needsAdultHydration = missingAdultSignals && !controls.includeAdult();
        boolean needsRelationHydration = missingRelations && !controls.includeExtraSeasons();
        if (!needsFormatHydration && !needsAdultHydration && !needsRelationHydration) {
            return anime;
        }

        AniListResponse.AnimeInfo hydrated = loadAnimeWithRelations(anime.getId(), relationResolutionState);
        if (hydrated == null) {
            return anime;
        }
        mergeAnimeInfo(anime, hydrated);
        if ((anime.getRelations() == null || anime.getRelations().isEmpty())
                && hydrated.getRelations() != null
                && !hydrated.getRelations().isEmpty()) {
            anime.setRelations(hydrated.getRelations());
        }
        if ((anime.getSynonyms() == null || anime.getSynonyms().isEmpty())
                && hydrated.getSynonyms() != null
                && !hydrated.getSynonyms().isEmpty()) {
            anime.setSynonyms(hydrated.getSynonyms());
        }
        return anime;
    }

    private AniListResponse.AnimeInfo remapScoredCandidateToEntrypoint(
            AniListResponse.AnimeInfo original,
            AniListResponse.AnimeInfo entrypoint) {
        AniListResponse.AnimeInfo remapped = copyAnimeInfo(entrypoint);
        if (remapped == null) {
            remapped = new AniListResponse.AnimeInfo();
            remapped.setId(entrypoint.getId());
            remapped.setTitle(entrypoint.getTitle());
        }
        remapped.setRecommendationReason(original.getRecommendationReason());
        remapped.setReasonCodes(original.getReasonCodes());
        remapped.setFusionScore(original.getFusionScore());
        remapped.setQueryAdherenceScore(original.getQueryAdherenceScore());
        remapped.setQueryRelevanceScore(original.getQueryRelevanceScore());
        remapped.setUserTasteScore(original.getUserTasteScore());
        remapped.setPopularityPriorScore(original.getPopularityPriorScore());
        remapped.setGuardrailApplied(original.getGuardrailApplied());
        return remapped;
    }

    private boolean isExcludedByFormatAndSeasonControls(
            AniListResponse.AnimeInfo anime,
            RecommendationControls controls,
            RelationResolutionState relationResolutionState) {
        if (anime == null || controls == null) {
            return false;
        }
        if (!controls.includeMovies() && AnimeFilterPolicy.isMovieCandidate(anime)) {
            return true;
        }
        if (!controls.includeOnasOvasSpecials() && AnimeFilterPolicy.isOnaOvaSpecialCandidate(anime)) {
            return true;
        }
        if (!controls.includeExtraSeasons() && isExtraSeasonCandidateWithRelations(anime, relationResolutionState)) {
            return true;
        }
        return false;
    }

    private boolean isExtraSeasonCandidateWithRelations(
            AniListResponse.AnimeInfo anime,
            RelationResolutionState relationResolutionState) {
        if (anime == null || anime.getId() == null) {
            return false;
        }
        Set<Integer> graphFlagged = relationGraphRepository.findAnimeIdsHavingRelationType(
                List.of(anime.getId()),
                ENTRYPOINT_RELATION_TYPES);
        return graphFlagged.contains(anime.getId());
    }

    private Integer resolveEntrypointAnimeId(Integer animeId, RelationResolutionState relationResolutionState) {
        if (animeId == null) {
            return null;
        }
        Integer cached = relationResolutionState.entrypointByAnimeId().get(animeId);
        if (cached != null) {
            return cached;
        }
        Integer graphResolved = relationGraphRepository.resolveEntrypoint(animeId, ENTRYPOINT_RELATION_TYPES, 12);
        if (graphResolved != null && graphResolved > 0) {
            relationResolutionState.entrypointByAnimeId().put(animeId, graphResolved);
            return graphResolved;
        }
        relationResolutionState.entrypointByAnimeId().put(animeId, animeId);
        return animeId;
    }

    private AniListResponse.AnimeInfo loadAnimeWithRelations(
            Integer animeId,
            RelationResolutionState relationResolutionState) {
        if (animeId == null || relationResolutionState == null) {
            return null;
        }
        if (relationResolutionState.animeById().containsKey(animeId)) {
            return relationResolutionState.animeById().get(animeId);
        }
        if (relationResolutionState.shouldBypassHydration(controlsEntrypointRemapFailureCircuitThreshold)) {
            return null;
        }
        if (relationResolutionState.remainingHydrations() <= 0) {
            return null;
        }
        try {
            AniListResponse.AnimeInfo fetched = aniListService.getAnimeByIdLocalOnly(animeId);
            if (fetched == null) {
                relationResolutionState.animeById().put(animeId, null);
                relationResolutionState.consumeHydration();
                relationResolutionState.recordHydrationFailure(controlsEntrypointRemapFailureCircuitThreshold);
                return null;
            }
            relationResolutionState.animeById().put(animeId, fetched);
            relationResolutionState.consumeHydration();
            relationResolutionState.recordHydrationSuccess();
            return fetched;
        } catch (Exception e) {
            log.debug("Entrypoint relation load failed for anime {}: {}", animeId, e.getMessage());
            relationResolutionState.animeById().put(animeId, null);
            relationResolutionState.consumeHydration();
            relationResolutionState.recordHydrationFailure(controlsEntrypointRemapFailureCircuitThreshold);
            return null;
        }
    }

    private String normalizeRelationType(String relationType) {
        if (relationType == null || relationType.isBlank()) {
            return "";
        }
        return relationType.trim()
                .toUpperCase()
                .replace(' ', '_')
                .replace('-', '_');
    }

    private double applyFeedbackScoreAdjustment(double baseScore, String feedbackSignal) {
        if (feedbackSignal == null || feedbackSignal.isBlank()) {
            return FusionScoringService.clamp(baseScore, 0.0d, 1.0d);
        }
        double upDelta = FusionScoringService.clamp(feedbackScoreAdjustmentThumbsUp, 0.0d, 0.20d);
        double downDelta = FusionScoringService.clamp(feedbackScoreAdjustmentThumbsDown, 0.0d, 0.20d);
        return switch (feedbackSignal) {
            case RecommendationFeedback.SIGNAL_THUMBS_UP -> FusionScoringService.clamp(baseScore + upDelta, 0.0d, 1.0d);
            case RecommendationFeedback.SIGNAL_THUMBS_DOWN -> FusionScoringService.clamp(baseScore - downDelta, 0.0d, 1.0d);
            default -> FusionScoringService.clamp(baseScore, 0.0d, 1.0d);
        };
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
            Integer averageScore,
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
        double popularityNorm = normalizePopularityForAttenuation(popularity, averageScore);
        // Two-sided attenuation: boost niche entries and down-weight very popular entries.
        double centeredPopularity = FusionScoringService.clamp((popularityNorm * 2.0d) - 1.0d, -1.0d, 1.0d);
        double multiplier = 1.0d - (alpha * centeredPopularity);
        return FusionScoringService.clamp(baseScore * multiplier, 0.0d, 1.0d);
    }

    private double normalizePopularityForAttenuation(Integer popularity, Integer averageScore) {
        if (popularity == null || popularity <= 0) {
            if (averageScore != null) {
                return FusionScoringService.clamp(averageScore / 100.0d, 0.0d, 1.0d);
            }
            return 0.50d;
        }
        double capped = Math.min(2_000_000.0d, popularity.doubleValue());
        return FusionScoringService.clamp(Math.log1p(capped) / Math.log1p(2_000_000.0d), 0.0d, 1.0d);
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
        return populateCatalogEmbeddings(maxPages, perPage);
    }

    public Map<String, Object> populateFullCatalogEmbeddings(int maxPages, int perPage) {
        return populateCatalogEmbeddings(maxPages, perPage);
    }

    private Map<String, Object> populateCatalogEmbeddings(int maxPages, int perPage) {
        int safePages = Math.max(1, maxPages);
        int safePerPage = Math.max(1, Math.min(10, perPage));
        AniListSyncStateRepository.SyncState syncState = aniListSyncStateRepository.findOrCreate(
                CATALOG_POPULATE_SYNC_SOURCE,
                1,
                Integer.toString(safePages));
        int resumeStartPage = Math.max(1, syncState.nextPage());
        AnimeEmbeddingPopulatorService.PopulationStats stats;
        try {
            stats = populatorService.populateFullCatalogRange(
                    resumeStartPage,
                    safePages,
                    safePerPage);
        } catch (Exception ex) {
            aniListSyncStateRepository.markFailure(
                    CATALOG_POPULATE_SYNC_SOURCE,
                    resumeStartPage,
                    ex.getMessage(),
                    Integer.toString(safePages),
                    Instant.now());
            throw ex;
        }
        int nextPage = Math.max(1, stats.nextPageHint());
        aniListSyncStateRepository.markSuccess(
                CATALOG_POPULATE_SYNC_SOURCE,
                nextPage,
                Integer.toString(safePages),
                Instant.now());
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
        out.put("catalogSynced", stats.catalogSynced());
        out.put("failed", stats.failed());
        out.put("totalCustomEmbeddings", stats.totalCustomEmbeddings());
        out.put("scoreCoverage", stats.scoreCoverage());
        out.put("popularityCoverage", stats.popularityCoverage());
        out.put("tagCoverage", stats.tagCoverage());
        out.put("aliasCoverage", stats.aliasCoverage());
        out.put("stableStopReached", stats.stableStopReached());
        out.put("consecutiveUnchangedPages", stats.consecutiveUnchangedPages());
        boolean capReached = !stats.exhausted()
                && !stats.stableStopReached()
                && stats.pagesVisited() >= safePages;
        out.put("capReached", capReached);
        out.put("resumeSource", CATALOG_POPULATE_SYNC_SOURCE);
        double coverage = stats.discovered() <= 0
                ? 0.0d
                : (double) stats.embedded() / (double) stats.discovered();
        out.put("fullCatalogCoverage", coverage);
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

    public Map<String, Object> rebuildRelationGraphFromCatalog() {
        AnimeRelationGraphRepository.RelationGraphRebuildStats stats =
                relationGraphRepository.rebuildFromCatalogMetadata();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("edgesBefore", stats.edgesBefore());
        out.put("inserted", stats.inserted());
        out.put("edgesAfter", stats.edgesAfter());
        out.put("animeWithEdges", stats.animeWithEdges());
        return out;
    }

    public void recordFeedback(String username, RecommendationFeedbackRequest request) {
        if (request == null || request.anilistId() == null) {
            throw new BadRequestException("anilistId is required");
        }
        String normalizedSignal = normalizeFeedbackSignal(request.signal());
        User user = getUser(username);
        String queryHash = hashFeedbackQuery(request.queryContext());
        RecommendationFeedback entry = feedbackRepository.findByUserAndAnilistId(user, request.anilistId())
                .orElseGet(() -> new RecommendationFeedback(
                        user,
                        request.anilistId(),
                        normalizedSignal,
                        normalizeSourceMode(request.sourceMode()),
                        queryHash,
                        request.title(),
                        request.coverImage()));

        entry.setSignal(normalizedSignal);
        entry.setSourceMode(normalizeSourceMode(request.sourceMode()));
        entry.setQueryHash(queryHash);
        if (request.title() != null && !request.title().isBlank()) {
            entry.setTitle(request.title());
        }
        if (request.coverImage() != null && !request.coverImage().isBlank()) {
            entry.setCoverImage(request.coverImage());
        }
        entry.setUpdatedAt(LocalDateTime.now());
        feedbackRepository.save(entry);
    }

    public List<Map<String, Object>> getFeedback(String username) {
        User user = getUser(username);
        return feedbackRepository.findByUserOrderByUpdatedAtDesc(user)
                .stream()
                .map(entry -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", entry.getId());
                    item.put("anilistId", entry.getAnilistId());
                    item.put("signal", entry.getSignal());
                    item.put("sourceMode", entry.getSourceMode());
                    item.put("title", entry.getTitle());
                    item.put("coverImage", entry.getCoverImage());
                    item.put("updatedAt", entry.getUpdatedAt());
                    item.put("createdAt", entry.getCreatedAt());
                    return item;
                })
                .toList();
    }

    public void removeFeedback(String username, Long id) {
        User user = getUser(username);
        RecommendationFeedback entry = feedbackRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Feedback entry not found"));
        if (!entry.getUser().getId().equals(user.getId())) {
            throw new UnauthorizedException("Not your feedback entry");
        }
        feedbackRepository.delete(entry);
    }

    private User getUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    private String normalizeFeedbackSignal(String signal) {
        if (signal == null || signal.isBlank()) {
            throw new BadRequestException("signal is required");
        }
        String normalized = signal.trim().toUpperCase().replace('-', '_');
        return switch (normalized) {
            case "THUMBS_UP" -> RecommendationFeedback.SIGNAL_THUMBS_UP;
            case "THUMBS_DOWN" -> RecommendationFeedback.SIGNAL_THUMBS_DOWN;
            default -> throw new BadRequestException("signal must be thumbs_up or thumbs_down");
        };
    }

    private String normalizeSourceMode(String sourceMode) {
        if (sourceMode == null || sourceMode.isBlank()) {
            return null;
        }
        String normalized = sourceMode.trim().toLowerCase();
        if (!Set.of("semantic", "similar", "cf").contains(normalized)) {
            return null;
        }
        return normalized;
    }

    private String hashFeedbackQuery(String queryContext) {
        if (queryContext == null || queryContext.isBlank()) {
            return null;
        }
        return computeMetadataFingerprint(queryContext);
    }

    private List<Integer> buildExcludeIds(String username, List<Integer> seedIds) {
        Set<Integer> excluded = new LinkedHashSet<>(seedIds);

        if (username != null) {
            List<AnimeListEntry> userList = animeListEntryService.getUserList(username);
            for (AnimeListEntry entry : userList) {
                excluded.add(entry.getAnilistId());
            }
        }

        if (excluded.isEmpty()) {
            excluded.add(-1);
        }
        return new ArrayList<>(excluded);
    }

    private float[] buildUserPreferenceVector(String username) {
        User user = getUser(username);
        List<AnimeListEntry> userList = animeListEntryService.getUserList(username);
        List<RecommendationFeedback> feedbackEntries = feedbackRepository.findByUserOrderByUpdatedAtDesc(user);
        if (feedbackEntries == null) {
            feedbackEntries = List.of();
        }

        Map<Integer, Integer> scoreById = new HashMap<>();
        Map<Integer, String> feedbackSignalById = new HashMap<>();
        Set<Integer> tasteIds = new LinkedHashSet<>();

        for (AnimeListEntry entry : userList) {
            tasteIds.add(entry.getAnilistId());
            scoreById.put(entry.getAnilistId(), entry.getScore());
        }
        for (RecommendationFeedback entry : feedbackEntries) {
            if (entry.getAnilistId() == null || entry.getSignal() == null) {
                continue;
            }
            tasteIds.add(entry.getAnilistId());
            feedbackSignalById.put(entry.getAnilistId(), entry.getSignal());
        }

        if (tasteIds.isEmpty()) {
            return null;
        }

        List<Object[]> rows = loadEmbeddings(new ArrayList<>(tasteIds), true);
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

            float ratingComponent = 0f;
            Integer score = scoreById.get(anilistId);
            if (score != null) {
                ratingComponent = normalizeUserScoreWeight(score);
            }
            float scoreWeight = (float) FusionScoringService.clamp(feedbackTasteRatingWeight, 0.0d, 1.0d);

            float feedbackComponent = 0f;
            String feedbackSignal = feedbackSignalById.get(anilistId);
            if (RecommendationFeedback.SIGNAL_THUMBS_UP.equals(feedbackSignal)) {
                feedbackComponent = (float) FusionScoringService.clamp(feedbackTasteThumbsUpWeight, 0.0d, 1.0d);
            } else if (RecommendationFeedback.SIGNAL_THUMBS_DOWN.equals(feedbackSignal)) {
                feedbackComponent = -(float) FusionScoringService.clamp(feedbackTasteThumbsDownWeight, 0.0d, 1.0d);
            }
            float weight = (float) FusionScoringService.clamp((scoreWeight * ratingComponent) + feedbackComponent, -1.0d, 1.0d);

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

    private float normalizeUserScoreWeight(Integer score) {
        if (score == null) {
            return 0f;
        }
        double boundedScore = FusionScoringService.clamp(score.doubleValue(), 1.0d, 10.0d);
        double normalized = (boundedScore - 5.5d) / 4.5d;
        return (float) FusionScoringService.clamp(normalized, -1.0d, 1.0d);
    }

    private Map<Integer, String> loadFeedbackSignalMap(String username) {
        if (username == null || username.isBlank()) {
            return Map.of();
        }
        User user = getUser(username);
        List<RecommendationFeedback> entries = feedbackRepository.findByUserOrderByUpdatedAtDesc(user);
        if (entries == null || entries.isEmpty()) {
            return Map.of();
        }
        Map<Integer, String> signals = new HashMap<>();
        for (RecommendationFeedback entry : entries) {
            if (entry == null || entry.getAnilistId() == null || entry.getSignal() == null || entry.getSignal().isBlank()) {
                continue;
            }
            // Repository ordering is newest-first; preserve first signal per anime id.
            signals.putIfAbsent(entry.getAnilistId(), entry.getSignal());
        }
        return signals.isEmpty() ? Map.of() : Map.copyOf(signals);
    }

    private Map<Integer, float[]> loadEmbeddingVectorMap(List<Integer> ids) {
        List<Integer> normalizedIds = normalizeIds(ids);
        if (normalizedIds.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = findEmbeddingRowsByIds(normalizedIds);
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        Map<Integer, float[]> vectors = new HashMap<>();
        for (Object[] row : rows) {
            if (row == null || row.length < 2 || !(row[0] instanceof Integer anilistId) || !(row[1] instanceof String vectorStr)) {
                continue;
            }
            if (vectorStr.isBlank()) {
                continue;
            }
            try {
                vectors.put(anilistId, EmbeddingService.fromVectorString(vectorStr));
            } catch (Exception e) {
                log.debug("Skipping invalid candidate vector for anime {}: {}", anilistId, e.getMessage());
            }
        }
        return vectors.isEmpty() ? Map.of() : vectors;
    }

    private double normalizedCosineSimilarity(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
            return Double.NaN;
        }
        double dot = 0.0d;
        double leftNorm = 0.0d;
        double rightNorm = 0.0d;
        for (int i = 0; i < left.length; i++) {
            double l = left[i];
            double r = right[i];
            dot += l * r;
            leftNorm += l * l;
            rightNorm += r * r;
        }
        if (leftNorm <= 0.0d || rightNorm <= 0.0d) {
            return Double.NaN;
        }
        double cosine = dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
        return FusionScoringService.clamp((cosine + 1.0d) / 2.0d, 0.0d, 1.0d);
    }

    private double blendCfWithTasteVectorScore(double cfScore, double tasteScore, double tasteWeight) {
        double weight = FusionScoringService.clamp(tasteWeight, 0.0d, 0.35d);
        if (Double.isNaN(tasteScore) || weight <= 0.0d) {
            return FusionScoringService.clamp(cfScore, 0.0d, 1.0d);
        }
        return FusionScoringService.clamp(((1.0d - weight) * cfScore) + (weight * tasteScore), 0.0d, 1.0d);
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
            AniListResponse.AnimeInfo anime = aniListService.getAnimeByIdLocalOnly(anilistId);
            if (anime == null) {
                log.warn("Could not load local metadata for anime {} while embedding on-the-fly", anilistId);
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
            String metadataJson = serializeMetadataJson(anime);

            embeddingRepository.upsertCustomEmbedding(
                    anime.getId(), titleRomaji, titleEnglish, coverImage,
                    genres, description, anime.getAverageScore(),
                    anime.getStatus(), anime.getEpisodes(),
                    anime.getPopularity(),
                    anime.getFormat(),
                    anime.getSeason(),
                    anime.getSeasonYear(),
                    anime.getIsAdult(),
                    metadataJson,
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
        if (row.length > 10) {
            anime.setFormat((String) row[10]);
        }
        if (row.length > 11) {
            anime.setSeason((String) row[11]);
        }
        if (row.length > 12 && row[12] instanceof Number seasonYear) {
            anime.setSeasonYear(seasonYear.intValue());
        }
        if (row.length > 13 && row[13] instanceof Boolean isAdult) {
            anime.setIsAdult(isAdult);
        }
        if (row.length > 14 && row[14] instanceof String metadataJson && !metadataJson.isBlank()) {
            mergeMetadataJson(anime, metadataJson);
        }
        return anime;
    }

    private void mergeMetadataJson(AniListResponse.AnimeInfo anime, String metadataJson) {
        if (anime == null || metadataJson == null || metadataJson.isBlank()) {
            return;
        }
        try {
            AniListResponse.AnimeInfo parsed = explanationObjectMapper.readValue(metadataJson, AniListResponse.AnimeInfo.class);
            if (parsed == null) {
                return;
            }
            if (anime.getSynonyms() == null || anime.getSynonyms().isEmpty()) {
                anime.setSynonyms(parsed.getSynonyms());
            }
            if (anime.getTags() == null || anime.getTags().isEmpty()) {
                anime.setTags(parsed.getTags());
            }
            if (anime.getStudios() == null || anime.getStudios().isEmpty()) {
                anime.setStudios(parsed.getStudios());
            }
            if (anime.getRelations() == null || anime.getRelations().isEmpty()) {
                anime.setRelations(parsed.getRelations());
            }
            if ((anime.getExtraFields() == null || anime.getExtraFields().isEmpty())
                    && parsed.getExtraFields() != null
                    && !parsed.getExtraFields().isEmpty()) {
                anime.setExtraFields(new HashMap<>(parsed.getExtraFields()));
            }
        } catch (Exception e) {
            log.debug("Failed to parse metadata_json for anime {}: {}", anime.getId(), e.getMessage());
        }
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
            AniListResponse.AnimeInfo fetched = aniListService.getAnimeByIdLocalOnly(anime.getId());
            if (fetched == null) {
                return anime;
            }

            AniListResponse.AnimeInfo merged = mergeAnimeInfo(anime, fetched);
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
        AniListResponse.AnimeInfo catalogMetadata = aniListService.getAnimeByIdLocalOnly(anilistId);
        if (catalogMetadata != null) {
            return catalogMetadata;
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
        copy.setSynonyms(source.getSynonyms() == null ? null : List.copyOf(source.getSynonyms()));
        copy.setStudios(source.getStudios() == null ? null : List.copyOf(source.getStudios()));
        copy.setRelations(source.getRelations() == null ? null : List.copyOf(source.getRelations()));
        if (source.getExtraFields() != null && !source.getExtraFields().isEmpty()) {
            copy.setExtraFields(new HashMap<>(source.getExtraFields()));
        }
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
        String metadataJson = serializeMetadataJson(anime);

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
                anime.getFormat(),
                anime.getSeason(),
                anime.getSeasonYear(),
                anime.getIsAdult(),
                metadataJson,
                metadataFingerprint);
    }

    private String serializeMetadataJson(AniListResponse.AnimeInfo anime) {
        if (anime == null) {
            return null;
        }
        try {
            return explanationObjectMapper.writeValueAsString(anime);
        } catch (Exception e) {
            log.debug("Failed to serialize metadata_json for anime {}: {}", anime.getId(), e.getMessage());
            return null;
        }
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

        List<FusionScoringService.FusedCandidate> processedCandidates = fusedCandidates;
        List<RecommendationResponse> results = new ArrayList<>();
        int effectiveLimit = Math.min(resolveFinalizeCandidateWindow(mode, limit), processedCandidates.size());
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

    private int resolveFinalizeCandidateWindow(String mode, int limit) {
        int safeLimit = Math.max(1, limit);
        String normalizedMode = mode == null ? "semantic" : mode.trim().toLowerCase();
        if ("semantic".equals(normalizedMode)) {
            return Math.min(MAX_RECOMMENDATION_RESULTS, Math.max(safeLimit * 2, 40));
        }
        if ("similar".equals(normalizedMode)) {
            return Math.min(MAX_RECOMMENDATION_RESULTS, Math.max(safeLimit * 2, 30));
        }
        if ("cf".equals(normalizedMode)) {
            return Math.min(MAX_RECOMMENDATION_RESULTS, Math.max(safeLimit * 2, 25));
        }
        return safeLimit;
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

    private String buildSemanticFranchiseKey(AniListResponse.AnimeInfo anime) {
        return buildSemanticFranchiseKeyFromTitle(pickTitleForDedupe(anime));
    }

    private String buildSemanticFranchiseKeyFromTitle(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }
        return title.toLowerCase()
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
            List<String> seedTitles = reasoningContext == null
                    ? List.of()
                    : reasoningContext.seedTitles();
            return filterAnchorTitlesForCandidate(anime, seedTitles);
        }
        if (anime == null || contributorTitlesByAnimeId == null || contributorTitlesByAnimeId.isEmpty()) {
            return List.of();
        }
        return filterAnchorTitlesForCandidate(anime, contributorTitlesByAnimeId.getOrDefault(anime.getId(), List.of()));
    }

    private List<String> filterAnchorTitlesForCandidate(
            AniListResponse.AnimeInfo candidate,
            List<String> anchors) {
        if (anchors == null || anchors.isEmpty()) {
            return List.of();
        }
        String candidateFranchiseKey = buildSemanticFranchiseKey(candidate);
        String candidateLoose = normalizeTitleForReasoning(pickTitleForDedupe(candidate));

        LinkedHashSet<String> filtered = new LinkedHashSet<>();
        for (String anchor : anchors) {
            if (anchor == null || anchor.isBlank()) {
                continue;
            }
            String normalizedAnchor = anchor.trim();
            String anchorFranchiseKey = buildSemanticFranchiseKeyFromTitle(normalizedAnchor);
            if (!candidateFranchiseKey.isBlank()
                    && !anchorFranchiseKey.isBlank()
                    && candidateFranchiseKey.equals(anchorFranchiseKey)) {
                continue;
            }
            String anchorLoose = normalizeTitleForReasoning(normalizedAnchor);
            if (!candidateLoose.isBlank()
                    && !anchorLoose.isBlank()
                    && (candidateLoose.contains(anchorLoose) || anchorLoose.contains(candidateLoose))) {
                continue;
            }
            filtered.add(normalizedAnchor);
            if (filtered.size() >= 5) {
                break;
            }
        }
        return filtered.isEmpty() ? List.of() : List.copyOf(filtered);
    }

    private String normalizeTitleForReasoning(String rawTitle) {
        if (rawTitle == null || rawTitle.isBlank()) {
            return "";
        }
        return rawTitle.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
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
        return AnimeFilterPolicy.parseGenreSet(genreList);
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
        List<String> queryKeywords = context == null ? List.of() : context.queryKeywords();
        List<String> tasteGenres = context == null ? List.of() : context.topTasteGenres();
        List<String> matchedThemes = findMatchedQueryThemes(anime, queryKeywords, 3);
        List<String> overlapGenres = findGenreOverlap(anime, tasteGenres, 3);
        List<String> animeGenres = topAnimeGenres(anime, 3);
        String fallback = buildDeterministicReason(
                mode,
                anime,
                List.copyOf(codes),
                contributorTitles,
                matchedThemes,
                overlapGenres,
                animeGenres);
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
        List<String> overlapGenres = findGenreOverlap(anime, topTasteGenres, 3);
        List<String> animeGenres = topAnimeGenres(anime, 3);
        String fallback = buildDeterministicReason(
                "cf",
                anime,
                List.of(RecommendationResponse.CF_SIGNAL),
                contributorTitles,
                List.of(),
                overlapGenres,
                animeGenres);
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
                    Do not start with "If you enjoyed".
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
                || lowered.contains("if you enjoyed")
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

    private String buildDeterministicReason(
            String mode,
            AniListResponse.AnimeInfo anime,
            List<String> reasonCodes,
            List<String> contributorTitles,
            List<String> matchedThemes,
            List<String> overlapGenres,
            List<String> animeGenres) {
        String normalizedMode = mode == null ? "semantic" : mode.trim().toLowerCase();
        List<String> safeReasonCodes = reasonCodes == null ? List.of() : reasonCodes;
        List<String> safeContributorTitles = contributorTitles == null ? List.of() : contributorTitles;
        List<String> safeMatchedThemes = matchedThemes == null ? List.of() : matchedThemes;
        List<String> safeOverlapGenres = overlapGenres == null ? List.of() : overlapGenres;
        List<String> safeAnimeGenres = animeGenres == null ? List.of() : animeGenres;

        boolean queryDriven = safeReasonCodes.contains(RecommendationResponse.MATCHES_QUERY);
        boolean tasteDriven = safeReasonCodes.contains(RecommendationResponse.MATCHES_TASTE_PROFILE);
        boolean cfDriven = "cf".equals(normalizedMode) || safeReasonCodes.contains(RecommendationResponse.CF_SIGNAL);
        boolean seedDriven = "similar".equals(normalizedMode)
                || safeReasonCodes.contains(RecommendationResponse.SIMILAR_TO_SEED);

        if (seedDriven) {
            if (!safeContributorTitles.isEmpty() && !safeMatchedThemes.isEmpty()) {
                return "Recommended because it is similar to " + formatNaturalTitleList(safeContributorTitles)
                        + " and matches your search for " + formatNaturalEvidenceList(safeMatchedThemes) + ".";
            }
            if (!safeContributorTitles.isEmpty() && !safeOverlapGenres.isEmpty()) {
                return "Recommended because it is similar to " + formatNaturalTitleList(safeContributorTitles)
                        + " and aligns with your taste in " + formatNaturalEvidenceList(safeOverlapGenres) + ".";
            }
            if (!safeContributorTitles.isEmpty()) {
                return "Recommended because it is similar to " + formatNaturalTitleList(safeContributorTitles) + ".";
            }
            if (!safeMatchedThemes.isEmpty()) {
                return "Recommended because it matches your search for "
                        + formatNaturalEvidenceList(safeMatchedThemes) + ".";
            }
            return "Recommended because it is close to the style of your selected seed shows.";
        }

        if (cfDriven) {
            if (!safeContributorTitles.isEmpty() && !safeOverlapGenres.isEmpty()) {
                return "Recommended because you liked " + formatNaturalTitleList(safeContributorTitles)
                        + " and it aligns with your taste in "
                        + formatNaturalEvidenceList(safeOverlapGenres) + ".";
            }
            if (!safeContributorTitles.isEmpty()) {
                return "Recommended because you liked " + formatNaturalTitleList(safeContributorTitles) + ".";
            }
            if (!safeOverlapGenres.isEmpty()) {
                return "Recommended because it aligns with your taste in "
                        + formatNaturalEvidenceList(safeOverlapGenres) + ".";
            }
            if (hasStrongAudienceSignal(anime)) {
                return "Recommended because users with similar taste patterns and strong audience response point to this title.";
            }
            return "Recommended because users with similar taste patterns tend to enjoy it.";
        }

        if (queryDriven && !safeMatchedThemes.isEmpty() && !safeOverlapGenres.isEmpty()) {
            return "Recommended because it matches your search for "
                    + formatNaturalEvidenceList(safeMatchedThemes)
                    + " and aligns with your taste in "
                    + formatNaturalEvidenceList(safeOverlapGenres) + ".";
        }
        if (queryDriven && !safeMatchedThemes.isEmpty() && !safeContributorTitles.isEmpty()) {
            return "Recommended because it matches your search for "
                    + formatNaturalEvidenceList(safeMatchedThemes)
                    + " and is close to " + formatNaturalTitleList(safeContributorTitles) + ".";
        }
        if (queryDriven && !safeMatchedThemes.isEmpty()) {
            return "Recommended because it matches your search for "
                    + formatNaturalEvidenceList(safeMatchedThemes) + ".";
        }
        if (queryDriven && !safeAnimeGenres.isEmpty()) {
            return "Recommended because it matches your search intent with "
                    + formatNaturalEvidenceList(safeAnimeGenres) + " themes.";
        }
        if (queryDriven) {
            return "Recommended because it partially matches your search intent.";
        }
        if (tasteDriven && !safeOverlapGenres.isEmpty()) {
            return "Recommended because it aligns with your taste in "
                    + formatNaturalEvidenceList(safeOverlapGenres) + ".";
        }
        if (!safeContributorTitles.isEmpty()) {
            return "Recommended because you liked " + formatNaturalTitleList(safeContributorTitles) + ".";
        }
        if (hasStrongAudienceSignal(anime)) {
            return "Recommended because it is a strong match with broad audience approval.";
        }
        return "Recommended because it is a strong match for your current preferences.";
    }

    private String buildEmergencyFallback(String mode, List<String> reasonCodes, List<String> contributorTitles) {
        return buildDeterministicReason(mode, null, reasonCodes, contributorTitles, List.of(), List.of(), List.of());
    }

    private boolean hasStrongAudienceSignal(AniListResponse.AnimeInfo anime) {
        if (anime == null) {
            return false;
        }
        Integer averageScore = anime.getAverageScore();
        Integer popularity = anime.getPopularity();
        return (averageScore != null && averageScore >= 80)
                || (popularity != null && popularity >= 150000);
    }

    private String formatNaturalEvidenceList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "similar themes";
        }
        List<String> cleaned = new ArrayList<>(3);
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            cleaned.add(value.trim());
            if (cleaned.size() >= 3) {
                break;
            }
        }
        if (cleaned.isEmpty()) {
            return "similar themes";
        }
        return formatNaturalTitleList(cleaned);
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
            String controlsFingerprint,
            String explanationFingerprint) {
        return new SemanticCacheKey(
                mode == null ? "semantic" : mode,
                normalizedQuery == null ? "" : normalizedQuery,
                limit,
                topK,
                authenticated,
                userProfileFingerprint == null ? "na" : userProfileFingerprint,
                modelFingerprint == null ? "default" : modelFingerprint,
                embeddingsFingerprint == null ? "unknown" : embeddingsFingerprint,
                controlsFingerprint == null ? "default" : controlsFingerprint,
                explanationFingerprint == null ? "default" : explanationFingerprint);
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
            String sourceHash = customEmbeddingImportStateRepository.findCurrent()
                    .map(CustomEmbeddingImportStateRepository.ImportState::sourceSha256)
                    .filter(value -> value != null && !value.isBlank())
                    .orElse("unknown");
            long customCount = useCustomVectors
                    ? embeddingRepository.countCustomEmbeddings()
                    : embeddingRepository.count();
            return sourceHash + "|customCount=" + customCount;
        } catch (Exception ex) {
            log.debug("Could not resolve embedding fingerprint for semantic cache key: {}", ex.getMessage());
            return "unknown";
        }
    }

    private String resolveExplanationFingerprint() {
        return String.join("|",
                "deterministic-v2",
                "llmEnabled=" + llmExplanationsEnabled,
                "provider=" + (explanationProvider == null ? "deterministic" : explanationProvider),
                "openAiModel=" + (openAiExplanationModel == null ? "" : openAiExplanationModel),
                "ollamaModel=" + (ollamaExplanationModel == null ? "" : ollamaExplanationModel),
                "maxRewrites=" + llmMaxRewritesPerRequest,
                "cfContributors=" + cfContributorExplanationsEnabled);
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
        if (limit == null || limit <= 0 || limit > MAX_RECOMMENDATION_RESULTS) {
            return 15;
        }
        return limit;
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize <= 0 || pageSize > MAX_RECOMMENDATION_RESULTS) {
            return 15;
        }
        return pageSize;
    }

    private String buildPagingContextFingerprint(
            String username,
            List<Integer> seedIds,
            String query,
            boolean useListOnly,
            Float requestedListWeight,
            String mode,
            SemanticRequest.Filters filters,
            int pageSize) {
        String normalizedMode = mode == null ? "semantic" : mode.trim().toLowerCase();
        String normalizedQuery = query == null ? "" : preprocessSemanticQuery(query);
        List<Integer> normalizedSeeds = normalizeIds(seedIds);
        String filtersFingerprint = resolveRecommendationControls(filters).fingerprint();
        String payload = String.join("|",
                username == null ? "anon" : username,
                normalizedMode,
                normalizedQuery,
                normalizedSeeds.toString(),
                Boolean.toString(useListOnly),
                requestedListWeight == null ? "default" : requestedListWeight.toString(),
                filtersFingerprint,
                Integer.toString(pageSize));
        return computeMetadataFingerprint(payload);
    }

    private int decodePagingCursorOffset(String cursor, String expectedFingerprint) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            String value = new String(decoded, StandardCharsets.UTF_8);
            String[] parts = value.split("\\|", 2);
            if (parts.length != 2) {
                return 0;
            }
            if (expectedFingerprint == null || expectedFingerprint.isBlank() || !expectedFingerprint.equals(parts[0])) {
                return 0;
            }
            int parsed = Integer.parseInt(parts[1]);
            return Math.max(0, parsed);
        } catch (Exception ex) {
            log.debug("Invalid recommendation cursor; resetting pagination: {}", ex.getMessage());
            return 0;
        }
    }

    private String encodePagingCursor(int nextOffset, String fingerprint) {
        String raw = (fingerprint == null ? "" : fingerprint) + "|" + Math.max(0, nextOffset);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
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
            String controlsFingerprint,
            String explanationFingerprint) {
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
                    includeExtraSeasons,
                    true,
                    includeOnasOvasSpecials,
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

    private static final class RelationResolutionState {
        private final Map<Integer, AniListResponse.AnimeInfo> animeById;
        private final Map<Integer, Integer> entrypointByAnimeId;
        private int remainingHydrations;
        private int failedHydrations;
        private boolean hydrationCircuitOpen;

        private RelationResolutionState(
                Map<Integer, AniListResponse.AnimeInfo> animeById,
                Map<Integer, Integer> entrypointByAnimeId,
                int remainingHydrations) {
            this.animeById = animeById;
            this.entrypointByAnimeId = entrypointByAnimeId;
            this.remainingHydrations = Math.max(0, remainingHydrations);
            this.failedHydrations = 0;
            this.hydrationCircuitOpen = false;
        }

        private Map<Integer, AniListResponse.AnimeInfo> animeById() {
            return animeById;
        }

        private Map<Integer, Integer> entrypointByAnimeId() {
            return entrypointByAnimeId;
        }

        private int remainingHydrations() {
            return remainingHydrations;
        }

        private void consumeHydration() {
            if (remainingHydrations > 0) {
                remainingHydrations--;
            }
        }

        private boolean shouldBypassHydration(int failureThreshold) {
            return hydrationCircuitOpen || (failureThreshold > 0 && failedHydrations >= failureThreshold);
        }

        private void recordHydrationSuccess() {
            failedHydrations = 0;
            hydrationCircuitOpen = false;
        }

        private void recordHydrationFailure(int failureThreshold) {
            failedHydrations++;
            if (failureThreshold > 0 && failedHydrations >= failureThreshold) {
                hydrationCircuitOpen = true;
            }
        }
    }
}
