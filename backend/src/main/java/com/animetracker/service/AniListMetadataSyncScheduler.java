package com.animetracker.service;

import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.animetracker.repository.AnimeCatalogRepository;
import com.animetracker.repository.AnimeRelationGraphRepository;
import com.animetracker.repository.AniListSyncStateRepository;
import com.animetracker.repository.AniListSyncStateRepository.SyncState;

/**
 * Tiered metadata sync scheduler for AniList catalog refresh.
 * Track A: sparse/unreleased metadata backfill by ID.
 * Track B: incremental full-catalog page scan with wrap-at-end cursor behavior.
 * Uses persisted cursors and adaptive page budgets to stay within upstream rate limits.
 */
@Component
public class AniListMetadataSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(AniListMetadataSyncScheduler.class);
    private static final String SOURCE_INCREMENTAL_FULL_SCAN = "catalog_full_scan_incremental";
    private static final String SOURCE_LOW_METADATA_BACKFILL = "catalog_low_metadata_backfill";
    private static final String SOURCE_WEEKLY_GRAPH_REBUILD = "weekly_relation_graph_rebuild";

    private final AnimeEmbeddingPopulatorService populatorService;
    private final AniListService aniListService;
    private final AnimeCatalogRepository catalogRepository;
    private final AnimeRelationGraphRepository relationGraphRepository;
    private final AniListSyncStateRepository syncStateRepository;
    private final ReentrantLock metadataLaneLock = new ReentrantLock();
    private final ReentrantLock weeklyGraphLock = new ReentrantLock();

    @Value("${recommendations.metadata-sync.enabled:false}")
    private boolean metadataSyncEnabled;

    @Value("${recommendations.metadata-sync.weekly-full-catalog-enabled:true}")
    private boolean weeklyFullCatalogEnabled;
    @Value("${recommendations.metadata-sync.weekly-full-catalog-pages:120}")
    private int weeklyFullCatalogPages;
    @Value("${recommendations.metadata-sync.full-catalog-per-page:10}")
    private int fullCatalogPerPage;
    @Value("${recommendations.metadata-sync.full-catalog-wrap-on-exhausted:true}")
    private boolean fullCatalogWrapOnExhausted;
    @Value("${recommendations.metadata-sync.low-metadata-backfill-enabled:true}")
    private boolean lowMetadataBackfillEnabled;
    @Value("${recommendations.metadata-sync.low-metadata-backfill-max-ids:90}")
    private int lowMetadataBackfillMaxIds;
    @Value("${recommendations.metadata-sync.weekly-graph-rebuild-enabled:true}")
    private boolean weeklyGraphRebuildEnabled;

    @Value("${recommendations.metadata-sync.adaptive-budget-enabled:true}")
    private boolean adaptiveBudgetEnabled;

    @Value("${recommendations.metadata-sync.adaptive-rate-limit-threshold:3}")
    private int adaptiveRateLimitThreshold;

    @Value("${recommendations.metadata-sync.adaptive-retry-threshold:5}")
    private int adaptiveRetryThreshold;

    public AniListMetadataSyncScheduler(
            AnimeEmbeddingPopulatorService populatorService,
            AniListService aniListService,
            AnimeCatalogRepository catalogRepository,
            AnimeRelationGraphRepository relationGraphRepository,
            AniListSyncStateRepository syncStateRepository) {
        this.populatorService = populatorService;
        this.aniListService = aniListService;
        this.catalogRepository = catalogRepository;
        this.relationGraphRepository = relationGraphRepository;
        this.syncStateRepository = syncStateRepository;
    }

    @Scheduled(
            fixedDelayString = "${recommendations.metadata-sync.low-metadata-backfill-fixed-delay-ms:86400000}",
            initialDelayString = "${recommendations.metadata-sync.initial-delay-ms:120000}")
    public void runLowMetadataBackfillSync() {
        if (!metadataSyncEnabled || !lowMetadataBackfillEnabled) {
            return;
        }
        if (!metadataLaneLock.tryLock()) {
            log.debug("Skipping low metadata backfill sync: another metadata sync lane is active");
            return;
        }
        try {
            runLowMetadataBackfillWindow();
        } finally {
            metadataLaneLock.unlock();
        }
    }

    @Scheduled(
            fixedDelayString = "${recommendations.metadata-sync.weekly-full-catalog-fixed-delay-ms:604800000}",
            initialDelayString = "${recommendations.metadata-sync.initial-delay-ms:120000}")
    public void runWeeklyFullCatalogSync() {
        if (!metadataSyncEnabled || !weeklyFullCatalogEnabled) {
            return;
        }
        if (!metadataLaneLock.tryLock()) {
            log.debug("Skipping weekly full catalog sync: another metadata sync lane is active");
            return;
        }
        try {
            runWindowedSync(
                    SOURCE_INCREMENTAL_FULL_SCAN,
                    1,
                    weeklyFullCatalogPages,
                    fullCatalogWrapOnExhausted);
        } finally {
            metadataLaneLock.unlock();
        }
    }

    @Scheduled(
            fixedDelayString = "${recommendations.metadata-sync.weekly-graph-rebuild-fixed-delay-ms:604800000}",
            initialDelayString = "${recommendations.metadata-sync.initial-delay-ms:120000}")
    public void runWeeklyRelationGraphRebuild() {
        if (!metadataSyncEnabled || !weeklyGraphRebuildEnabled) {
            return;
        }
        if (!weeklyGraphLock.tryLock()) {
            log.debug("Skipping weekly relation graph rebuild: previous run still active");
            return;
        }
        try {
            AnimeRelationGraphRepository.RelationGraphRebuildStats stats =
                    relationGraphRepository.rebuildFromCatalogMetadata();
            syncStateRepository.markSuccess(
                    SOURCE_WEEKLY_GRAPH_REBUILD,
                    1,
                    Long.toString(Math.max(0L, stats.edgesAfter())),
                    Instant.now());
            log.info(
                    "AniList sync '{}' complete: edges_before={} inserted={} edges_after={} anime_with_edges={}",
                    SOURCE_WEEKLY_GRAPH_REBUILD,
                    stats.edgesBefore(),
                    stats.inserted(),
                    stats.edgesAfter(),
                    stats.animeWithEdges());
        } catch (Exception ex) {
            syncStateRepository.markFailure(
                    SOURCE_WEEKLY_GRAPH_REBUILD,
                    1,
                    ex.getMessage(),
                    null,
                    Instant.now());
            log.warn(
                    "AniList sync '{}' failed: error={}",
                    SOURCE_WEEKLY_GRAPH_REBUILD,
                    ex.getMessage());
        } finally {
            weeklyGraphLock.unlock();
        }
    }

    private void runLowMetadataBackfillWindow() {
        int safeMaxIds = Math.max(1, lowMetadataBackfillMaxIds);
        SyncState state = syncStateRepository.findOrCreate(
                SOURCE_LOW_METADATA_BACKFILL,
                1,
                Integer.toString(safeMaxIds));
        int maxIds = resolveBudgetPages(state.budgetState(), safeMaxIds);
        Instant startedAt = Instant.now();
        var candidateIds = catalogRepository.findLowMetadataAnilistIds(maxIds);
        if (candidateIds == null || candidateIds.isEmpty()) {
            syncStateRepository.markSuccess(
                    SOURCE_LOW_METADATA_BACKFILL,
                    1,
                    Integer.toString(maxIds),
                    Instant.now());
            log.info(
                    "AniList sync '{}' complete: no sparse catalog rows discovered (max_ids={} started_at={})",
                    SOURCE_LOW_METADATA_BACKFILL,
                    maxIds,
                    startedAt);
            return;
        }

        aniListService.resetRateLimitWindow();
        try {
            AnimeEmbeddingPopulatorService.IdBackfillStats stats =
                    populatorService.refreshCatalogIds(candidateIds, SOURCE_LOW_METADATA_BACKFILL);
            AniListService.RateLimitWindow rateWindow = aniListService.consumeRateLimitWindow();
            syncStateRepository.markSuccess(
                    SOURCE_LOW_METADATA_BACKFILL,
                    1,
                    Integer.toString(maxIds),
                    Instant.now());
            log.info(
                    "AniList sync '{}' complete: requested_ids={} discovered={} embedded={} metadata_refreshed={} failed={} requests={} status429={} retryable_failures={} started_at={}",
                    SOURCE_LOW_METADATA_BACKFILL,
                    stats.requestedIds(),
                    stats.discovered(),
                    stats.embedded(),
                    stats.metadataRefreshed(),
                    stats.failed(),
                    rateWindow.requests(),
                    rateWindow.status429Responses(),
                    rateWindow.retryableFailures(),
                    startedAt);
        } catch (Exception ex) {
            AniListService.RateLimitWindow rateWindow = aniListService.consumeRateLimitWindow();
            syncStateRepository.markFailure(
                    SOURCE_LOW_METADATA_BACKFILL,
                    1,
                    ex.getMessage(),
                    Integer.toString(maxIds),
                    Instant.now());
            log.warn(
                    "AniList sync '{}' failed: requested_ids={} requests={} status429={} retryable_failures={} error={}",
                    SOURCE_LOW_METADATA_BACKFILL,
                    maxIds,
                    rateWindow.requests(),
                    rateWindow.status429Responses(),
                    rateWindow.retryableFailures(),
                    ex.getMessage());
        }
    }

    private void runWindowedSync(
            String sourceKey,
            int defaultStartPage,
            int configuredMaxPages,
            boolean wrapOnExhausted) {
        int safeConfiguredPages = Math.max(1, configuredMaxPages);
        int safePerPage = Math.max(1, Math.min(10, fullCatalogPerPage));
        SyncState state = syncStateRepository.findOrCreate(
                sourceKey,
                Math.max(1, defaultStartPage),
                Integer.toString(safeConfiguredPages));
        int currentBudgetPages = resolveBudgetPages(state.budgetState(), safeConfiguredPages);
        int startPage = Math.max(1, state.nextPage());
        int windowPages = Math.max(1, Math.min(safeConfiguredPages, currentBudgetPages));
        Instant startedAt = Instant.now();
        aniListService.resetRateLimitWindow();

        try {
            AnimeEmbeddingPopulatorService.PopulationStats stats =
                    populatorService.populateFullCatalogRange(startPage, windowPages, safePerPage);
            AniListService.RateLimitWindow rateWindow = aniListService.consumeRateLimitWindow();
            int nextBudgetPages = computeNextBudget(windowPages, safeConfiguredPages, rateWindow);
            int nextPage = resolveNextPage(stats, wrapOnExhausted);
            syncStateRepository.markSuccess(
                    sourceKey,
                    nextPage,
                    Integer.toString(nextBudgetPages),
                    Instant.now());
            log.info(
                    "AniList sync '{}' complete: start_page={} next_page={} pages={} discovered={} embedded={} metadata_refreshed={} failed={} exhausted={} requests={} status429={} retryable_failures={} budget_pages={} next_budget_pages={} started_at={}",
                    sourceKey,
                    startPage,
                    nextPage,
                    stats.pagesVisited(),
                    stats.discovered(),
                    stats.embedded(),
                    stats.metadataRefreshed(),
                    stats.failed(),
                    stats.exhausted(),
                    rateWindow.requests(),
                    rateWindow.status429Responses(),
                    rateWindow.retryableFailures(),
                    windowPages,
                    nextBudgetPages,
                    startedAt);
        } catch (Exception ex) {
            AniListService.RateLimitWindow rateWindow = aniListService.consumeRateLimitWindow();
            int nextBudgetPages = computeNextBudget(windowPages, safeConfiguredPages, rateWindow);
            syncStateRepository.markFailure(
                    sourceKey,
                    startPage,
                    ex.getMessage(),
                    Integer.toString(nextBudgetPages),
                    Instant.now());
            log.warn(
                    "AniList sync '{}' failed: start_page={} requests={} status429={} retryable_failures={} budget_pages={} next_budget_pages={} error={}",
                    sourceKey,
                    startPage,
                    rateWindow.requests(),
                    rateWindow.status429Responses(),
                    rateWindow.retryableFailures(),
                    windowPages,
                    nextBudgetPages,
                    ex.getMessage());
        }
    }

    private int resolveNextPage(
            AnimeEmbeddingPopulatorService.PopulationStats stats,
            boolean wrapOnExhausted) {
        if (stats == null) {
            return 1;
        }
        if (wrapOnExhausted && stats.exhausted()) {
            return 1;
        }
        return Math.max(1, stats.nextPageHint());
    }

    private int resolveBudgetPages(String budgetState, int fallbackPages) {
        if (budgetState == null || budgetState.isBlank()) {
            return Math.max(1, fallbackPages);
        }
        try {
            return Math.max(1, Integer.parseInt(budgetState.trim()));
        } catch (NumberFormatException ignored) {
            return Math.max(1, fallbackPages);
        }
    }

    private int computeNextBudget(
            int currentBudgetPages,
            int configuredPages,
            AniListService.RateLimitWindow rateWindow) {
        int safeCurrent = Math.max(1, currentBudgetPages);
        int safeConfigured = Math.max(1, configuredPages);
        if (!adaptiveBudgetEnabled || rateWindow == null) {
            return Math.min(safeCurrent, safeConfigured);
        }
        boolean rateLimited = rateWindow.status429Responses() >= Math.max(1, adaptiveRateLimitThreshold)
                || rateWindow.retryableFailures() >= Math.max(1, adaptiveRetryThreshold);
        if (rateLimited) {
            return Math.max(1, safeCurrent / 2);
        }
        if (safeCurrent < safeConfigured) {
            return Math.min(safeConfigured, safeCurrent + 1);
        }
        return safeConfigured;
    }

}
