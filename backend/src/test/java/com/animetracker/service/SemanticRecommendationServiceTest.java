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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
import com.animetracker.exception.BadRequestException;
import com.animetracker.exception.UnauthorizedException;
import com.animetracker.dto.AniListResponse;
import com.animetracker.dto.SemanticRequest;
import com.animetracker.entity.AnimeListEntry;
import com.animetracker.entity.RecommendationFeedback;
import com.animetracker.entity.User;
import com.animetracker.repository.AnimeEmbeddingRepository;
import com.animetracker.repository.CustomEmbeddingImportStateRepository;
import com.animetracker.repository.RecommendationFeedbackRepository;
import com.animetracker.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class SemanticRecommendationServiceTest {
    private static final double EPS = 1e-6;

    @Mock
    private AnimeEmbeddingRepository embeddingRepository;
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

    private SemanticRecommendationService service;

    @BeforeEach
    void setUp() {
        service = new SemanticRecommendationService(
                embeddingRepository,
                animeListEntryService,
                feedbackRepository,
                userRepository,
                aniListService,
                populatorService,
                mlSidecarService,
                customEmbeddingImportStateRepository);
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
    void isExtraSeasonCandidate_detectsOrdinalSeasonPattern() throws Exception {
        AniListResponse.AnimeInfo anime = new AniListResponse.AnimeInfo();
        AniListResponse.AnimeTitle title = new AniListResponse.AnimeTitle();
        title.setEnglish("Haikyuu!! 2nd Season");
        anime.setTitle(title);

        boolean flagged = (boolean) invokePrivate(
                "isExtraSeasonCandidate",
                new Class<?>[] { AniListResponse.AnimeInfo.class },
                anime);
        assertTrue(flagged);
    }

    @Test
    void isExtraSeasonCandidate_doesNotFlagBaseTitle() throws Exception {
        AniListResponse.AnimeInfo anime = new AniListResponse.AnimeInfo();
        AniListResponse.AnimeTitle title = new AniListResponse.AnimeTitle();
        title.setEnglish("Haikyuu!!");
        anime.setTitle(title);

        boolean flagged = (boolean) invokePrivate(
                "isExtraSeasonCandidate",
                new Class<?>[] { AniListResponse.AnimeInfo.class },
                anime);
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
    void buildExcludeIds_includesThumbsDownFeedback() throws Exception {
        User user = new User();
        user.setId(22L);
        user.setUsername("alice");

        AnimeListEntry listEntry = new AnimeListEntry();
        listEntry.setAnilistId(111);

        RecommendationFeedback down = new RecommendationFeedback(
                user,
                222,
                RecommendationFeedback.SIGNAL_THUMBS_DOWN,
                "semantic",
                null,
                "Disliked Show",
                null);

        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(animeListEntryService.getUserList("alice")).thenReturn(List.of(listEntry));
        when(feedbackRepository.findByUserAndSignal(user, RecommendationFeedback.SIGNAL_THUMBS_DOWN))
                .thenReturn(List.of(down));

        @SuppressWarnings("unchecked")
        List<Integer> excluded = (List<Integer>) invokePrivate(
                "buildExcludeIds",
                new Class<?>[] { String.class, List.class },
                "alice",
                List.of(333));

        assertTrue(excluded.contains(333));
        assertTrue(excluded.contains(111));
        assertTrue(excluded.contains(222));
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

        assertTrue(filtered.isEmpty());
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

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
