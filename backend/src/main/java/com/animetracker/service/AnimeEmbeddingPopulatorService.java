package com.animetracker.service;

import com.animetracker.dto.AniListResponse;
import com.animetracker.repository.AnimeCatalogRepository;
import com.animetracker.repository.AnimeEmbeddingRepository;
import com.animetracker.repository.AnimeRelationGraphRepository;
import com.animetracker.repository.EmbeddingPopulationFailureRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

@Service
public class AnimeEmbeddingPopulatorService {

    private static final Logger log = LoggerFactory.getLogger(AnimeEmbeddingPopulatorService.class);
    private static final List<String> ACTIVE_FORMATS = List.of(
            "TV",
            "TV_SHORT",
            "MOVIE",
            "OVA",
            "ONA",
            "SPECIAL");
    // Full-catalog query uses a broad metadata fragment; AniList becomes unstable with larger page sizes.
    private static final int FULL_CATALOG_SAFE_PER_PAGE_MAX = 10;
    @Value("${recommendations.metadata-sync.full-catalog-unchanged-stop-pages:40}")
    private int fullCatalogUnchangedStopPages;
    @Value("${recommendations.metadata-sync.failure-policy.embed-max-attempts:3}")
    private int embedRetryAttempts;

    private final AniListService aniListService;
    private final MlSidecarService mlSidecarService;
    private final AnimeCatalogRepository catalogRepository;
    private final AnimeEmbeddingRepository embeddingRepository;
    private final AnimeRelationGraphRepository relationGraphRepository;
    private final EmbeddingPopulationFailureRepository failureRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnimeEmbeddingPopulatorService(
            AniListService aniListService,
            MlSidecarService mlSidecarService,
            AnimeCatalogRepository catalogRepository,
            AnimeEmbeddingRepository embeddingRepository,
            AnimeRelationGraphRepository relationGraphRepository,
            EmbeddingPopulationFailureRepository failureRepository) {
        this.aniListService = aniListService;
        this.mlSidecarService = mlSidecarService;
        this.catalogRepository = catalogRepository;
        this.embeddingRepository = embeddingRepository;
        this.relationGraphRepository = relationGraphRepository;
        this.failureRepository = failureRepository;
    }

    /**
     * Populate from popular anime pages. Existing behavior kept for compatibility.
     */
    @Transactional
    public int populate(int totalPages) {
        PopulationStats stats = populatePages(
                1,
                totalPages,
                50,
                (page, perPage) -> aniListService.fetchPopularAnimePage(page, perPage),
                "popular");
        return stats.embedded();
    }

    /**
     * Populate from active catalog pages (broader than popularity sorted subset).
     */
    @Transactional
    public PopulationStats populateActiveCatalog(int maxPages, int perPage) {
        int effectivePerPage = Math.max(1, Math.min(50, perPage));
        return populatePages(
                1,
                maxPages,
                effectivePerPage,
                (page, pageSize) -> aniListService.fetchActiveCatalogPage(page, pageSize, ACTIVE_FORMATS),
                "active_catalog");
    }

    /**
     * Populate from complete AniList catalog pages.
     * Intended for initial full scrape and periodic full repopulation windows.
     */
    @Transactional
    public PopulationStats populateFullCatalog(int maxPages, int perPage) {
        int effectivePerPage = Math.max(1, Math.min(FULL_CATALOG_SAFE_PER_PAGE_MAX, perPage));
        return populatePages(
                1,
                maxPages,
                effectivePerPage,
                (page, pageSize) -> aniListService.fetchFullCatalogPage(page, pageSize),
                "full_catalog");
    }

    /**
     * Populate a bounded full-catalog range starting at startPage.
     * Useful for resumable full-catalog bootstrap and deep refresh windows.
     */
    @Transactional
    public PopulationStats populateFullCatalogRange(int startPage, int maxPages, int perPage) {
        int effectiveStartPage = Math.max(1, startPage);
        int effectivePages = Math.max(1, maxPages);
        int effectivePerPage = Math.max(1, Math.min(FULL_CATALOG_SAFE_PER_PAGE_MAX, perPage));
        return populatePages(
                effectiveStartPage,
                effectivePages,
                effectivePerPage,
                (page, pageSize) -> aniListService.fetchFullCatalogPage(page, pageSize),
                "full_catalog");
    }

    /**
     * Populate a bounded popular range starting at startPage.
     * Useful for scheduled hot-window refresh with adaptive budget.
     */
    @Transactional
    public PopulationStats populatePopularRange(int startPage, int maxPages, int perPage) {
        int effectiveStartPage = Math.max(1, startPage);
        int effectivePages = Math.max(1, maxPages);
        int effectivePerPage = Math.max(1, Math.min(50, perPage));
        return populatePages(
                effectiveStartPage,
                effectivePages,
                effectivePerPage,
                (page, pageSize) -> aniListService.fetchPopularAnimePage(page, pageSize),
                "popular");
    }

    /**
     * Populate a bounded active-catalog range starting at startPage.
     * Useful for cursor-based scheduled catalog rotation.
     */
    @Transactional
    public PopulationStats populateActiveCatalogRange(int startPage, int maxPages, int perPage) {
        int effectiveStartPage = Math.max(1, startPage);
        int effectivePages = Math.max(1, maxPages);
        int effectivePerPage = Math.max(1, Math.min(50, perPage));
        return populatePages(
                effectiveStartPage,
                effectivePages,
                effectivePerPage,
                (page, pageSize) -> aniListService.fetchActiveCatalogPage(page, pageSize, ACTIVE_FORMATS),
                "active_catalog");
    }

    /**
     * Refresh a bounded set of catalog IDs directly from AniList.
     * Intended for sparse/unreleased metadata backfill lanes.
     */
    @Transactional
    public IdBackfillStats refreshCatalogIds(List<Integer> anilistIds, String source) {
        if (!mlSidecarService.isEnabled()) {
            throw new IllegalStateException("ML sidecar must be enabled for embedding population");
        }

        String safeSource = (source == null || source.isBlank()) ? "catalog_id_refresh" : source.trim();
        List<Integer> ids = anilistIds == null
                ? List.of()
                : anilistIds.stream()
                        .filter(Objects::nonNull)
                        .filter(id -> id > 0)
                        .distinct()
                        .toList();

        int requestedIds = ids.size();
        int discovered = 0;
        int embedded = 0;
        int skipped = 0;
        int failed = 0;
        int metadataRefreshed = 0;
        int catalogSynced = 0;
        int scoreCoverageCount = 0;
        int popularityCoverageCount = 0;
        int tagCoverageCount = 0;
        int aliasCoverageCount = 0;

        for (Integer anilistId : ids) {
            AniListResponse.AnimeInfo anime;
            try {
                anime = aniListService.getAnimeByIdFromApi(anilistId);
            } catch (Exception ex) {
                failureRepository.recordFailure(
                        anilistId,
                        safeSource,
                        failureReasonFromException(ex),
                        ex.getMessage());
                failed++;
                continue;
            }

            if (anime == null || anime.getId() == null || anime.getId() <= 0) {
                failureRepository.recordFailure(
                        anilistId,
                        safeSource,
                        EmbeddingFailureReason.MISSING_METADATA,
                        "AniList returned no metadata");
                failed++;
                continue;
            }

            discovered++;
            try {
                String metadataJson = serializeMetadata(anime);
                String catalogFingerprint = computeFingerprint(metadataJson);
                upsertCatalogMetadata(anime, metadataJson, catalogFingerprint);
                catalogSynced++;

                if (anime.getAverageScore() != null) {
                    scoreCoverageCount++;
                }
                if (anime.getPopularity() != null) {
                    popularityCoverageCount++;
                }
                if (anime.getTags() != null && !anime.getTags().isEmpty()) {
                    tagCoverageCount++;
                }
                if (anime.getSynonyms() != null && !anime.getSynonyms().isEmpty()) {
                    aliasCoverageCount++;
                }

                String embeddingText = buildEmbeddingText(anime);
                String metadataFingerprint = computeFingerprint(embeddingText);
                if (embeddingRepository.existsByAnilistId(anime.getId())) {
                    Boolean hasCustomEmbedding = embeddingRepository.hasCustomEmbedding(anime.getId());
                    String existingFingerprint = embeddingRepository.findMetadataFingerprintByAnilistId(anime.getId());
                    if (Boolean.TRUE.equals(hasCustomEmbedding) && Objects.equals(existingFingerprint, metadataFingerprint)) {
                        refreshMetadata(anime, metadataJson, metadataFingerprint);
                        failureRepository.markResolved(anime.getId(), safeSource);
                        metadataRefreshed++;
                        skipped++;
                        continue;
                    }
                }
                if (upsertEmbeddedAnimeWithRetry(anime, embeddingText, metadataJson, metadataFingerprint, safeSource)) {
                    failureRepository.markResolved(anime.getId(), safeSource);
                    embedded++;
                    metadataRefreshed++;
                } else {
                    failureRepository.recordFailure(
                            anime.getId(),
                            safeSource,
                            EmbeddingFailureReason.EMBED_FAILURE,
                            "sidecar_embedding_empty_or_invalid");
                    failed++;
                }
            } catch (Exception ex) {
                failureRepository.recordFailure(
                        anime.getId(),
                        safeSource,
                        failureReasonFromException(ex),
                        ex.getMessage());
                failed++;
            }
        }

        long totalCustomEmbeddings = embeddingRepository.countCustomEmbeddings();
        double scoreCoverage = coverage(scoreCoverageCount, discovered);
        double popularityCoverage = coverage(popularityCoverageCount, discovered);
        double tagCoverage = coverage(tagCoverageCount, discovered);
        double aliasCoverage = coverage(aliasCoverageCount, discovered);

        log.info(
                "Catalog ID refresh complete ({}) requested_ids={} discovered={} embedded={} skipped={} metadata_refreshed={} catalog_synced={} failed={} total_custom={} score_coverage={} popularity_coverage={} tag_coverage={} alias_coverage={}",
                safeSource,
                requestedIds,
                discovered,
                embedded,
                skipped,
                metadataRefreshed,
                catalogSynced,
                failed,
                totalCustomEmbeddings,
                scoreCoverage,
                popularityCoverage,
                tagCoverage,
                aliasCoverage);

        return new IdBackfillStats(
                safeSource,
                requestedIds,
                discovered,
                embedded,
                skipped,
                metadataRefreshed,
                catalogSynced,
                failed,
                totalCustomEmbeddings,
                scoreCoverage,
                popularityCoverage,
                tagCoverage,
                aliasCoverage);
    }

    private PopulationStats populatePages(
            int startPage,
            int totalPages,
            int perPage,
            BiFunction<Integer, Integer, List<AniListResponse.AnimeInfo>> fetchPage,
            String source) {
        if (!mlSidecarService.isEnabled()) {
            throw new IllegalStateException("ML sidecar must be enabled for embedding population");
        }

        int embedded = 0;
        int skipped = 0;
        int failed = 0;
        int metadataRefreshed = 0;
        int catalogSynced = 0;
        int discovered = 0;
        int pagesVisited = 0;
        int scoreCoverageCount = 0;
        int popularityCoverageCount = 0;
        int tagCoverageCount = 0;
        int aliasCoverageCount = 0;

        int effectiveStartPage = Math.max(1, startPage);
        int effectiveTotalPages = Math.max(1, totalPages);
        int lastAttemptedPage = effectiveStartPage - 1;
        boolean exhausted = false;
        boolean lastPageAdvanceEligible = false;
        int consecutiveUnchangedPages = 0;
        boolean stableStopReached = false;
        int safeUnchangedStopPages = Math.max(1, fullCatalogUnchangedStopPages);
        boolean fullCatalogSource = "full_catalog".equals(source);

        for (int page = effectiveStartPage; page < effectiveStartPage + effectiveTotalPages; page++) {
            lastAttemptedPage = page;
            pagesVisited++;
            lastPageAdvanceEligible = false;
            log.info(
                    "Fetching AniList {} page {} (window {}/{})",
                    source,
                    page,
                    pagesVisited,
                    effectiveTotalPages);

            List<AniListResponse.AnimeInfo> animeList;
            try {
                animeList = fetchPage.apply(page, perPage);
            } catch (AniListService.AniListRequestException e) {
                log.error(
                        "Failed to fetch AniList {} page {}: reason={} message={}",
                        source,
                        page,
                        e.reason(),
                        e.getMessage());
                break;
            } catch (Exception e) {
                log.error("Failed to fetch AniList {} page {}: {}", source, page, e.getMessage());
                break;
            }

            if (animeList == null || animeList.isEmpty()) {
                log.info("No more anime returned from AniList {} at page {}, stopping", source, page);
                exhausted = true;
                break;
            }

            boolean pageFullyPopulated = true;
            discovered += animeList.size();
            int unchangedKnownInPage = 0;
            for (AniListResponse.AnimeInfo anime : animeList) {
                if (anime == null || anime.getId() == null || anime.getId() <= 0) {
                    failureRepository.recordFailure(
                            anime == null ? null : anime.getId(),
                            source,
                            EmbeddingFailureReason.VALIDATION,
                            "missing_or_invalid_anilist_id");
                    failed++;
                    pageFullyPopulated = false;
                    continue;
                }
                try {
                    String metadataJson = serializeMetadata(anime);
                    String catalogFingerprint = computeFingerprint(metadataJson);
                    upsertCatalogMetadata(anime, metadataJson, catalogFingerprint);
                    catalogSynced++;

                    if (anime.getAverageScore() != null) {
                        scoreCoverageCount++;
                    }
                    if (anime.getPopularity() != null) {
                        popularityCoverageCount++;
                    }
                    if (anime.getTags() != null && !anime.getTags().isEmpty()) {
                        tagCoverageCount++;
                    }
                    if (anime.getSynonyms() != null && !anime.getSynonyms().isEmpty()) {
                        aliasCoverageCount++;
                    }

                    String embeddingText = buildEmbeddingText(anime);
                    String metadataFingerprint = computeFingerprint(embeddingText);
                    if (embeddingRepository.existsByAnilistId(anime.getId())) {
                        Boolean hasCustomEmbedding = embeddingRepository.hasCustomEmbedding(anime.getId());
                        String existingFingerprint = embeddingRepository.findMetadataFingerprintByAnilistId(anime.getId());
                        if (Boolean.TRUE.equals(hasCustomEmbedding) && Objects.equals(existingFingerprint, metadataFingerprint)) {
                            refreshMetadata(anime, metadataJson, metadataFingerprint);
                            failureRepository.markResolved(anime.getId(), source);
                            metadataRefreshed++;
                            skipped++;
                            unchangedKnownInPage++;
                            continue;
                        }
                        // Metadata exists and custom vector is missing or stale fingerprint changed.
                    }
                    if (upsertEmbeddedAnimeWithRetry(anime, embeddingText, metadataJson, metadataFingerprint, source)) {
                        failureRepository.markResolved(anime.getId(), source);
                        embedded++;
                        metadataRefreshed++;
                        if (embedded % 50 == 0) {
                            log.info(
                                "Population progress ({}): embedded={}, skipped={}, failed={}",
                                source,
                                embedded,
                                skipped,
                                failed);
                        }
                    } else {
                        failureRepository.recordFailure(
                                anime.getId(),
                                source,
                                EmbeddingFailureReason.EMBED_FAILURE,
                                "sidecar_embedding_empty_or_invalid");
                        failed++;
                        pageFullyPopulated = false;
                    }
                } catch (Exception e) {
                    failureRepository.recordFailure(
                            anime.getId(),
                            source,
                            failureReasonFromException(e),
                            e.getMessage());
                    failed++;
                    pageFullyPopulated = false;
                    log.error(
                            "Failed to embed anime {} ({}) from {}: {}",
                            anime.getId(),
                            anime.getTitle() != null ? anime.getTitle().getRomaji() : "unknown",
                            source,
                            e.getMessage());
                }
            }

            if (unchangedKnownInPage >= animeList.size()) {
                consecutiveUnchangedPages++;
            } else {
                consecutiveUnchangedPages = 0;
            }
            lastPageAdvanceEligible = pageFullyPopulated && animeList.size() >= perPage;

            if (!pageFullyPopulated) {
                log.warn(
                        "Stopping AniList {} at page {} because some entries failed embedding after retries; cursor remains pinned for retry",
                        source,
                        page);
                break;
            }

            if (fullCatalogSource && consecutiveUnchangedPages >= safeUnchangedStopPages) {
                stableStopReached = true;
                log.info(
                        "Stopping AniList full_catalog early after {} consecutive unchanged pages (threshold={}): last_page={} next_page_hint={}",
                        consecutiveUnchangedPages,
                        safeUnchangedStopPages,
                        lastAttemptedPage,
                        lastAttemptedPage + 1);
                break;
            }
        }

        long totalCustomEmbeddings = embeddingRepository.countCustomEmbeddings();
        double scoreCoverage = coverage(scoreCoverageCount, discovered);
        double popularityCoverage = coverage(popularityCoverageCount, discovered);
        double tagCoverage = coverage(tagCoverageCount, discovered);
        double aliasCoverage = coverage(aliasCoverageCount, discovered);
        log.info(
                "Population complete ({}) start_page={} last_page={} pages={} discovered={} embedded={} skipped={} metadata_refreshed={} catalog_synced={} failed={} total_custom={} score_coverage={} popularity_coverage={} tag_coverage={} alias_coverage={} exhausted={}",
                source,
                effectiveStartPage,
                lastAttemptedPage,
                pagesVisited,
                discovered,
                embedded,
                skipped,
                metadataRefreshed,
                catalogSynced,
                failed,
                totalCustomEmbeddings,
                scoreCoverage,
                popularityCoverage,
                tagCoverage,
                aliasCoverage,
                exhausted);
        if (!exhausted && pagesVisited >= effectiveTotalPages) {
            log.warn(
                    "Population window cap reached ({}) before upstream exhaustion: start_page={} last_page={} pages_visited={} window_pages={}",
                    source,
                    effectiveStartPage,
                    lastAttemptedPage,
                    pagesVisited,
                    effectiveTotalPages);
        }
        // Advance cursor only when the last attempted page was fully populated and full-sized.
        // Any page with unresolved embedding failures keeps the cursor pinned for retry.
        boolean advanceCursor = lastPageAdvanceEligible;
        int nextPageHint = Math.max(1, advanceCursor ? lastAttemptedPage + 1 : lastAttemptedPage);
        return new PopulationStats(
                source,
                effectiveStartPage,
                nextPageHint,
                exhausted,
                pagesVisited,
                discovered,
                embedded,
                skipped,
                metadataRefreshed,
                catalogSynced,
                failed,
                totalCustomEmbeddings,
                scoreCoverage,
                popularityCoverage,
                tagCoverage,
                aliasCoverage,
                stableStopReached,
                consecutiveUnchangedPages);
    }

    private boolean upsertEmbeddedAnimeWithRetry(
            AniListResponse.AnimeInfo anime,
            String embeddingText,
            String metadataJson,
            String metadataFingerprint,
            String source) {
        int safeAttempts = Math.max(1, embedRetryAttempts);
        Exception lastException = null;
        for (int attempt = 1; attempt <= safeAttempts; attempt++) {
            try {
                if (upsertEmbeddedAnime(anime, embeddingText, metadataJson, metadataFingerprint)) {
                    if (attempt > 1) {
                        log.info(
                                "Recovered embedding for anime {} from {} after retry attempt {}/{}",
                                anime.getId(),
                                source,
                                attempt,
                                safeAttempts);
                    }
                    return true;
                }
                if (attempt < safeAttempts) {
                    log.warn(
                            "Empty embedding for anime {} from {} on attempt {}/{}; retrying",
                            anime.getId(),
                            source,
                            attempt,
                            safeAttempts);
                }
            } catch (Exception ex) {
                lastException = ex;
                if (attempt < safeAttempts) {
                    log.warn(
                            "Embedding call failed for anime {} from {} on attempt {}/{}: {}. Retrying",
                            anime.getId(),
                            source,
                            attempt,
                            safeAttempts,
                            ex.getMessage());
                    continue;
                }
                throw ex;
            }
        }
        if (lastException != null) {
            throw new IllegalStateException(lastException.getMessage(), lastException);
        }
        return false;
    }

    private boolean upsertEmbeddedAnime(
            AniListResponse.AnimeInfo anime,
            String embeddingText,
            String metadataJson,
            String metadataFingerprint) {
        float[] vector = mlSidecarService.embedText(embeddingText);
        if (vector == null || vector.length == 0) {
            log.warn("Skipping anime {} because sidecar embedding failed", anime.getId());
            return false;
        }

        String vectorStr = EmbeddingService.toVectorString(vector);
        String titleRomaji = anime.getTitle() != null ? anime.getTitle().getRomaji() : null;
        String titleEnglish = anime.getTitle() != null ? anime.getTitle().getEnglish() : null;
        String coverImage = anime.getCoverImage() != null ? anime.getCoverImage().getLarge() : null;
        String genres = anime.getGenres() != null ? String.join(", ", anime.getGenres()) : null;
        String description = stripHtml(anime.getDescription());
        embeddingRepository.upsertCustomEmbedding(
                anime.getId(),
                titleRomaji,
                titleEnglish,
                coverImage,
                genres,
                description,
                anime.getAverageScore(),
                anime.getStatus(),
                anime.getEpisodes(),
                anime.getPopularity(),
                anime.getFormat(),
                anime.getSeason(),
                anime.getSeasonYear(),
                anime.getIsAdult(),
                metadataJson,
                embeddingText,
                metadataFingerprint,
                vectorStr);
        upsertRelationGraph(anime);
        return true;
    }

    private void refreshMetadata(AniListResponse.AnimeInfo anime, String metadataJson, String metadataFingerprint) {
        if (anime == null || anime.getId() == null) {
            return;
        }
        String titleRomaji = anime.getTitle() != null ? anime.getTitle().getRomaji() : null;
        String titleEnglish = anime.getTitle() != null ? anime.getTitle().getEnglish() : null;
        String coverImage = anime.getCoverImage() != null ? anime.getCoverImage().getLarge() : null;
        String genres = anime.getGenres() != null ? String.join(", ", anime.getGenres()) : null;
        String description = stripHtml(anime.getDescription());
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
        upsertRelationGraph(anime);
    }

    private double coverage(int coveredCount, int total) {
        if (total <= 0) {
            return 0.0d;
        }
        return (double) coveredCount / (double) total;
    }

    public Map<String, Object> getFailureReport(String source, String status, int limit) {
        int safeLimit = Math.max(1, Math.min(500, limit));
        EmbeddingPopulationFailureRepository.FailureSummary summary = failureRepository.summarize(source);
        List<EmbeddingPopulationFailureRepository.PopulationFailure> failures = failureRepository.findFailures(
                source,
                status,
                safeLimit);
        Map<EmbeddingFailureReason, Long> reasonSummary = failureRepository.summarizeByReason(source, status);
        List<Map<String, Object>> rows = failures.stream()
                .map(this::toFailureRow)
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", source);
        out.put("status", status);
        out.put("limit", safeLimit);
        out.put("summary", Map.of(
                "total", summary.total(),
                "open", summary.openCount(),
                "deadLetter", summary.deadLetterCount(),
                "resolved", summary.resolvedCount()));
        Map<String, Long> reasonCounts = new LinkedHashMap<>();
        for (EmbeddingFailureReason reason : EmbeddingFailureReason.values()) {
            reasonCounts.put(reason.name(), reasonSummary.getOrDefault(reason, 0L));
        }
        out.put("reasonSummary", reasonCounts);
        out.put("items", rows);
        return out;
    }

    @Transactional
    public Map<String, Object> retryFailures(String source, int limit) {
        int safeLimit = Math.max(1, Math.min(200, limit));
        List<EmbeddingPopulationFailureRepository.PopulationFailure> retryable = failureRepository.findRetryableFailures(
                source,
                safeLimit);
        int attempted = 0;
        int recovered = 0;
        int failed = 0;
        for (EmbeddingPopulationFailureRepository.PopulationFailure failure : retryable) {
            attempted++;
            Integer anilistId = failure.anilistId();
            try {
                AniListResponse.AnimeInfo anime = aniListService.getAnimeByIdFromApi(anilistId);
                if (anime == null || anime.getId() == null || anime.getId() <= 0) {
                    failureRepository.recordFailure(
                            anilistId,
                            failure.source(),
                            EmbeddingFailureReason.MISSING_METADATA,
                            "AniList returned no metadata");
                    failed++;
                    continue;
                }
                String metadataJson = serializeMetadata(anime);
                String catalogFingerprint = computeFingerprint(metadataJson);
                upsertCatalogMetadata(anime, metadataJson, catalogFingerprint);
                String embeddingText = buildEmbeddingText(anime);
                String metadataFingerprint = computeFingerprint(embeddingText);
                if (upsertEmbeddedAnime(anime, embeddingText, metadataJson, metadataFingerprint)) {
                    refreshMetadata(anime, metadataJson, metadataFingerprint);
                    failureRepository.markResolved(anilistId, failure.source());
                    recovered++;
                } else {
                    failureRepository.recordFailure(
                            anilistId,
                            failure.source(),
                            EmbeddingFailureReason.EMBED_FAILURE,
                            "sidecar_embedding_empty_or_invalid");
                    failed++;
                }
            } catch (Exception ex) {
                failureRepository.recordFailure(
                        anilistId,
                        failure.source(),
                        failureReasonFromException(ex),
                        ex.getMessage());
                failed++;
            }
        }
        EmbeddingPopulationFailureRepository.FailureSummary summary = failureRepository.summarize(source);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", source);
        out.put("retryLimit", safeLimit);
        out.put("attempted", attempted);
        out.put("recovered", recovered);
        out.put("failed", failed);
        out.put("summary", Map.of(
                "total", summary.total(),
                "open", summary.openCount(),
                "deadLetter", summary.deadLetterCount(),
                "resolved", summary.resolvedCount()));
        return out;
    }

    /**
     * Build embedding text that captures title + topical metadata.
     */
    String buildEmbeddingText(AniListResponse.AnimeInfo anime) {
        StringBuilder sb = new StringBuilder();

        if (anime.getTitle() != null) {
            String title = anime.getTitle().getEnglish() != null
                    ? anime.getTitle().getEnglish()
                    : anime.getTitle().getRomaji();
            if (title != null && !title.isBlank()) {
                sb.append("Title: ").append(title).append("\n");
            }
            if (anime.getTitle().getNativeTitle() != null && !anime.getTitle().getNativeTitle().isBlank()) {
                sb.append("Title Native: ").append(anime.getTitle().getNativeTitle()).append("\n");
            }
        }

        if (anime.getSynonyms() != null && !anime.getSynonyms().isEmpty()) {
            List<String> cleaned = anime.getSynonyms().stream()
                    .filter(x -> x != null && !x.isBlank())
                    .limit(6)
                    .toList();
            if (!cleaned.isEmpty()) {
                sb.append("Synonyms: ").append(String.join(", ", cleaned)).append("\n");
            }
        }

        if (anime.getFormat() != null && !anime.getFormat().isBlank()) {
            sb.append("Format: ").append(anime.getFormat()).append("\n");
        }
        if (anime.getSeason() != null && !anime.getSeason().isBlank()) {
            sb.append("Season: ").append(anime.getSeason());
            if (anime.getSeasonYear() != null) {
                sb.append(" ").append(anime.getSeasonYear());
            }
            sb.append("\n");
        }

        if (anime.getGenres() != null && !anime.getGenres().isEmpty()) {
            sb.append("Genres: ").append(String.join(", ", anime.getGenres())).append("\n");
        }

        if (anime.getTags() != null && !anime.getTags().isEmpty()) {
            String tagStr = anime.getTags().stream()
                    .filter(t -> t.getRank() != null && t.getRank() >= 60)
                    .sorted((a, b) -> b.getRank() - a.getRank())
                    .map(t -> t.getName() + " (" + t.getRank() + "%)")
                    .collect(Collectors.joining(", "));
            if (!tagStr.isEmpty()) {
                sb.append("Tags: ").append(tagStr).append("\n");
            }
        }

        if (anime.getStudios() != null && !anime.getStudios().isEmpty()) {
            List<String> studios = new ArrayList<>();
            for (AniListResponse.AnimeStudio studio : anime.getStudios()) {
                if (studio == null || studio.getName() == null || studio.getName().isBlank()) {
                    continue;
                }
                studios.add(studio.getName());
            }
            if (!studios.isEmpty()) {
                sb.append("Studios: ").append(String.join(", ", studios)).append("\n");
            }
        }

        if (anime.getRelations() != null && !anime.getRelations().isEmpty()) {
            List<String> relationTitles = new ArrayList<>();
            for (AniListResponse.AnimeRelation relation : anime.getRelations()) {
                if (relation == null || relation.getTitle() == null) {
                    continue;
                }
                String title = relation.getTitle().getEnglish() != null
                        ? relation.getTitle().getEnglish()
                        : relation.getTitle().getRomaji();
                if (title != null && !title.isBlank()) {
                    relationTitles.add(title);
                }
                if (relationTitles.size() >= 5) {
                    break;
                }
            }
            if (!relationTitles.isEmpty()) {
                sb.append("Related: ").append(String.join(", ", relationTitles)).append("\n");
            }
        }

        if (anime.getDescription() != null && !anime.getDescription().isBlank()) {
            String cleanDesc = stripHtml(anime.getDescription());
            if (cleanDesc != null && cleanDesc.length() > 500) {
                cleanDesc = cleanDesc.substring(0, 500) + "...";
            }
            if (cleanDesc != null && !cleanDesc.isBlank()) {
                sb.append("Description: ").append(cleanDesc);
            }
        }

        return sb.toString().trim();
    }

    private static String stripHtml(String html) {
        if (html == null) {
            return null;
        }
        return html.replaceAll("<[^>]*>", "").trim();
    }

    private String computeFingerprint(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest algorithm unavailable", e);
        }
    }

    private String serializeMetadata(AniListResponse.AnimeInfo anime) {
        if (anime == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(anime);
        } catch (JsonProcessingException e) {
            log.debug("Failed to serialize metadata JSON for anime {}: {}", anime.getId(), e.getMessage());
            return null;
        }
    }

    private void upsertCatalogMetadata(
            AniListResponse.AnimeInfo anime,
            String metadataJson,
            String metadataFingerprint) {
        if (anime == null || anime.getId() == null || anime.getId() <= 0) {
            return;
        }
        String titleRomaji = anime.getTitle() != null ? anime.getTitle().getRomaji() : null;
        String titleEnglish = anime.getTitle() != null ? anime.getTitle().getEnglish() : null;
        String titleNative = anime.getTitle() != null ? anime.getTitle().getNativeTitle() : null;
        String coverImage = anime.getCoverImage() != null ? anime.getCoverImage().getLarge() : null;
        String genres = anime.getGenres() != null ? String.join(", ", anime.getGenres()) : null;
        String description = stripHtml(anime.getDescription());
        catalogRepository.upsertCatalogEntry(
                anime.getId(),
                anime.getIdMal(),
                titleRomaji,
                titleEnglish,
                titleNative,
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

    private void upsertRelationGraph(AniListResponse.AnimeInfo anime) {
        if (anime == null || anime.getId() == null || anime.getId() <= 0) {
            return;
        }
        List<AnimeRelationGraphRepository.RelationEdge> edges = new ArrayList<>();
        if (anime.getRelations() != null) {
            for (AniListResponse.AnimeRelation relation : anime.getRelations()) {
                if (relation == null || relation.getId() == null || relation.getId() <= 0) {
                    continue;
                }
                String relationType = normalizeRelationType(relation.getRelationType());
                if (relationType == null) {
                    continue;
                }
                edges.add(new AnimeRelationGraphRepository.RelationEdge(
                        anime.getId(),
                        relation.getId(),
                        relationType,
                        null));
            }
        }
        relationGraphRepository.replaceRelations(anime.getId(), edges);
    }

    private String normalizeRelationType(String relationType) {
        if (relationType == null || relationType.isBlank()) {
            return null;
        }
        return relationType.trim()
                .toUpperCase()
                .replace(' ', '_')
                .replace('-', '_');
    }

    private Map<String, Object> toFailureRow(EmbeddingPopulationFailureRepository.PopulationFailure failure) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", failure.id());
        row.put("anilistId", failure.anilistId());
        row.put("source", failure.source());
        row.put("failureReason", failure.failureReason());
        row.put("lastError", failure.lastError());
        row.put("attempts", failure.attempts());
        row.put("status", failure.status());
        row.put("lastAttemptAt", failure.lastAttemptAt());
        row.put("nextRetryAt", failure.nextRetryAt());
        row.put("createdAt", failure.createdAt());
        row.put("updatedAt", failure.updatedAt());
        return row;
    }

    private EmbeddingFailureReason failureReasonFromException(Exception exception) {
        if (exception == null) {
            return EmbeddingFailureReason.UNKNOWN;
        }
        if (exception instanceof AniListService.AniListRequestException aniListRequestException) {
            return aniListRequestException.reason();
        }
        return EmbeddingFailureReason.UNKNOWN;
    }

    public record PopulationStats(
            String source,
            int startPage,
            int nextPageHint,
            boolean exhausted,
            int pagesVisited,
            int discovered,
            int embedded,
            int skipped,
            int metadataRefreshed,
            int catalogSynced,
            int failed,
            long totalCustomEmbeddings,
            double scoreCoverage,
            double popularityCoverage,
            double tagCoverage,
            double aliasCoverage,
            boolean stableStopReached,
            int consecutiveUnchangedPages) {
    }

    public record IdBackfillStats(
            String source,
            int requestedIds,
            int discovered,
            int embedded,
            int skipped,
            int metadataRefreshed,
            int catalogSynced,
            int failed,
            long totalCustomEmbeddings,
            double scoreCoverage,
            double popularityCoverage,
            double tagCoverage,
            double aliasCoverage) {
    }
}
