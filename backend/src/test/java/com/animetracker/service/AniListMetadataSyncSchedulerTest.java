package com.animetracker.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.animetracker.repository.AniListSyncStateRepository;
import com.animetracker.repository.AnimeRelationGraphRepository;

@ExtendWith(MockitoExtension.class)
class AniListMetadataSyncSchedulerTest {

    @Mock
    private AnimeEmbeddingPopulatorService populatorService;
    @Mock
    private AniListService aniListService;
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
                relationGraphRepository,
                syncStateRepository);
        setField("metadataSyncEnabled", true);
        setField("weeklyFullCatalogEnabled", true);
        setField("weeklyFullCatalogPages", 120);
        setField("fullCatalogPerPage", 10);
        setField("weeklyGraphRebuildEnabled", true);
    }

    @Test
    void runWeeklyFullCatalogSync_usesCursorAndCallsFullCatalogPopulation() {
        AniListSyncStateRepository.SyncState state = new AniListSyncStateRepository.SyncState(
                "catalog_populate",
                2201,
                null,
                null,
                null,
                "6");
        when(syncStateRepository.findOrCreate(eq("catalog_populate"), eq(1), eq("120")))
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
                eq("catalog_populate"),
                eq(2207),
                eq("6"),
                any(Instant.class));
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
