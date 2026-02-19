package com.animetracker.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;

import com.animetracker.dto.AniListResponse;
import com.animetracker.dto.RecommendationResponse;
import com.animetracker.dto.SemanticRequest;
import com.animetracker.service.CustomEmbeddingImportService;
import com.animetracker.service.SemanticRecommendationService;

@ExtendWith(MockitoExtension.class)
class RecommendationControllerTest {

    @Mock
    private SemanticRecommendationService semanticService;
    @Mock
    private CustomEmbeddingImportService customEmbeddingImportService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void semanticEndpoint_returnsLegacyAnimeShape() {
        RecommendationController controller = new RecommendationController(semanticService, customEmbeddingImportService);
        SemanticRequest request = new SemanticRequest();
        request.setMode("semantic");
        request.setLimit(5);

        AniListResponse.AnimeInfo anime = animeInfo(101);
        RecommendationResponse scored = new RecommendationResponse(anime, 0.82, List.of(RecommendationResponse.MATCHES_QUERY));
        when(semanticService.recommend(
                isNull(),
                nullable(List.class),
                nullable(String.class),
                anyInt(),
                anyBoolean(),
                nullable(Float.class),
                nullable(String.class)))
                .thenReturn(List.of(scored));

        ResponseEntity<List<AniListResponse.AnimeInfo>> response = controller.getSemanticRecommendations(request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals(101, response.getBody().get(0).getId());
        verify(semanticService).recommend(
                isNull(),
                nullable(List.class),
                nullable(String.class),
                anyInt(),
                anyBoolean(),
                nullable(Float.class),
                nullable(String.class));
    }

    @Test
    void semanticScoredEndpoint_returnsScoredShape() {
        RecommendationController controller = new RecommendationController(semanticService, customEmbeddingImportService);
        SemanticRequest request = new SemanticRequest();
        request.setMode("semantic");
        request.setLimit(5);

        AniListResponse.AnimeInfo anime = animeInfo(202);
        RecommendationResponse scored = new RecommendationResponse(anime, 0.67, List.of(RecommendationResponse.CF_SIGNAL));
        when(semanticService.recommend(
                isNull(),
                nullable(List.class),
                nullable(String.class),
                anyInt(),
                anyBoolean(),
                nullable(Float.class),
                nullable(String.class)))
                .thenReturn(List.of(scored));

        ResponseEntity<List<RecommendationResponse>> response = controller.getSemanticRecommendationsScored(request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals(202, response.getBody().get(0).getAnime().getId());
        assertEquals(0.67, response.getBody().get(0).getFusionScore());
        verify(semanticService).recommend(
                isNull(),
                nullable(List.class),
                nullable(String.class),
                anyInt(),
                anyBoolean(),
                nullable(Float.class),
                nullable(String.class));
    }

    private AniListResponse.AnimeInfo animeInfo(int anilistId) {
        AniListResponse.AnimeInfo anime = new AniListResponse.AnimeInfo();
        anime.setId(anilistId);
        AniListResponse.AnimeTitle title = new AniListResponse.AnimeTitle();
        title.setRomaji("Anime " + anilistId);
        anime.setTitle(title);
        return anime;
    }
}
