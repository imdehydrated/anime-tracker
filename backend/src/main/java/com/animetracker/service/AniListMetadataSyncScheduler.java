package com.animetracker.service;

import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.animetracker.repository.AniListSyncStateRepository;
import com.animetracker.repository.AniListSyncStateRepository.SyncState;

/**
 * Tiered metadata sync scheduler for AniList catalog refresh.
 * Uses persisted cursors and adaptive page budgets to stay within upstream rate limits.
 */
@Component
public class AniListMetadataSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(AniListMetadataSyncScheduler.class);
    private static final String SOURCE_HOT_POPULAR = "hot_popular";
    private static final String SOURCE_DAILY_ACTIVE = "daily_active_catalog";
    private static final String SOURCE_WEEKLY_DEEP = "weekly_deep_catalog";

    private final AnimeEmbeddingPopulatorService populatorService;
    private final AniListService aniListService;
    private final AniListSyncStateRepository syncStateRepository;
    private final ReentrantLock hotPopularLock = new ReentrantLock();
    private final ReentrantLock dailyActiveLock = new ReentrantLock();
    private final ReentrantLock weeklyDeepLock = new ReentrantLock();

    @Value("${recommendations.metadata-sync.enabled:false}")
    private boolean metadataSyncEnabled;

    @Value("${recommendations.metadata-sync.per-page:50}")
    private int syncPerPage;

    @Value("${recommendations.metadata-sync.hot-popular-pages:20}")
    private int hotPopularPages;

    @Value("${recommendations.metadata-sync.daily-active-pages:20}")
    private int dailyActivePages;

    @Value("${recommendations.metadata-sync.weekly-deep-pages:120}")
    private int weeklyDeepPages;

    @Value("${recommendations.metadata-sync.adaptive-budget-enabled:true}")
    private boolean adaptiveBudgetEnabled;

    @Value("${recommendations.metadata-sync.adaptive-rate-limit-threshold:3}")
    private int adaptiveRateLimitThreshold;

    @Value("${recommendations.metadata-sync.adaptive-retry-threshold:5}")
    private int adaptiveRetryThreshold;

    public AniListMetadataSyncScheduler(
            AnimeEmbeddingPopulatorService populatorService,
            AniListService aniListService,
            AniListSyncStateRepository syncStateRepository) {
        this.populatorService = populatorService;
        this.aniListService = aniListService;
        this.syncStateRepository = syncStateRepository;
    }

    @Scheduled(
            fixedDelayString = "${recommendations.metadata-sync.hot-popular-fixed-delay-ms:21600000}",
            initialDelayString = "${recommendations.metadata-sync.initial-delay-ms:120000}")
    public void runHotPopularSync() {
        if (!metadataSyncEnabled) {
            return;
        }
        if (!hotPopularLock.tryLock()) {
            log.debug("Skipping hot popular sync: previous run still active");
            return;
        }
        try {
            runWindowedSync(
                    SOURCE_HOT_POPULAR,
                    1,
                    hotPopularPages,
                    true);
        } finally {
            hotPopularLock.unlock();
        }
    }

    @Scheduled(
            fixedDelayString = "${recommendations.metadata-sync.daily-active-fixed-delay-ms:86400000}",
            initialDelayString = "${recommendations.metadata-sync.initial-delay-ms:120000}")
    public void runDailyActiveCatalogSync() {
        if (!metadataSyncEnabled) {
            return;
        }
        if (!dailyActiveLock.tryLock()) {
            log.debug("Skipping daily active catalog sync: previous run still active");
            return;
        }
        try {
            runWindowedSync(
                    SOURCE_DAILY_ACTIVE,
                    1,
                    dailyActivePages,
                    false);
        } finally {
            dailyActiveLock.unlock();
        }
    }

    @Scheduled(
            fixedDelayString = "${recommendations.metadata-sync.weekly-deep-fixed-delay-ms:604800000}",
            initialDelayString = "${recommendations.metadata-sync.initial-delay-ms:120000}")
    public void runWeeklyDeepCatalogSync() {
        if (!metadataSyncEnabled) {
            return;
        }
        if (!weeklyDeepLock.tryLock()) {
            log.debug("Skipping weekly deep catalog sync: previous run still active");
            return;
        }
        try {
            runWindowedSync(
                    SOURCE_WEEKLY_DEEP,
                    1,
                    weeklyDeepPages,
                    false);
        } finally {
            weeklyDeepLock.unlock();
        }
    }

    private void runWindowedSync(
            String sourceKey,
            int defaultStartPage,
            int configuredMaxPages,
            boolean forceStartAtPageOne) {
        int safeConfiguredPages = Math.max(1, configuredMaxPages);
        int safePerPage = Math.max(1, Math.min(50, syncPerPage));
        SyncState state = syncStateRepository.findOrCreate(
                sourceKey,
                Math.max(1, defaultStartPage),
                Integer.toString(safeConfiguredPages));
        int currentBudgetPages = resolveBudgetPages(state.budgetState(), safeConfiguredPages);
        int startPage = forceStartAtPageOne ? 1 : Math.max(1, state.nextPage());
        int windowPages = Math.max(1, Math.min(safeConfiguredPages, currentBudgetPages));
        Instant startedAt = Instant.now();
        aniListService.resetRateLimitWindow();

        try {
            AnimeEmbeddingPopulatorService.PopulationStats stats = forceStartAtPageOne
                    ? populatorService.populatePopularRange(startPage, windowPages, safePerPage)
                    : populatorService.populateActiveCatalogRange(startPage, windowPages, safePerPage);
            AniListService.RateLimitWindow rateWindow = aniListService.consumeRateLimitWindow();
            int nextBudgetPages = computeNextBudget(windowPages, safeConfiguredPages, rateWindow);
            int nextPage = forceStartAtPageOne ? 1 : Math.max(1, stats.nextPageHint());
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
