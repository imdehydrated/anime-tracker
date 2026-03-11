package com.animetracker.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.animetracker.repository.AniListSyncStateRepository;
import com.animetracker.repository.AnimeCatalogRepository;
import com.animetracker.repository.AnimeRelationGraphRepository;

@ExtendWith(MockitoExtension.class)
class AniListMetadataSyncSchedulerTest {

    @Mock
    private AnimeEmbeddingPopulatorService populatorService;
    @Mock
    private AniListService aniListService;
    @Mock
    private AnimeCatalogRepository catalogRepository;
    @Mock
    private AnimeRelationGraphRepository relationGraphRepository;
    @Mock
    private AniListSyncStateRepository syncStateRepository;

    private AniListMetadataSyncScheduler scheduler;

    @BeforeEach
    void setUp() throws Exception {
        scheduler = new AniListMetadataSyncScheduler(
                populatorService,
                aniListService,
                catalogRepository,
                relationGraphRepository,
                syncStateRepository);
        setField("metadataSyncEnabled", true);
        setField("weeklyFullCatalogEnabled", true);
        setField("weeklyFullCatalogPages", 120);
        setField("fullCatalogPerPage", 10);
        setField("fullCatalogWrapOnExhausted", true);
        setField("lowMetadataBackfillEnabled", true);
        setField("lowMetadataBackfillMaxIds", 90);
        setField("lowMetadataBackfillRefreshCooldownHours", 72);
        setField("lowMetadataBackfillUnreleasedRefreshCooldownHours", 336);
        setField("weeklyGraphRebuildEnabled", true);
        setField("clusterLockEnabled", false);
    }

    @Test
    void runWeeklyFullCatalogSync_usesCursorAndCallsFullCatalogPopulation() {
        AniListSyncStateRepository.SyncState state = new AniListSyncStateRepository.SyncState(
                "catalog_full_scan_incremental",
                2201,
                null,
                null,
                null,
                "6");
        when(syncStateRepository.findOrCreate(eq("catalog_full_scan_incremental"), eq(1), eq("120")))
                .thenReturn(state);
        when(populatorService.populateFullCatalogRange(2201, 6, 10))
                .thenReturn(new AnimeEmbeddingPopulatorService.PopulationStats(
                        "full_catalog",
                        2201,
                        2207,
                        false,
                        6,
                        60,
                        20,
                        40,
                        60,
                        60,
                        0,
                        22020L,
                        1.0d,
                        1.0d,
                        1.0d,
                        1.0d,
                        false,
                        0));
        when(aniListService.consumeRateLimitWindow())
                .thenReturn(new AniListService.RateLimitWindow(6, 0, 0));

        scheduler.runWeeklyFullCatalogSync();

        verify(aniListService).resetRateLimitWindow();
        verify(populatorService).populateFullCatalogRange(2201, 6, 10);
        verify(syncStateRepository).markSuccess(
                eq("catalog_full_scan_incremental"),
                eq(2207),
                eq("6"),
                any(Instant.class));
    }

    @Test
    void runWeeklyFullCatalogSync_wrapsCursorWhenExhausted() {
        AniListSyncStateRepository.SyncState state = new AniListSyncStateRepository.SyncState(
                "catalog_full_scan_incremental",
                3201,
                null,
                null,
                null,
                "8");
        when(syncStateRepository.findOrCreate(eq("catalog_full_scan_incremental"), eq(1), eq("120")))
                .thenReturn(state);
        when(populatorService.populateFullCatalogRange(3201, 8, 10))
                .thenReturn(new AnimeEmbeddingPopulatorService.PopulationStats(
                        "full_catalog",
                        3201,
                        3201,
                        true,
                        8,
                        80,
                        20,
                        60,
                        80,
                        80,
                        0,
                        30000L,
                        1.0d,
                        1.0d,
                        1.0d,
                        1.0d,
                        false,
                        0));
        when(aniListService.consumeRateLimitWindow())
                .thenReturn(new AniListService.RateLimitWindow(8, 0, 0));

        scheduler.runWeeklyFullCatalogSync();

        verify(syncStateRepository).markSuccess(
                eq("catalog_full_scan_incremental"),
                eq(1),
                eq("8"),
                any(Instant.class));
    }

    @Test
    void runLowMetadataBackfillSync_refreshesNinetySparseIdsPerRun() {
        AniListSyncStateRepository.SyncState state = new AniListSyncStateRepository.SyncState(
                "catalog_low_metadata_backfill",
                1,
                null,
                null,
                null,
                "90");
        when(syncStateRepository.findOrCreate(eq("catalog_low_metadata_backfill"), eq(1), eq("90")))
                .thenReturn(state);
        when(catalogRepository.findLowMetadataAnilistIds(90, 72, 336)).thenReturn(List.of(11, 22, 33));
        when(populatorService.refreshCatalogIds(eq(List.of(11, 22, 33)), eq("catalog_low_metadata_backfill")))
                .thenReturn(new AnimeEmbeddingPopulatorService.IdBackfillStats(
                        "catalog_low_metadata_backfill",
                        3,
                        3,
                        2,
                        1,
                        3,
                        3,
                        0,
                        100L,
                        1.0d,
                        1.0d,
                        1.0d,
                        1.0d));
        when(aniListService.consumeRateLimitWindow())
                .thenReturn(new AniListService.RateLimitWindow(3, 0, 0));

        scheduler.runLowMetadataBackfillSync();

        verify(catalogRepository).findLowMetadataAnilistIds(90, 72, 336);
        verify(populatorService).refreshCatalogIds(eq(List.of(11, 22, 33)), eq("catalog_low_metadata_backfill"));
        verify(syncStateRepository).markSuccess(
                eq("catalog_low_metadata_backfill"),
                eq(1),
                eq("90"),
                any(Instant.class));
    }

    @Test
    void runLowMetadataBackfillSync_skipsWhenClusterLeaseNotAcquired() throws Exception {
        setField("clusterLockEnabled", true);
        when(syncStateRepository.tryAcquireLease(anyString(), anyString(), any())).thenReturn(false);

        scheduler.runLowMetadataBackfillSync();

        verify(catalogRepository, never()).findLowMetadataAnilistIds(anyInt(), anyInt(), anyInt());
        verify(populatorService, never()).refreshCatalogIds(anyList(), anyString());
        verify(syncStateRepository, never()).releaseLease(anyString(), anyString());
    }

    @Test
    void runWeeklyRelationGraphRebuild_rebuildsAndPersistsSuccessState() {
        when(relationGraphRepository.rebuildFromCatalogMetadata())
                .thenReturn(new AnimeRelationGraphRepository.RelationGraphRebuildStats(
                        100L,
                        90,
                        90L,
                        55L));

        scheduler.runWeeklyRelationGraphRebuild();

        verify(relationGraphRepository).rebuildFromCatalogMetadata();
        verify(syncStateRepository).markSuccess(
                eq("weekly_relation_graph_rebuild"),
                eq(1),
                eq("90"),
                any(Instant.class));
    }

    @Test
    void runWeeklyRelationGraphRebuild_skipsWhenMetadataSyncDisabled() throws Exception {
        setField("metadataSyncEnabled", false);

        scheduler.runWeeklyRelationGraphRebuild();

        verify(relationGraphRepository, never()).rebuildFromCatalogMetadata();
        verify(syncStateRepository, never()).markSuccess(anyString(), anyInt(), anyString(), any(Instant.class));
    }

    private void setField(String fieldName, Object value) throws Exception {
        Field field = AniListMetadataSyncScheduler.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(scheduler, value);
    }
}
