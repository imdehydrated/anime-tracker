package com.animetracker.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.animetracker.dto.AniListResponse;
import com.animetracker.repository.AnimeCatalogRepository;
import com.animetracker.repository.AnimeEmbeddingRepository;
import com.animetracker.repository.AnimeRelationGraphRepository;
import com.animetracker.repository.EmbeddingPopulationFailureRepository;

@ExtendWith(MockitoExtension.class)
class AnimeEmbeddingPopulatorServiceTest {

    @Mock
    private AniListService aniListService;
    @Mock
    private MlSidecarService mlSidecarService;
    @Mock
    private AnimeCatalogRepository catalogRepository;
    @Mock
    private AnimeEmbeddingRepository embeddingRepository;
    @Mock
    private AnimeRelationGraphRepository relationGraphRepository;
    @Mock
    private EmbeddingPopulationFailureRepository failureRepository;

    private AnimeEmbeddingPopulatorService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new AnimeEmbeddingPopulatorService(
                aniListService,
                mlSidecarService,
                catalogRepository,
                embeddingRepository,
                relationGraphRepository,
                failureRepository);
        setField("fullCatalogUnchangedStopPages", 40);
        setField("embedRetryAttempts", 3);
        when(mlSidecarService.isEnabled()).thenReturn(true);
        when(embeddingRepository.countCustomEmbeddings()).thenReturn(0L);
    }

    @Test
    void populateFullCatalogRange_retriesFailedEmbeddingAndAdvancesCursorOnRecovery() {
        AniListResponse.AnimeInfo anime = anime(101, "Retry Success");
        when(aniListService.fetchFullCatalogPage(1, 1)).thenReturn(List.of(anime));
        when(embeddingRepository.existsByAnilistId(101)).thenReturn(false);
        when(mlSidecarService.embedText(anyString()))
                .thenReturn(null)
                .thenReturn(new float[] { 0.1f, 0.2f, 0.3f });

        AnimeEmbeddingPopulatorService.PopulationStats stats = service.populateFullCatalogRange(1, 1, 1);

        assertEquals(2, stats.nextPageHint());
        assertEquals(1, stats.embedded());
        assertEquals(0, stats.failed());
        verify(mlSidecarService, times(2)).embedText(anyString());
        verify(failureRepository, never()).recordFailure(
                eq(101),
                eq("full_catalog"),
                any(EmbeddingFailureReason.class),
                anyString());
    }

    @Test
    void populateFullCatalogRange_keepsCursorPinnedWhenRetriesExhausted() {
        AniListResponse.AnimeInfo anime = anime(201, "Retry Exhausted");
        when(aniListService.fetchFullCatalogPage(1, 1)).thenReturn(List.of(anime));
        when(embeddingRepository.existsByAnilistId(201)).thenReturn(false);
        when(mlSidecarService.embedText(anyString()))
                .thenReturn(null)
                .thenReturn(null)
                .thenReturn(null);

        AnimeEmbeddingPopulatorService.PopulationStats stats = service.populateFullCatalogRange(1, 3, 1);

        assertEquals(1, stats.nextPageHint());
        assertEquals(1, stats.pagesVisited());
        assertEquals(0, stats.embedded());
        assertEquals(1, stats.failed());
        assertFalse(stats.exhausted());
        verify(aniListService, never()).fetchFullCatalogPage(2, 1);
        verify(failureRepository, times(1)).recordFailure(
                eq(201),
                eq("full_catalog"),
                eq(EmbeddingFailureReason.EMBED_FAILURE),
                eq("sidecar_embedding_empty_or_invalid"));
    }

    @Test
    void populateFullCatalogRange_retriesOnExceptionAndRecovers() {
        AniListResponse.AnimeInfo anime = anime(301, "Retry Exception");
        when(aniListService.fetchFullCatalogPage(1, 1)).thenReturn(List.of(anime));
        when(embeddingRepository.existsByAnilistId(301)).thenReturn(false);
        when(mlSidecarService.embedText(anyString()))
                .thenThrow(new RuntimeException("sidecar unavailable"))
                .thenReturn(new float[] { 0.4f, 0.5f, 0.6f });

        AnimeEmbeddingPopulatorService.PopulationStats stats = service.populateFullCatalogRange(1, 1, 1);

        assertEquals(2, stats.nextPageHint());
        assertEquals(1, stats.embedded());
        assertEquals(0, stats.failed());
        verify(mlSidecarService, times(2)).embedText(anyString());
        verify(failureRepository, never()).recordFailure(
                eq(301),
                eq("full_catalog"),
                any(EmbeddingFailureReason.class),
                anyString());
    }

    @Test
    void refreshCatalogIds_embedsCatalogIdsFromAniListApi() {
        AniListResponse.AnimeInfo anime = anime(401, "Catalog ID Refresh");
        when(aniListService.getAnimeByIdFromApi(401)).thenReturn(anime);
        when(embeddingRepository.existsByAnilistId(401)).thenReturn(false);
        when(mlSidecarService.embedText(anyString()))
                .thenReturn(new float[] { 0.2f, 0.3f, 0.4f });

        AnimeEmbeddingPopulatorService.IdBackfillStats stats =
                service.refreshCatalogIds(List.of(401), "catalog_low_metadata_backfill");

        assertEquals(1, stats.requestedIds());
        assertEquals(1, stats.discovered());
        assertEquals(1, stats.embedded());
        assertEquals(0, stats.failed());
        verify(aniListService).getAnimeByIdFromApi(401);
    }

    @Test
    void refreshCatalogIds_recordsFailureWhenAniListReturnsNoMetadata() {
        when(aniListService.getAnimeByIdFromApi(777)).thenReturn(null);

        AnimeEmbeddingPopulatorService.IdBackfillStats stats =
                service.refreshCatalogIds(List.of(777), "catalog_low_metadata_backfill");

        assertEquals(1, stats.requestedIds());
        assertEquals(0, stats.discovered());
        assertEquals(1, stats.failed());
        verify(failureRepository).recordFailure(
                eq(777),
                eq("catalog_low_metadata_backfill"),
                eq(EmbeddingFailureReason.MISSING_METADATA),
                eq("AniList returned no metadata"));
    }

    private AniListResponse.AnimeInfo anime(int id, String titleText) {
        AniListResponse.AnimeInfo anime = new AniListResponse.AnimeInfo();
        anime.setId(id);
        AniListResponse.AnimeTitle title = new AniListResponse.AnimeTitle();
        title.setRomaji(titleText);
        anime.setTitle(title);
        return anime;
    }

    private void setField(String fieldName, Object value) throws Exception {
        Field field = AnimeEmbeddingPopulatorService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(service, value);
    }
}
