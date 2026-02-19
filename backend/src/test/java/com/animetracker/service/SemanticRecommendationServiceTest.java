package com.animetracker.service;

import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
