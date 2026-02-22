package com.animetracker.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.animetracker.exception.BadRequestException;
import com.animetracker.exception.UnauthorizedException;
import com.animetracker.repository.AnimeEmbeddingRepository;
import com.animetracker.repository.RecommendationBlacklistRepository;
import com.animetracker.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class SemanticRecommendationServiceTest {

    @Mock
    private EmbeddingService embeddingService;
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
                embeddingService,
                embeddingRepository,
                animeListEntryService,
                blacklistRepository,
                userRepository,
                aniListService,
                populatorService,
                mlSidecarService,
                fusionScoringService);
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
        when(embeddingService.embed(anyString())).thenReturn(new float[] { 0.1f, 0.2f, 0.3f });
        when(embeddingRepository.findSimilar(anyString(), anyList(), anyInt())).thenReturn(List.of());

        assertDoesNotThrow(() -> service.recommend(
                null,
                List.of(1, 2, 3),
                "dark thriller",
                15,
                false,
                null,
                "semantic"));

        verify(embeddingService).embed("dark thriller");
        verify(embeddingRepository, never()).findEmbeddingsByAnilistIds(anyList());
        verify(embeddingRepository).findSimilar(anyString(), anyList(), anyInt());
    }

    @Test
    void recommend_similarModeWithoutSeeds_throwsBadRequest() {
        assertThrows(BadRequestException.class,
                () -> service.recommend(null, List.of(), "any query", 15, false, null, "similar"));
    }
}
