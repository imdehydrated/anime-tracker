package com.animetracker.service;

import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.animetracker.repository.AnimeRelationGraphRepository;
import com.animetracker.repository.AniListSyncStateRepository;
import com.animetracker.repository.AniListSyncStateRepository.SyncState;

/**
 * Tiered metadata sync scheduler for AniList catalog refresh.
 * Uses persisted cursors and adaptive page budgets to stay within upstream rate limits.
 */
@Component
public class AniListMetadataSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(AniListMetadataSyncScheduler.class);
    private static final String SOURCE_CATALOG_POPULATE = "catalog_populate";
    private static final String SOURCE_WEEKLY_GRAPH_REBUILD = "weekly_relation_graph_rebuild";

    private final AnimeEmbeddingPopulatorService populatorService;
    private final AniListService aniListService;
    private final AnimeRelationGraphRepository relationGraphRepository;
    private final AniListSyncStateRepository syncStateRepository;
    private final ReentrantLock weeklyFullLock = new ReentrantLock();
    private final ReentrantLock weeklyGraphLock = new ReentrantLock();

    @Value("${recommendations.metadata-sync.enabled:false}")
    private boolean metadataSyncEnabled;

    @Value("${recommendations.metadata-sync.weekly-full-catalog-enabled:true}")
    private boolean weeklyFullCatalogEnabled;
    @Value("${recommendations.metadata-sync.weekly-full-catalog-pages:120}")
    private int weeklyFullCatalogPages;
    @Value("${recommendations.metadata-sync.full-catalog-per-page:10}")
    private int fullCatalogPerPage;
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
            AnimeRelationGraphRepository relationGraphRepository,
            AniListSyncStateRepository syncStateRepository) {
        this.populatorService = populatorService;
        this.aniListService = aniListService;
        this.relationGraphRepository = relationGraphRepository;
        this.syncStateRepository = syncStateRepository;
    }

    @Scheduled(
            fixedDelayString = "${recommendations.metadata-sync.weekly-full-catalog-fixed-delay-ms:604800000}",
            initialDelayString = "${recommendations.metadata-sync.initial-delay-ms:120000}")
    public void runWeeklyFullCatalogSync() {
        if (!metadataSyncEnabled || !weeklyFullCatalogEnabled) {
            return;
        }
        if (!weeklyFullLock.tryLock()) {
            log.debug("Skipping weekly full catalog sync: previous run still active");
            return;
        }
        try {
            runWindowedSync(
                    SOURCE_CATALOG_POPULATE,
                    1,
                    weeklyFullCatalogPages);
        } finally {
            weeklyFullLock.unlock();
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

    private void runWindowedSync(
            String sourceKey,
            int defaultStartPage,
            int configuredMaxPages) {
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
            int nextPage = Math.max(1, stats.nextPageHint());
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
