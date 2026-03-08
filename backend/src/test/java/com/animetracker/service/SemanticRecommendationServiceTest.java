package com.animetracker.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.animetracker.dto.RecommendationFeedbackRequest;
import com.animetracker.dto.RecommendationResponse;
import com.animetracker.exception.BadRequestException;
import com.animetracker.exception.UnauthorizedException;
import com.animetracker.dto.AniListResponse;
import com.animetracker.dto.SemanticRequest;
import com.animetracker.entity.AnimeListEntry;
import com.animetracker.entity.RecommendationFeedback;
import com.animetracker.entity.User;
import com.animetracker.repository.AnimeEmbeddingRepository;
import com.animetracker.repository.AnimeRelationGraphRepository;
import com.animetracker.repository.AniListSyncStateRepository;
import com.animetracker.repository.CustomEmbeddingImportStateRepository;
import com.animetracker.repository.RecommendationFeedbackRepository;
import com.animetracker.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class SemanticRecommendationServiceTest {
    private static final double EPS = 1e-6;

    @Mock
    private AnimeEmbeddingRepository embeddingRepository;
    @Mock
    private AnimeRelationGraphRepository relationGraphRepository;
    @Mock
    private AnimeListEntryService animeListEntryService;
    @Mock
    private RecommendationFeedbackRepository feedbackRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AniListService aniListService;
    @Mock
    private AnimeEmbeddingPopulatorService populatorService;
    @Mock
    private MlSidecarService mlSidecarService;
    @Mock
    private CustomEmbeddingImportStateRepository customEmbeddingImportStateRepository;
    @Mock
    private AniListSyncStateRepository aniListSyncStateRepository;
    @Mock
    private RecommendationCandidateTuning candidateTuning;

    private SemanticRecommendationService service;

    @BeforeEach
    void setUp() {
        service = new SemanticRecommendationService(
                embeddingRepository,
                relationGraphRepository,
                animeListEntryService,
                feedbackRepository,
                userRepository,
                aniListService,
                populatorService,
                mlSidecarService,
                customEmbeddingImportStateRepository,
                aniListSyncStateRepository,
                candidateTuning);
        lenient().when(relationGraphRepository.findAnimeIdsHavingRelationType(anyList(), anyList())).thenReturn(java.util.Set.of());
        lenient().when(relationGraphRepository.resolveEntrypoint(anyInt(), anyList(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(candidateTuning.semanticLexicalCandidateLimit()).thenReturn(60);
        lenient().when(candidateTuning.semanticVectorCandidateLimit()).thenReturn(140);
        lenient().when(candidateTuning.semanticMergedCandidateLimit()).thenReturn(140);
        lenient().when(candidateTuning.semanticSimilarCandidateLimit()).thenReturn(90);
        assertDoesNotThrow(() -> setField(service, "useCustomVectors", true));
        assertDoesNotThrow(() -> setField(service, "semanticPopularityPriorEnabled", true));
        assertDoesNotThrow(() -> setField(service, "semanticTasteWeightLoggedIn", 0.20f));
        assertDoesNotThrow(() -> setField(service, "semanticTasteWeightLoggedInBroadQuery", 0.25f));
        assertDoesNotThrow(() -> setField(service, "semanticPopularityPriorWeightLoggedIn", 0.10f));
        assertDoesNotThrow(() -> setField(service, "semanticPopularityPriorWeightLoggedInBroadQuery", 0.15f));
        assertDoesNotThrow(() -> setField(service, "semanticPopularityPriorWeightLoggedOut", 0.15f));
        assertDoesNotThrow(() -> setField(service, "semanticPopularityPriorWeightLoggedOutBroadQuery", 0.25f));
        assertDoesNotThrow(() -> setField(service, "semanticPopularityGuardrailThreshold", 0.45f));
        assertDoesNotThrow(() -> setField(service, "semanticPopularityGuardrailMaxWeight", 0.05f));
        assertDoesNotThrow(() -> setField(service, "semanticBroadQueryLowQualityScoreThreshold", 72));
        assertDoesNotThrow(() -> setField(service, "semanticBroadQueryLowQualityPopularityThreshold", 15000));
        assertDoesNotThrow(() -> setField(service, "semanticBroadQueryLowQualityPenalty", 0.88f));
        assertDoesNotThrow(() -> setField(service, "semanticPopularityPriorNormalizationPower", 2.0f));
        assertDoesNotThrow(() -> setField(service, "semanticListBlendCapWithQuery", 0.08f));
        assertDoesNotThrow(() -> setField(service, "semanticListBlendCapBroadQuery", 0.12f));
        assertDoesNotThrow(() -> setField(service, "semanticListBlendCapTitleIntent", 0.05f));
        assertDoesNotThrow(() -> setField(service, "feedbackTasteRatingWeight", 0.70f));
        assertDoesNotThrow(() -> setField(service, "feedbackScoreAdjustmentThumbsUp", 0.04f));
        assertDoesNotThrow(() -> setField(service, "feedbackScoreAdjustmentThumbsDown", 0.06f));
        assertDoesNotThrow(() -> setField(service, "cfTasteVectorWeight", 0.20f));
        assertDoesNotThrow(() -> setField(service, "cfPopularFallbackEnabled", true));
        assertDoesNotThrow(() -> setField(service, "cfPopularFallbackMinRatedItems", 1));
        assertDoesNotThrow(() -> setField(service, "cfPopularFallbackCandidateLimit", 100));
        assertDoesNotThrow(() -> setField(service, "controlsEntrypointRemapMaxHydrations", 8));
        assertDoesNotThrow(() -> setField(service, "controlsEntrypointRemapMaxHydrationsHardCap", 12));
        assertDoesNotThrow(() -> setField(service, "controlsEntrypointRemapMaxHydrationsCf", 3));
        assertDoesNotThrow(() -> setField(service, "controlsEntrypointRemapFailureCircuitThreshold", 3));
    }

    @Test
    void recommend_withoutInputsAndNotListOnly_throwsBadRequest() {
        assertThrows(BadRequestException.class,
                () -> service.recommend(null, List.of(), null, 15, false, null, "semantic"));
    }

    @Test
    void recommend_listOnlyWithoutUser_throwsUnauthorized() {
        assertThrows(UnauthorizedException.class,
                () -> service.recommend(null, List.of(), null, 15, true, 1.0f, "semantic"));
    }

    @Test
    void recommend_semanticModeSeedOnly_throwsBadRequest() {
        assertThrows(BadRequestException.class,
                () -> service.recommend(null, List.of(1, 2), null, 15, false, null, "semantic"));
    }

    @Test
    void recommend_semanticModeWithQueryAndSeeds_ignoresSeedsAndDoesNotThrow() {
        when(mlSidecarService.isEnabled()).thenReturn(true);
        when(mlSidecarService.embedText(anyString())).thenReturn(new float[] { 0.1f, 0.2f, 0.3f });
        when(embeddingRepository.findSimilarCustom(anyString(), anyList(), anyInt())).thenReturn(List.of());

        assertDoesNotThrow(() -> service.recommend(
                null,
                List.of(1, 2, 3),
                "dark thriller",
                15,
                false,
                null,
                "semantic"));

        verify(mlSidecarService).embedText("dark thriller");
        verify(embeddingRepository, never()).findEmbeddingsByAnilistIds(anyList());
        verify(embeddingRepository).findSimilarCustom(anyString(), anyList(), anyInt());
    }

    @Test
    void recommend_similarModeWithoutSeeds_throwsBadRequest() {
        assertThrows(BadRequestException.class,
                () -> service.recommend(null, List.of(), "any query", 15, false, null, "similar"));
    }

    @Test
    void recommend_semanticMode_loggedOutIgnoresListWeight() {
        when(mlSidecarService.isEnabled()).thenReturn(true);
        when(mlSidecarService.embedText(anyString())).thenReturn(new float[] { 0.1f, 0.2f, 0.3f });
        when(embeddingRepository.findSimilarCustom(anyString(), anyList(), anyInt())).thenReturn(java.util.Collections.singletonList(
                sampleSemanticRow(301, "Query Match", 0.12d)));

        List<?> results = service.recommend(
                null,
                List.of(),
                "action",
                10,
                false,
                1.0f,
                "semantic");

        assertEquals(1, results.size());
        verify(animeListEntryService, never()).getUserList(anyString());
    }

    @Test
    void recommend_semanticMode_filtersCancelledShows() {
        when(mlSidecarService.isEnabled()).thenReturn(true);
        when(mlSidecarService.embedText(anyString())).thenReturn(new float[] { 0.1f, 0.2f, 0.3f });
        when(embeddingRepository.findSimilarCustom(anyString(), anyList(), anyInt())).thenReturn(List.of(
                sampleSemanticRowWithStatus(777, "Cancelled Title", "CANCELLED", 0.08d),
                sampleSemanticRowWithStatus(778, "Finished Title", "FINISHED", 0.09d)));

        List<?> results = service.recommend(
                null,
                List.of(),
                "comedic sports show",
                10,
                false,
                null,
                "semantic");

        assertEquals(1, results.size());
        RecommendationResponse row = (RecommendationResponse) results.get(0);
        assertEquals(778, row.getAnime().getId());
        assertEquals("FINISHED", row.getAnime().getStatus());
    }

    @Test
    void recommend_similarModeWithSeeds_loggedOutWorks() {
        when(embeddingRepository.findCustomEmbeddingsByAnilistIds(anyList()))
                .thenReturn(java.util.Collections.singletonList(new Object[] { 101, "[0.1,0.2,0.3]" }));
        when(embeddingRepository.findSimilarCustom(anyString(), anyList(), anyInt()))
                .thenReturn(java.util.Collections.singletonList(sampleSemanticRow(202, "Seed Similar", 0.10d)));

        List<?> results = service.recommend(
                null,
                List.of(101),
                null,
                10,
                false,
                0.75f,
                "similar");

        assertEquals(1, results.size());
        verify(animeListEntryService, never()).getUserList(anyString());
    }

    @Test
    void populateActiveCatalogEmbeddings_resumesAndPersistsNextPage() {
        when(aniListSyncStateRepository.findOrCreate(eq("catalog_populate"), eq(1), eq("25")))
                .thenReturn(new AniListSyncStateRepository.SyncState(
                        "catalog_populate",
                        2212,
                        null,
                        null,
                        null,
                        "25"));
        when(populatorService.populateFullCatalogRange(eq(2212), eq(25), eq(10)))
                .thenReturn(new AnimeEmbeddingPopulatorService.PopulationStats(
                        "active_catalog",
                        2212,
                        2213,
                        false,
                        1,
                        50,
                        40,
                        10,
                        40,
                        50,
                        0,
                        22118L,
                        1.0d,
                        1.0d,
                        1.0d,
                        1.0d,
                        false,
                        0));

        Map<String, Object> out = service.populateActiveCatalogEmbeddings(25, 50);

        assertEquals(2212, out.get("startPage"));
        assertEquals(2213, out.get("nextPageHint"));
        assertEquals("catalog_populate", out.get("resumeSource"));
        verify(populatorService).populateFullCatalogRange(eq(2212), eq(25), eq(10));
        verify(aniListSyncStateRepository).markSuccess(eq("catalog_populate"), eq(2213), eq("25"), any());
        verify(populatorService, never()).populateActiveCatalog(anyInt(), anyInt());
    }

    @Test
    void populateActiveCatalogEmbeddings_keepsCursorWhenPageIsPartial() {
        when(aniListSyncStateRepository.findOrCreate(eq("catalog_populate"), eq(1), eq("25")))
                .thenReturn(new AniListSyncStateRepository.SyncState(
                        "catalog_populate",
                        2214,
                        null,
                        null,
                        null,
                        "25"));
        when(populatorService.populateFullCatalogRange(eq(2214), eq(25), eq(10)))
                .thenReturn(new AnimeEmbeddingPopulatorService.PopulationStats(
                        "full_catalog",
                        2214,
                        2214,
                        true,
                        1,
                        1,
                        1,
                        0,
                        1,
                        1,
                        0,
                        22120L,
                        1.0d,
                        1.0d,
                        1.0d,
                        1.0d,
                        false,
                        0));

        Map<String, Object> out = service.populateActiveCatalogEmbeddings(25, 10);

        assertEquals(2214, out.get("startPage"));
        assertEquals(2214, out.get("nextPageHint"));
        verify(aniListSyncStateRepository).markSuccess(eq("catalog_populate"), eq(2214), eq("25"), any());
    }

    @Test
    void recommend_semanticMode_preprocessesQueryBeforeEmbedding() {
        when(mlSidecarService.isEnabled()).thenReturn(true);
        when(mlSidecarService.embedText(anyString())).thenReturn(new float[] { 0.1f, 0.2f, 0.3f });
        when(embeddingRepository.findSimilarCustom(anyString(), anyList(), anyInt())).thenReturn(List.of());

        assertDoesNotThrow(() -> service.recommend(
                null,
                List.of(),
                "ROMCOM!!! isekai??",
                15,
                false,
                null,
                "semantic"));

        verify(mlSidecarService).embedText(eq("romcom romance comedy isekai another world fantasy adventure"));
    }

    @Test
    void preprocessSemanticQuery_negatedTermsDoNotExpandPositiveSynonyms() throws Exception {
        String normalized = (String) invokePrivate(
                "preprocessSemanticQuery",
                new Class<?>[] { String.class },
                "not isekai mecha idol");

        assertTrue(normalized.contains("not isekai mecha idol"));
        assertTrue(!normalized.contains("another world"));
        assertTrue(!normalized.contains("robot sci fi"));
    }

    @Test
    void recommend_semanticMode_usesLexicalFallbackCandidatesWhenVectorIsEmpty() {
        assertDoesNotThrow(() -> setField(service, "semanticLexicalEnabled", true));
        when(mlSidecarService.isEnabled()).thenReturn(true);
        when(mlSidecarService.embedText(anyString())).thenReturn(new float[] { 0.1f, 0.2f, 0.3f });
        when(embeddingRepository.findSimilarCustom(anyString(), anyList(), anyInt())).thenReturn(List.of());
        when(embeddingRepository.findLexicalMatches(anyString(), anyList(), anyInt()))
                .thenReturn(java.util.Collections.singletonList(sampleSemanticRow(777, "Lexical Match", 0.20d)));

        assertDoesNotThrow(() -> service.recommend(
                null,
                List.of(),
                "romcom school",
                10,
                false,
                null,
                "semantic"));
        verify(embeddingRepository).findLexicalMatches(anyString(), anyList(), anyInt());
    }

    @Test
    void recommend_semanticMode_usesSidecarRerankWhenEnabled() throws Exception {
        setField(service, "semanticLexicalEnabled", false);
        setField(service, "semanticRerankEnabled", true);
        setField(service, "semanticRerankTopK", 20);

        when(mlSidecarService.isEnabled()).thenReturn(true);
        when(mlSidecarService.embedText(anyString())).thenReturn(new float[] { 0.1f, 0.2f, 0.3f });
        when(embeddingRepository.findSimilarCustom(anyString(), anyList(), anyInt())).thenReturn(List.of(
                sampleSemanticRow(101, "First Distance", 0.30d),
                sampleSemanticRow(202, "Second Distance", 0.10d)));
        when(mlSidecarService.rerank(any(), anyList(), anyList(), anyInt())).thenReturn(List.of(
                Map.of("anilist_id", 101, "score", 0.90d),
                Map.of("anilist_id", 202, "score", 0.20d)));

        @SuppressWarnings("unchecked")
        List<com.animetracker.dto.RecommendationResponse> results =
                (List<com.animetracker.dto.RecommendationResponse>) (List<?>) service.recommend(
                        null,
                        List.of(),
                        "dark thriller",
                        10,
                        false,
                        null,
                        "semantic");

        verify(mlSidecarService).rerank(any(), anyList(), anyList(), anyInt());
        assertEquals(2, results.size());
        assertEquals(101, results.get(0).getAnime().getId());
    }

    @Test
    void recommend_semanticMode_dedupesFranchiseSpecials() throws Exception {
        setField(service, "semanticLexicalEnabled", false);
        setField(service, "semanticRerankEnabled", false);
        setField(service, "semanticDedupeEnabled", true);
        setField(service, "semanticDedupeMaxPerFranchise", 1);
        setField(service, "semanticDedupeSuppressSpecials", true);

        when(mlSidecarService.isEnabled()).thenReturn(true);
        when(mlSidecarService.embedText(anyString())).thenReturn(new float[] { 0.1f, 0.2f, 0.3f });
        when(embeddingRepository.findSimilarCustom(anyString(), anyList(), anyInt())).thenReturn(List.of(
                sampleSemanticRow(101, "Attack on Titan OVA", 0.05d),
                sampleSemanticRow(102, "Attack on Titan Season 1", 0.06d),
                sampleSemanticRow(201, "Vinland Saga", 0.07d)));

        @SuppressWarnings("unchecked")
        List<com.animetracker.dto.RecommendationResponse> results =
                (List<com.animetracker.dto.RecommendationResponse>) (List<?>) service.recommend(
                        null,
                        List.of(),
                        "dark action",
                        2,
                        false,
                        null,
                        "semantic");

        assertEquals(2, results.size());
        assertEquals(102, results.get(0).getAnime().getId());
        assertEquals(201, results.get(1).getAnime().getId());
    }

    @Test
    void isExtraSeasonCandidate_flagsPrequelRelation() throws Exception {
        AniListResponse.AnimeInfo anime = new AniListResponse.AnimeInfo();
        anime.setId(1001);
        AniListResponse.AnimeRelation relation = new AniListResponse.AnimeRelation();
        relation.setId(1000);
        relation.setRelationType("PREQUEL");
        anime.setRelations(List.of(relation));

        boolean flagged = AnimeFilterPolicy.isExtraSeasonCandidate(anime);
        assertTrue(flagged);
    }

    @Test
    void isExtraSeasonCandidate_doesNotFlagBaseTitle() throws Exception {
        AniListResponse.AnimeInfo anime = new AniListResponse.AnimeInfo();
        AniListResponse.AnimeTitle title = new AniListResponse.AnimeTitle();
        title.setEnglish("Haikyuu!!");
        anime.setTitle(title);

        boolean flagged = AnimeFilterPolicy.isExtraSeasonCandidate(anime);
        assertTrue(!flagged);
    }

    @Test
    void recommend_cfMode_usesLocalMetadataBeforeAnilistFallback() {
        User user = new User();
        user.setId(10L);
        user.setUsername("alice");

        AnimeListEntry watched = new AnimeListEntry();
        watched.setAnilistId(1);
        watched.setScore(8);

        Map<String, Object> cfPrediction = Map.of(
                "anilist_id", 123,
                "predicted_score", 8.7,
                "watch_confidence", 0.91);

        when(mlSidecarService.isEnabled()).thenReturn(true);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(animeListEntryService.getUserList("alice")).thenReturn(List.of(watched));
        when(feedbackRepository.findByUserOrderByUpdatedAtDesc(user)).thenReturn(List.of());
        when(mlSidecarService.getCfRecommendations(anyMap(), anyList(), anyInt())).thenReturn(List.of(cfPrediction));
        when(embeddingRepository.findMetadataByAnilistIds(List.of(123))).thenReturn(java.util.Collections.singletonList(new Object[] {
                123,
                "Local Title",
                "Local English",
                "https://img.test/123.jpg",
                "Action, Sci-Fi",
                "Local description",
                80,
                "FINISHED",
                12,
                100000
        }));

        List<?> results = service.recommend("alice", List.of(), null, 10, false, null, "cf");

        verify(aniListService, never()).getAnimeById(123);
        org.junit.jupiter.api.Assertions.assertEquals(1, results.size());
    }

    @Test
    void recommend_cfMode_coldStartFallsBackToPopularCatalog() throws Exception {
        User user = new User();
        user.setId(12L);
        user.setUsername("alice");

        setField(service, "cfPopularFallbackMinRatedItems", 3);

        when(mlSidecarService.isEnabled()).thenReturn(true);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(animeListEntryService.getUserList("alice")).thenReturn(List.of());
        when(feedbackRepository.findByUserOrderByUpdatedAtDesc(user)).thenReturn(List.of());
        when(embeddingRepository.findTopPopularMetadataExcluding(anyList(), anyInt()))
                .thenReturn(java.util.Collections.singletonList(new Object[] {
                        999,
                        "Popular Title",
                        "Popular Title",
                        "https://img.test/999.jpg",
                        "Action, Adventure",
                        "Popular description",
                        86,
                        "FINISHED",
                        24,
                        550000,
                        "TV",
                        "SPRING",
                        2019,
                        false,
                        null
                }));

        @SuppressWarnings("unchecked")
        List<com.animetracker.dto.RecommendationResponse> results =
                (List<com.animetracker.dto.RecommendationResponse>) (List<?>) service.recommend(
                        "alice",
                        List.of(),
                        null,
                        10,
                        false,
                        null,
                        "cf");

        assertEquals(1, results.size());
        assertEquals(999, results.get(0).getAnime().getId());
        verify(mlSidecarService, never()).getCfRecommendations(anyMap(), anyList(), anyInt());
    }

    @Test
    void loadMetadataFromStore_prefersCatalogLocalLookup() throws Exception {
        AniListResponse.AnimeInfo catalogAnime = new AniListResponse.AnimeInfo();
        catalogAnime.setId(7777);
        AniListResponse.AnimeTitle title = new AniListResponse.AnimeTitle();
        title.setEnglish("Catalog-first Title");
        catalogAnime.setTitle(title);
        when(aniListService.getAnimeByIdLocalOnly(7777)).thenReturn(catalogAnime);

        AniListResponse.AnimeInfo resolved = (AniListResponse.AnimeInfo) invokePrivate(
                "loadMetadataFromStore",
                new Class<?>[] { Integer.class },
                7777);

        assertEquals(7777, resolved.getId());
        assertEquals("Catalog-first Title", resolved.getTitle().getEnglish());
        verify(embeddingRepository, never()).findMetadataByAnilistIds(anyList());
    }

    @Test
    void normalizeLimit_allowsUpToHundred() throws Exception {
        int accepted = (Integer) invokePrivate(
                "normalizeLimit",
                new Class<?>[] { Integer.class },
                100);
        int clamped = (Integer) invokePrivate(
                "normalizeLimit",
                new Class<?>[] { Integer.class },
                101);

        assertEquals(100, accepted);
        assertEquals(15, clamped);
    }

    @Test
    void recommend_cfMode_blendsTasteVectorIntoCfScore() {
        User user = new User();
        user.setId(11L);
        user.setUsername("alice");

        AnimeListEntry watched = new AnimeListEntry();
        watched.setAnilistId(1);
        watched.setScore(10);

        Map<String, Object> cfPrediction = Map.of(
                "anilist_id", 123,
                "predicted_score", 5.0,
                "watch_confidence", 1.0);

        when(mlSidecarService.isEnabled()).thenReturn(true);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(animeListEntryService.getUserList("alice")).thenReturn(List.of(watched));
        when(feedbackRepository.findByUserOrderByUpdatedAtDesc(user)).thenReturn(List.of());
        when(mlSidecarService.getCfRecommendations(anyMap(), anyList(), anyInt())).thenReturn(List.of(cfPrediction));
        when(embeddingRepository.findMetadataByAnilistIds(List.of(123))).thenReturn(java.util.Collections.singletonList(new Object[] {
                123,
                "Local Title",
                "Local English",
                "https://img.test/123.jpg",
                "Action, Sports",
                "Local description",
                80,
                "FINISHED",
                12,
                100000
        }));
        when(embeddingRepository.findCustomEmbeddingsByAnilistIds(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<Integer> ids = invocation.getArgument(0);
            if (ids.contains(123)) {
                return java.util.Collections.singletonList(new Object[] { 123, "[1.0,0.0]" });
            }
            if (ids.contains(1)) {
                return java.util.Collections.singletonList(new Object[] { 1, "[1.0,0.0]" });
            }
            return List.of();
        });

        @SuppressWarnings("unchecked")
        List<com.animetracker.dto.RecommendationResponse> results =
                (List<com.animetracker.dto.RecommendationResponse>) (List<?>) service.recommend(
                        "alice",
                        List.of(),
                        null,
                        10,
                        false,
                        null,
                        "cf");

        assertEquals(1, results.size());
        double pureCfScore = FusionScoringService.normalizeCfScore(5.0d, 1.0d);
        assertTrue(results.get(0).getFusionScore() > pureCfScore);
        assertTrue(numberValue(results.get(0).getAnime().getUserTasteScore(), 0.0d) > 0.95d);
    }

    @Test
    void recordFeedback_persistsNormalizedSignalAndMode() {
        User user = new User();
        user.setId(22L);
        user.setUsername("alice");

        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(feedbackRepository.findByUserAndAnilistId(user, 16498)).thenReturn(Optional.empty());

        RecommendationFeedbackRequest request = new RecommendationFeedbackRequest(
                16498,
                "thumbs_down",
                "semantic",
                "sports anime",
                "Haikyuu!!",
                "https://img.test/haikyuu.jpg");

        service.recordFeedback("alice", request);

        ArgumentCaptor<RecommendationFeedback> captor = ArgumentCaptor.forClass(RecommendationFeedback.class);
        verify(feedbackRepository).save(captor.capture());
        RecommendationFeedback saved = captor.getValue();
        assertEquals(16498, saved.getAnilistId());
        assertEquals(RecommendationFeedback.SIGNAL_THUMBS_DOWN, saved.getSignal());
        assertEquals("semantic", saved.getSourceMode());
        assertTrue(saved.getQueryHash() != null && !saved.getQueryHash().isBlank());
    }

    @Test
    void buildExcludeIds_doesNotIncludeThumbsDownFeedback() throws Exception {
        AnimeListEntry listEntry = new AnimeListEntry();
        listEntry.setAnilistId(111);

        when(animeListEntryService.getUserList("alice")).thenReturn(List.of(listEntry));

        @SuppressWarnings("unchecked")
        List<Integer> excluded = (List<Integer>) invokePrivate(
                "buildExcludeIds",
                new Class<?>[] { String.class, List.class },
                "alice",
                List.of(333));

        assertTrue(excluded.contains(333));
        assertTrue(excluded.contains(111));
        org.junit.jupiter.api.Assertions.assertFalse(excluded.contains(222));
    }

    @Test
    void buildUserPreferenceVector_usesThumbsFeedbackWhenListIsEmpty() throws Exception {
        User user = new User();
        user.setId(22L);
        user.setUsername("alice");

        RecommendationFeedback up = new RecommendationFeedback(
                user,
                222,
                RecommendationFeedback.SIGNAL_THUMBS_UP,
                "semantic",
                null,
                "Liked Show",
                null);

        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(animeListEntryService.getUserList("alice")).thenReturn(List.of());
        when(feedbackRepository.findByUserOrderByUpdatedAtDesc(user)).thenReturn(List.of(up));
        when(embeddingRepository.findCustomEmbeddingsByAnilistIds(anyList()))
                .thenReturn(java.util.Collections.singletonList(new Object[] { 222, "[1.0,0.0]" }));

        float[] vector = (float[]) invokePrivate(
                "buildUserPreferenceVector",
                new Class<?>[] { String.class },
                "alice");

        assertTrue(vector != null);
        assertTrue(vector[0] > 0.90f);
        assertTrue(Math.abs(vector[1]) < 1e-5f);
    }

    @Test
    void buildReasonSentence_cfContributorIncludesTitleAndSingleSentenceShape() throws Exception {
        Method method = SemanticRecommendationService.class.getDeclaredMethod(
                "buildReasonSentence", String.class, List.class, String.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        String reason = (String) method.invoke(
                service,
                "cf",
                List.of("CF_SIGNAL"),
                "Neon Genesis Evangelion");

        assertTrue(reason.contains("Neon Genesis Evangelion"));
        assertTrue(reason.startsWith("Recommended because "));
        assertTrue(reason.endsWith("."));
    }

    @Test
    void buildCfReasonSentence_includesContributorAndTasteEvidence() throws Exception {
        AniListResponse.AnimeInfo anime = new AniListResponse.AnimeInfo();
        anime.setGenres(List.of("Fantasy", "Adventure"));

        String reason = (String) invokePrivate(
                "buildCfReasonSentence",
                new Class<?>[] { AniListResponse.AnimeInfo.class, List.class, List.class, boolean.class },
                anime,
                List.of("Frieren: Beyond Journey's End"),
                List.of("fantasy", "slice of life"),
                false);

        assertTrue(reason.contains("Frieren: Beyond Journey's End"));
        assertTrue(reason.toLowerCase().contains("fantasy"));
        assertTrue(reason.startsWith("Recommended because "));
        assertTrue(reason.endsWith("."));
    }

    @Test
    void buildSemanticCacheKey_changesWhenExplanationFingerprintChanges() throws Exception {
        Object keyWithoutLlm = invokePrivate(
                "buildSemanticCacheKey",
                new Class<?>[] {
                        String.class,
                        String.class,
                        int.class,
                        int.class,
                        boolean.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class },
                "semantic",
                "sports anime",
                15,
                60,
                true,
                "user-fp",
                "model-fp",
                "emb-fp",
                "filters-fp",
                "deterministic-v2|llmEnabled=false");

        Object keyWithLlm = invokePrivate(
                "buildSemanticCacheKey",
                new Class<?>[] {
                        String.class,
                        String.class,
                        int.class,
                        int.class,
                        boolean.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class },
                "semantic",
                "sports anime",
                15,
                60,
                true,
                "user-fp",
                "model-fp",
                "emb-fp",
                "filters-fp",
                "deterministic-v2|llmEnabled=true");

        assertNotEquals(keyWithoutLlm, keyWithLlm);
    }

    @Test
    void filterAnchorTitlesForCandidate_excludesSameFranchiseAnchors() throws Exception {
        AniListResponse.AnimeInfo candidate = new AniListResponse.AnimeInfo();
        AniListResponse.AnimeTitle title = new AniListResponse.AnimeTitle();
        title.setEnglish("Haikyuu!! 2nd Season");
        candidate.setTitle(title);

        @SuppressWarnings("unchecked")
        List<String> anchors = (List<String>) invokePrivate(
                "filterAnchorTitlesForCandidate",
                new Class<?>[] { AniListResponse.AnimeInfo.class, List.class },
                candidate,
                List.of("Haikyuu!!", "Kuroko's Basketball"));

        assertEquals(1, anchors.size());
        assertEquals("Kuroko's Basketball", anchors.get(0));
    }

    @Test
    void applyQueryScoreCalibration_adjustsScoresButKeepsOrdering() throws Exception {
        setField(service, "semanticScoreCalibrationEnabled", true);
        setField(service, "semanticScoreCalibrationTemperature", 1.0f);

        List<FusionScoringService.ScoredCandidate> input = List.of(
                scoredCandidate(1, 0.90d),
                scoredCandidate(2, 0.50d),
                scoredCandidate(3, 0.10d));

        @SuppressWarnings("unchecked")
        List<FusionScoringService.ScoredCandidate> calibrated = (List<FusionScoringService.ScoredCandidate>) invokePrivate(
                "applyQueryScoreCalibration",
                new Class<?>[] { List.class, boolean.class },
                input,
                true);

        assertEquals(3, calibrated.size());
        assertTrue(calibrated.get(0).score() > calibrated.get(1).score());
        assertTrue(calibrated.get(1).score() > calibrated.get(2).score());
        assertNotEquals(input.get(0).score(), calibrated.get(0).score(), EPS);
    }

    @Test
    void applyModeBlendedScoring_loggedOutBroadQuery_penalizesLowQualityLowPopularity() throws Exception {
        List<FusionScoringService.ScoredCandidate> input = List.of(
                scoredCandidateWithMetadata(1001, 0.90d, 0.90d, 0.70d, 69, 5957, List.of("Comedy", "Slice of Life")));

        @SuppressWarnings("unchecked")
        List<FusionScoringService.ScoredCandidate> narrow = (List<FusionScoringService.ScoredCandidate>) invokePrivate(
                "applyModeBlendedScoring",
                new Class<?>[] { List.class, String.class, boolean.class, List.class, boolean.class },
                input,
                "semantic",
                false,
                List.of(),
                false);

        @SuppressWarnings("unchecked")
        List<FusionScoringService.ScoredCandidate> broad = (List<FusionScoringService.ScoredCandidate>) invokePrivate(
                "applyModeBlendedScoring",
                new Class<?>[] { List.class, String.class, boolean.class, List.class, boolean.class },
                input,
                "semantic",
                false,
                List.of(),
                true);

        assertEquals(1, narrow.size());
        assertEquals(1, broad.size());
        assertTrue(broad.get(0).score() < narrow.get(0).score());
        assertEquals(Boolean.TRUE, broad.get(0).animeInfo().getGuardrailApplied());
    }

    @Test
    void applyModeBlendedScoring_loggedInBroadQuery_boostsTasteWeight() throws Exception {
        FusionScoringService.ScoredCandidate highQueryLowTaste = scoredCandidateWithMetadata(
                2001, 0.85d, 0.85d, 0.80d, 82, 150000, List.of("Action"));
        FusionScoringService.ScoredCandidate midQueryHighTaste = scoredCandidateWithMetadata(
                2002, 0.50d, 0.50d, 0.80d, 82, 150000, List.of("Slice of Life"));
        List<FusionScoringService.ScoredCandidate> input = List.of(highQueryLowTaste, midQueryHighTaste);

        @SuppressWarnings("unchecked")
        List<FusionScoringService.ScoredCandidate> narrow = (List<FusionScoringService.ScoredCandidate>) invokePrivate(
                "applyModeBlendedScoring",
                new Class<?>[] { List.class, String.class, boolean.class, List.class, boolean.class },
                input,
                "semantic",
                true,
                List.of("slice of life"),
                false);

        @SuppressWarnings("unchecked")
        List<FusionScoringService.ScoredCandidate> broad = (List<FusionScoringService.ScoredCandidate>) invokePrivate(
                "applyModeBlendedScoring",
                new Class<?>[] { List.class, String.class, boolean.class, List.class, boolean.class },
                input,
                "semantic",
                true,
                List.of("slice of life"),
                true);

        assertEquals(2001, narrow.get(0).anilistId());
        assertEquals(2002, broad.get(0).anilistId());
    }

    @Test
    void applyRecommendationControls_filtersAdultByDefault() throws Exception {
        com.animetracker.dto.AniListResponse.AnimeInfo anime = new com.animetracker.dto.AniListResponse.AnimeInfo();
        anime.setId(777);
        anime.setGenres(List.of("Comedy", "Ecchi"));
        com.animetracker.dto.AniListResponse.AnimeTag tag = new com.animetracker.dto.AniListResponse.AnimeTag();
        tag.setName("Ecchi");
        tag.setRank(80);
        anime.setTags(List.of(tag));
        com.animetracker.dto.RecommendationResponse row = new com.animetracker.dto.RecommendationResponse(
                anime,
                0.55d,
                List.of(com.animetracker.dto.RecommendationResponse.MATCHES_QUERY));

        SemanticRequest.Filters requestedFilters = new SemanticRequest.Filters();
        requestedFilters.setIncludeMovies(false);
        requestedFilters.setIncludeExtraSeasons(false);
        requestedFilters.setIncludeOnasOvasSpecials(false);
        requestedFilters.setIncludeMusic(false);
        requestedFilters.setIncludeAdult(false);

        Object controls = invokePrivate(
                "resolveRecommendationControls",
                new Class<?>[] { SemanticRequest.Filters.class },
                requestedFilters);

        Method method = SemanticRecommendationService.class.getDeclaredMethod(
                "applyRecommendationControls",
                List.class,
                controls.getClass(),
                String.class,
                int.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<com.animetracker.dto.RecommendationResponse> filtered =
                (List<com.animetracker.dto.RecommendationResponse>) method.invoke(
                        service,
                        List.of(row),
                        controls,
                        "semantic",
                        10);

        assertTrue(filtered.isEmpty());
    }

    @Test
    void applyRecommendationControls_excludesMovieWhenMovieFormatPresent() throws Exception {
        AniListResponse.AnimeInfo candidate = new AniListResponse.AnimeInfo();
        candidate.setId(991);
        AniListResponse.AnimeTitle candidateTitle = new AniListResponse.AnimeTitle();
        candidateTitle.setEnglish("Your Name");
        candidate.setTitle(candidateTitle);
        candidate.setFormat("MOVIE");

        RecommendationResponse row = new RecommendationResponse(
                candidate,
                0.65d,
                List.of(RecommendationResponse.MATCHES_QUERY));

        SemanticRequest.Filters requestedFilters = new SemanticRequest.Filters();
        requestedFilters.setIncludeMovies(false);
        requestedFilters.setIncludeExtraSeasons(false);
        requestedFilters.setIncludeOnasOvasSpecials(false);
        requestedFilters.setIncludeMusic(false);
        requestedFilters.setIncludeAdult(false);

        Object controls = invokePrivate(
                "resolveRecommendationControls",
                new Class<?>[] { SemanticRequest.Filters.class },
                requestedFilters);

        Method method = SemanticRecommendationService.class.getDeclaredMethod(
                "applyRecommendationControls",
                List.class,
                controls.getClass(),
                String.class,
                int.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<RecommendationResponse> filtered =
                (List<RecommendationResponse>) method.invoke(
                        service,
                        List.of(row),
                        controls,
                        "cf",
                        10);

        assertTrue(filtered.isEmpty());
    }

    @Test
    void applyRecommendationControls_adaptiveHydrationBudgetExcludesMovieBeyondConfiguredFloor() throws Exception {
        setField(service, "controlsEntrypointRemapMaxHydrations", 1);
        setField(service, "controlsEntrypointRemapMaxHydrationsHardCap", 40);
        setField(service, "controlsEntrypointRemapMaxHydrationsCf", 3);

        List<RecommendationResponse> rows = new ArrayList<>();
        for (int id = 1200; id < 1212; id++) {
            AniListResponse.AnimeInfo anime = new AniListResponse.AnimeInfo();
            anime.setId(id);
            AniListResponse.AnimeTitle title = new AniListResponse.AnimeTitle();
            title.setEnglish("Candidate " + id);
            anime.setTitle(title);
            rows.add(new RecommendationResponse(
                    anime,
                    0.70d - ((id - 1200) * 0.01d),
                    List.of(RecommendationResponse.MATCHES_QUERY)));
        }

        when(aniListService.getAnimeByIdLocalOnly(anyInt())).thenAnswer(invocation -> {
            int id = invocation.getArgument(0);
            AniListResponse.AnimeInfo hydrated = new AniListResponse.AnimeInfo();
            hydrated.setId(id);
            AniListResponse.AnimeTitle title = new AniListResponse.AnimeTitle();
            title.setEnglish("Hydrated " + id);
            hydrated.setTitle(title);
            hydrated.setFormat(id == 1211 ? "MOVIE" : "TV");
            hydrated.setRelations(List.of());
            return hydrated;
        });

        SemanticRequest.Filters requestedFilters = new SemanticRequest.Filters();
        requestedFilters.setIncludeMovies(false);
        requestedFilters.setIncludeExtraSeasons(false);
        requestedFilters.setIncludeOnasOvasSpecials(false);
        requestedFilters.setIncludeMusic(false);
        requestedFilters.setIncludeAdult(false);

        Object controls = invokePrivate(
                "resolveRecommendationControls",
                new Class<?>[] { SemanticRequest.Filters.class },
                requestedFilters);

        Method method = SemanticRecommendationService.class.getDeclaredMethod(
                "applyRecommendationControls",
                List.class,
                controls.getClass(),
                String.class,
                int.class,
                Map.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<RecommendationResponse> filtered = (List<RecommendationResponse>) method.invoke(
                service,
                rows,
                controls,
                "semantic",
                12,
                Map.of());

        assertTrue(filtered.stream().noneMatch(row -> row.getAnime() != null && Integer.valueOf(1211).equals(row.getAnime().getId())));
        verify(aniListService, atLeastOnce()).getAnimeByIdLocalOnly(1211);
    }

    @Test
    void applyRecommendationControls_cfHydrationBudgetCapsAniListCalls() throws Exception {
        setField(service, "controlsEntrypointRemapMaxHydrations", 20);
        setField(service, "controlsEntrypointRemapMaxHydrationsHardCap", 20);
        setField(service, "controlsEntrypointRemapMaxHydrationsCf", 2);

        List<RecommendationResponse> rows = new ArrayList<>();
        for (int id = 1300; id < 1310; id++) {
            AniListResponse.AnimeInfo anime = new AniListResponse.AnimeInfo();
            anime.setId(id);
            AniListResponse.AnimeTitle title = new AniListResponse.AnimeTitle();
            title.setEnglish("Candidate " + id);
            anime.setTitle(title);
            rows.add(new RecommendationResponse(
                    anime,
                    0.70d - ((id - 1300) * 0.01d),
                    List.of(RecommendationResponse.MATCHES_QUERY)));
        }

        SemanticRequest.Filters requestedFilters = new SemanticRequest.Filters();
        requestedFilters.setIncludeMovies(false);
        requestedFilters.setIncludeExtraSeasons(false);
        requestedFilters.setIncludeOnasOvasSpecials(false);
        requestedFilters.setIncludeMusic(false);
        requestedFilters.setIncludeAdult(false);

        Object controls = invokePrivate(
                "resolveRecommendationControls",
                new Class<?>[] { SemanticRequest.Filters.class },
                requestedFilters);

        Method method = SemanticRecommendationService.class.getDeclaredMethod(
                "applyRecommendationControls",
                List.class,
                controls.getClass(),
                String.class,
                int.class,
                Map.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<RecommendationResponse> filtered = (List<RecommendationResponse>) method.invoke(
                service,
                rows,
                controls,
                "cf",
                10,
                Map.of());

        assertTrue(!filtered.isEmpty());
    }

    @Test
    void applyRecommendationControls_underfilledDefaultsRelaxFormatFilters() throws Exception {
        com.animetracker.dto.AniListResponse.AnimeInfo anime = new com.animetracker.dto.AniListResponse.AnimeInfo();
        anime.setId(778);
        anime.setFormat("MOVIE");
        com.animetracker.dto.RecommendationResponse row = new com.animetracker.dto.RecommendationResponse(
                anime,
                0.50d,
                List.of(com.animetracker.dto.RecommendationResponse.MATCHES_QUERY));

        Object controls = invokePrivate(
                "resolveRecommendationControls",
                new Class<?>[] { SemanticRequest.Filters.class },
                new Object[] { null });

        Method method = SemanticRecommendationService.class.getDeclaredMethod(
                "applyRecommendationControls",
                List.class,
                controls.getClass(),
                String.class,
                int.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<com.animetracker.dto.RecommendationResponse> filtered =
                (List<com.animetracker.dto.RecommendationResponse>) method.invoke(
                        service,
                        List.of(row),
                        controls,
                        "semantic",
                        10);

        assertEquals(1, filtered.size());
        assertEquals(778, filtered.get(0).getAnime().getId());
    }

    @Test
    void applyRecommendationControls_remapsSpecialToEntrypointWhenAvailable() throws Exception {
        AniListResponse.AnimeInfo special = new AniListResponse.AnimeInfo();
        special.setId(900);
        AniListResponse.AnimeTitle specialTitle = new AniListResponse.AnimeTitle();
        specialTitle.setEnglish("No Game No Life Specials");
        special.setTitle(specialTitle);

        AniListResponse.AnimeRelation parentRelation = new AniListResponse.AnimeRelation();
        parentRelation.setId(901);
        parentRelation.setRelationType("PARENT");
        special.setRelations(List.of(parentRelation));

        AniListResponse.AnimeInfo parent = new AniListResponse.AnimeInfo();
        parent.setId(901);
        parent.setFormat("TV");
        AniListResponse.AnimeTitle parentTitle = new AniListResponse.AnimeTitle();
        parentTitle.setEnglish("No Game No Life");
        parent.setTitle(parentTitle);

        when(aniListService.getAnimeByIdLocalOnly(900)).thenReturn(special);
        when(aniListService.getAnimeByIdLocalOnly(901)).thenReturn(parent);
        when(relationGraphRepository.findAnimeIdsHavingRelationType(eq(List.of(900)), any()))
                .thenReturn(java.util.Set.of(900));
        when(relationGraphRepository.resolveEntrypoint(eq(900), any(), anyInt())).thenReturn(901);

        RecommendationResponse row = new RecommendationResponse(
                special,
                0.62d,
                List.of(RecommendationResponse.MATCHES_QUERY));

        Object controls = invokePrivate(
                "resolveRecommendationControls",
                new Class<?>[] { SemanticRequest.Filters.class },
                new Object[] { null });

        Method method = SemanticRecommendationService.class.getDeclaredMethod(
                "applyRecommendationControls",
                List.class,
                controls.getClass(),
                String.class,
                int.class,
                Map.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<RecommendationResponse> filtered = (List<RecommendationResponse>) method.invoke(
                service,
                List.of(row),
                controls,
                "semantic",
                10,
                Map.of());

        assertEquals(1, filtered.size());
        assertEquals(901, filtered.get(0).getAnime().getId());
    }

    @Test
    void applyRecommendationControls_remapsSequelChainToRootSeasonWhenAvailable() throws Exception {
        AniListResponse.AnimeInfo seasonThree = new AniListResponse.AnimeInfo();
        seasonThree.setId(930);
        seasonThree.setFormat("TV");
        AniListResponse.AnimeTitle s3Title = new AniListResponse.AnimeTitle();
        s3Title.setEnglish("Teekyuu 3");
        seasonThree.setTitle(s3Title);
        AniListResponse.AnimeRelation s3Prequel = new AniListResponse.AnimeRelation();
        s3Prequel.setId(920);
        s3Prequel.setRelationType("PREQUEL");
        seasonThree.setRelations(List.of(s3Prequel));

        AniListResponse.AnimeInfo seasonOne = new AniListResponse.AnimeInfo();
        seasonOne.setId(910);
        seasonOne.setFormat("TV");
        AniListResponse.AnimeTitle s1Title = new AniListResponse.AnimeTitle();
        s1Title.setEnglish("Teekyuu");
        seasonOne.setTitle(s1Title);
        seasonOne.setRelations(List.of());

        when(aniListService.getAnimeByIdLocalOnly(930)).thenReturn(seasonThree);
        when(aniListService.getAnimeByIdLocalOnly(910)).thenReturn(seasonOne);
        when(relationGraphRepository.findAnimeIdsHavingRelationType(eq(List.of(930)), any()))
                .thenReturn(java.util.Set.of(930));
        when(relationGraphRepository.resolveEntrypoint(eq(930), any(), anyInt())).thenReturn(910);

        RecommendationResponse row = new RecommendationResponse(
                seasonThree,
                0.61d,
                List.of(RecommendationResponse.MATCHES_QUERY));

        Object controls = invokePrivate(
                "resolveRecommendationControls",
                new Class<?>[] { SemanticRequest.Filters.class },
                new Object[] { null });
        Method method = SemanticRecommendationService.class.getDeclaredMethod(
                "applyRecommendationControls",
                List.class,
                controls.getClass(),
                String.class,
                int.class,
                Map.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<RecommendationResponse> filtered = (List<RecommendationResponse>) method.invoke(
                service,
                List.of(row),
                controls,
                "semantic",
                10,
                Map.of());

        assertEquals(1, filtered.size());
        assertEquals(910, filtered.get(0).getAnime().getId());
    }

    @Test
    void applyRecommendationControls_doesNotRemapByPayloadRelationsWhenGraphMissing() throws Exception {
        AniListResponse.AnimeInfo seasonThree = new AniListResponse.AnimeInfo();
        seasonThree.setId(931);
        seasonThree.setFormat("TV");
        AniListResponse.AnimeTitle s3Title = new AniListResponse.AnimeTitle();
        s3Title.setEnglish("Payload-only sequel");
        seasonThree.setTitle(s3Title);
        AniListResponse.AnimeRelation s3Prequel = new AniListResponse.AnimeRelation();
        s3Prequel.setId(930);
        s3Prequel.setRelationType("PREQUEL");
        seasonThree.setRelations(List.of(s3Prequel));

        when(relationGraphRepository.findAnimeIdsHavingRelationType(eq(List.of(931)), any()))
                .thenReturn(java.util.Set.of());

        RecommendationResponse row = new RecommendationResponse(
                seasonThree,
                0.61d,
                List.of(RecommendationResponse.MATCHES_QUERY));

        Object controls = invokePrivate(
                "resolveRecommendationControls",
                new Class<?>[] { SemanticRequest.Filters.class },
                new Object[] { null });
        Method method = SemanticRecommendationService.class.getDeclaredMethod(
                "applyRecommendationControls",
                List.class,
                controls.getClass(),
                String.class,
                int.class,
                Map.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<RecommendationResponse> filtered = (List<RecommendationResponse>) method.invoke(
                service,
                List.of(row),
                controls,
                "semantic",
                10,
                Map.of());

        assertEquals(1, filtered.size());
        assertEquals(931, filtered.get(0).getAnime().getId());
    }

    @Test
    void applyRecommendationControls_popularityAttenuationUsesAverageScoreFallbackWhenPopularityMissing() throws Exception {
        setField(service, "popularityAttenuationLow", 0.00f);
        setField(service, "popularityAttenuationMedium", 0.08f);
        setField(service, "popularityAttenuationHigh", 0.16f);

        AniListResponse.AnimeInfo higherScore = new AniListResponse.AnimeInfo();
        higherScore.setId(1001);
        higherScore.setAverageScore(90);
        higherScore.setPopularity(null);

        AniListResponse.AnimeInfo lowerScore = new AniListResponse.AnimeInfo();
        lowerScore.setId(1002);
        lowerScore.setAverageScore(60);
        lowerScore.setPopularity(null);

        RecommendationResponse higherScoreRow = new RecommendationResponse(
                higherScore,
                0.50d,
                List.of(RecommendationResponse.MATCHES_QUERY));
        RecommendationResponse lowerScoreRow = new RecommendationResponse(
                lowerScore,
                0.50d,
                List.of(RecommendationResponse.MATCHES_QUERY));

        SemanticRequest.Filters requestedFilters = new SemanticRequest.Filters();
        requestedFilters.setPopularityAttenuation("high");

        Object controls = invokePrivate(
                "resolveRecommendationControls",
                new Class<?>[] { SemanticRequest.Filters.class },
                requestedFilters);

        Method method = SemanticRecommendationService.class.getDeclaredMethod(
                "applyRecommendationControls",
                List.class,
                controls.getClass(),
                String.class,
                int.class,
                Map.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<RecommendationResponse> filtered = (List<RecommendationResponse>) method.invoke(
                service,
                List.of(higherScoreRow, lowerScoreRow),
                controls,
                "semantic",
                10,
                Map.of());

        assertEquals(2, filtered.size());
        assertEquals(1002, filtered.get(0).getAnime().getId());
        assertTrue(numberValue(filtered.get(0).getFusionScore(), 0.0d)
                > numberValue(filtered.get(1).getFusionScore(), 0.0d));
    }

    @Test
    void applyRecommendationControls_popularityAttenuationHighDownweightsPopularAndBoostsNiche() throws Exception {
        setField(service, "popularityAttenuationLow", 0.00f);
        setField(service, "popularityAttenuationMedium", 0.08f);
        setField(service, "popularityAttenuationHigh", 0.16f);

        AniListResponse.AnimeInfo highlyPopular = new AniListResponse.AnimeInfo();
        highlyPopular.setId(1000);
        highlyPopular.setPopularity(1_500_000);

        AniListResponse.AnimeInfo niche = new AniListResponse.AnimeInfo();
        niche.setId(2000);
        niche.setPopularity(2_000);

        RecommendationResponse highlyPopularRow = new RecommendationResponse(
                highlyPopular,
                0.65d,
                List.of(RecommendationResponse.MATCHES_QUERY));
        RecommendationResponse nicheRow = new RecommendationResponse(
                niche,
                0.65d,
                List.of(RecommendationResponse.MATCHES_QUERY));

        SemanticRequest.Filters lowFilters = new SemanticRequest.Filters();
        lowFilters.setPopularityAttenuation("low");
        SemanticRequest.Filters highFilters = new SemanticRequest.Filters();
        highFilters.setPopularityAttenuation("high");

        Object lowControls = invokePrivate(
                "resolveRecommendationControls",
                new Class<?>[] { SemanticRequest.Filters.class },
                lowFilters);
        Object highControls = invokePrivate(
                "resolveRecommendationControls",
                new Class<?>[] { SemanticRequest.Filters.class },
                highFilters);

        Method method = SemanticRecommendationService.class.getDeclaredMethod(
                "applyRecommendationControls",
                List.class,
                lowControls.getClass(),
                String.class,
                int.class,
                Map.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<RecommendationResponse> low = (List<RecommendationResponse>) method.invoke(
                service,
                List.of(highlyPopularRow, nicheRow),
                lowControls,
                "semantic",
                10,
                Map.of());

        @SuppressWarnings("unchecked")
        List<RecommendationResponse> high = (List<RecommendationResponse>) method.invoke(
                service,
                List.of(highlyPopularRow, nicheRow),
                highControls,
                "semantic",
                10,
                Map.of());

        assertEquals(1000, low.get(0).getAnime().getId());
        assertEquals(2000, high.get(0).getAnime().getId());
        assertTrue(numberValue(high.get(0).getFusionScore(), 0.0d)
                > numberValue(high.get(1).getFusionScore(), 0.0d));
    }

    @Test
    void applyRecommendationControls_appliesFeedbackScoreAdjustmentsAcrossModes() throws Exception {
        com.animetracker.dto.AniListResponse.AnimeInfo downAnime = new com.animetracker.dto.AniListResponse.AnimeInfo();
        downAnime.setId(901);
        com.animetracker.dto.AniListResponse.AnimeInfo upAnime = new com.animetracker.dto.AniListResponse.AnimeInfo();
        upAnime.setId(902);

        com.animetracker.dto.RecommendationResponse downRow = new com.animetracker.dto.RecommendationResponse(
                downAnime,
                0.50d,
                List.of(com.animetracker.dto.RecommendationResponse.MATCHES_QUERY));
        com.animetracker.dto.RecommendationResponse upRow = new com.animetracker.dto.RecommendationResponse(
                upAnime,
                0.50d,
                List.of(com.animetracker.dto.RecommendationResponse.MATCHES_QUERY));

        Object controls = invokePrivate(
                "resolveRecommendationControls",
                new Class<?>[] { SemanticRequest.Filters.class },
                new Object[] { null });

        Method method = SemanticRecommendationService.class.getDeclaredMethod(
                "applyRecommendationControls",
                List.class,
                controls.getClass(),
                String.class,
                int.class,
                Map.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<com.animetracker.dto.RecommendationResponse> adjusted =
                (List<com.animetracker.dto.RecommendationResponse>) method.invoke(
                        service,
                        List.of(downRow, upRow),
                        controls,
                        "semantic",
                        10,
                        Map.of(
                                901, RecommendationFeedback.SIGNAL_THUMBS_DOWN,
                                902, RecommendationFeedback.SIGNAL_THUMBS_UP));

        assertEquals(2, adjusted.size());
        assertEquals(902, adjusted.get(0).getAnime().getId());
        assertEquals(901, adjusted.get(1).getAnime().getId());
        assertTrue(numberValue(adjusted.get(0).getFusionScore(), 0.0d)
                > numberValue(adjusted.get(1).getFusionScore(), 0.0d));
    }

    private double numberValue(Number value, double fallback) {
        return value == null ? fallback : value.doubleValue();
    }

    private Object invokePrivate(String methodName, Class<?>[] argTypes, Object... args) throws Exception {
        Method method = SemanticRecommendationService.class.getDeclaredMethod(methodName, argTypes);
        method.setAccessible(true);
        return method.invoke(service, args);
    }

    private FusionScoringService.ScoredCandidate scoredCandidate(int animeId, double score) {
        com.animetracker.dto.AniListResponse.AnimeInfo anime = new com.animetracker.dto.AniListResponse.AnimeInfo();
        anime.setId(animeId);
        return new FusionScoringService.ScoredCandidate(
                animeId,
                anime,
                score,
                List.of(com.animetracker.dto.RecommendationResponse.MATCHES_QUERY));
    }

    private FusionScoringService.ScoredCandidate scoredCandidateWithMetadata(
            int animeId,
            double score,
            double queryRelevance,
            double popularityPrior,
            Integer averageScore,
            Integer popularity,
            List<String> genres) {
        com.animetracker.dto.AniListResponse.AnimeInfo anime = new com.animetracker.dto.AniListResponse.AnimeInfo();
        anime.setId(animeId);
        anime.setQueryRelevanceScore(queryRelevance);
        anime.setPopularityPriorScore(popularityPrior);
        anime.setAverageScore(averageScore);
        anime.setPopularity(popularity);
        anime.setGenres(genres);
        return new FusionScoringService.ScoredCandidate(
                animeId,
                anime,
                score,
                List.of(com.animetracker.dto.RecommendationResponse.MATCHES_QUERY));
    }

    private Object[] sampleSemanticRow(int anilistId, String title, double distance) {
        return new Object[] {
                999L,
                anilistId,
                title,
                title,
                "https://img.test/" + anilistId + ".jpg",
                "Comedy, Romance",
                "Description",
                80,
                "FINISHED",
                12,
                250000,
                "embedding text",
                null,
                null,
                distance
        };
    }

    private Object[] sampleSemanticRowWithStatus(int anilistId, String title, String status, double distance) {
        Object[] row = sampleSemanticRow(anilistId, title, distance);
        row[8] = status;
        return row;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
