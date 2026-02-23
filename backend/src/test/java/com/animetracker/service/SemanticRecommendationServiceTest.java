package com.animetracker.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.animetracker.exception.BadRequestException;
import com.animetracker.exception.UnauthorizedException;
import com.animetracker.entity.AnimeListEntry;
import com.animetracker.entity.User;
import com.animetracker.repository.AnimeEmbeddingRepository;
import com.animetracker.repository.RecommendationBlacklistRepository;
import com.animetracker.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class SemanticRecommendationServiceTest {
    private static final double EPS = 1e-6;

    @Mock
    private AnimeEmbeddingRepository embeddingRepository;
    @Mock
    private AnimeListEntryService animeListEntryService;
    @Mock
    private RecommendationBlacklistRepository blacklistRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AniListService aniListService;
    @Mock
    private AnimeEmbeddingPopulatorService populatorService;
    @Mock
    private MlSidecarService mlSidecarService;
    @Mock
    private FusionScoringService fusionScoringService;

    private SemanticRecommendationService service;

    @BeforeEach
    void setUp() {
        service = new SemanticRecommendationService(
                embeddingRepository,
                animeListEntryService,
                blacklistRepository,
                userRepository,
                aniListService,
                populatorService,
                mlSidecarService,
                fusionScoringService);
        assertDoesNotThrow(() -> setField(service, "useCustomVectors", true));
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
        when(blacklistRepository.findByUser(user)).thenReturn(List.of());
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
                12
        }));

        List<?> results = service.recommend("alice", List.of(), null, 10, false, null, "cf");

        verify(aniListService, never()).getAnimeById(123);
        org.junit.jupiter.api.Assertions.assertEquals(1, results.size());
    }

    @Test
    void resolveDynamicCfBlendWeight_requestedWeightOverridesAll() throws Exception {
        setField(service, "dynamicBlendEnabled", true);
        setField(service, "dynamicBlendMinRatedAnime", 10);
        setField(service, "dynamicBlendMaxRatedAnime", 80);
        setField(service, "dynamicBlendMinCfWeight", 0.15f);
        setField(service, "dynamicBlendMaxCfWeight", 0.55f);

        double weight = invokeResolveDynamicCfBlendWeight(0.72f, 0.20f, 999);
        assertEquals(0.72d, weight, EPS);
    }

    @Test
    void resolveDynamicCfBlendWeight_dynamicDisabledUsesResolvedWeight() throws Exception {
        setField(service, "dynamicBlendEnabled", false);

        double weight = invokeResolveDynamicCfBlendWeight(null, 0.33f, 50);
        assertEquals(0.33d, weight, EPS);
    }

    @Test
    void resolveDynamicCfBlendWeight_interpolatesByRatedAnimeCount() throws Exception {
        setField(service, "dynamicBlendEnabled", true);
        setField(service, "dynamicBlendMinRatedAnime", 10);
        setField(service, "dynamicBlendMaxRatedAnime", 80);
        setField(service, "dynamicBlendMinCfWeight", 0.15f);
        setField(service, "dynamicBlendMaxCfWeight", 0.55f);

        // Midpoint between 10 and 80 => midpoint between 0.15 and 0.55.
        double midWeight = invokeResolveDynamicCfBlendWeight(null, 0.20f, 45);
        assertEquals(0.35d, midWeight, EPS);

        double lowWeight = invokeResolveDynamicCfBlendWeight(null, 0.20f, 0);
        assertEquals(0.15d, lowWeight, EPS);

        double highWeight = invokeResolveDynamicCfBlendWeight(null, 0.20f, 500);
        assertEquals(0.55d, highWeight, EPS);
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

    private double invokeResolveDynamicCfBlendWeight(
            Float requestedListWeight,
            float resolvedListWeight,
            int ratedAnimeCount) throws Exception {
        Method method = SemanticRecommendationService.class.getDeclaredMethod(
                "resolveDynamicCfBlendWeight",
                Float.class,
                float.class,
                int.class);
        method.setAccessible(true);
        return (double) method.invoke(service, requestedListWeight, resolvedListWeight, ratedAnimeCount);
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
