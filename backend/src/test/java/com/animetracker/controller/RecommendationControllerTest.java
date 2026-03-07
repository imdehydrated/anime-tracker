package com.animetracker.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.lang.reflect.Field;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.animetracker.dto.AniListResponse;
import com.animetracker.dto.RecommendationPageResponse;
import com.animetracker.dto.RecommendationResponse;
import com.animetracker.dto.RecommendationFeedbackRequest;
import com.animetracker.dto.SemanticRequest;
import com.animetracker.exception.NotFoundException;
import com.animetracker.exception.UnauthorizedException;
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
                nullable(String.class),
                nullable(SemanticRequest.Filters.class)))
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
                nullable(String.class),
                nullable(SemanticRequest.Filters.class));
    }

    @Test
    void semanticScoredPagedEndpoint_returnsPagedShape() {
        RecommendationController controller = new RecommendationController(semanticService, customEmbeddingImportService);
        SemanticRequest request = new SemanticRequest();
        request.setMode("semantic");
        request.setLimit(20);
        request.setPageSize(10);
        request.setCursor(null);

        AniListResponse.AnimeInfo anime = animeInfo(303);
        RecommendationResponse scored = new RecommendationResponse(anime, 0.74, List.of(RecommendationResponse.MATCHES_QUERY));
        RecommendationPageResponse page = new RecommendationPageResponse(
                List.of(scored),
                "next-cursor",
                true,
                Map.of("offset", 0));
        when(semanticService.recommendPaged(
                isNull(),
                nullable(List.class),
                nullable(String.class),
                nullable(Integer.class),
                anyBoolean(),
                nullable(Float.class),
                nullable(String.class),
                nullable(SemanticRequest.Filters.class),
                nullable(String.class),
                nullable(Integer.class)))
                .thenReturn(page);

        ResponseEntity<RecommendationPageResponse> response = controller.getSemanticRecommendationsScoredPaged(request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().getItems().size());
        assertEquals(303, response.getBody().getItems().get(0).getAnime().getId());
        assertEquals("next-cursor", response.getBody().getNextCursor());
        assertEquals(true, response.getBody().isHasMore());
        verify(semanticService).recommendPaged(
                isNull(),
                nullable(List.class),
                nullable(String.class),
                nullable(Integer.class),
                anyBoolean(),
                nullable(Float.class),
                nullable(String.class),
                nullable(SemanticRequest.Filters.class),
                nullable(String.class),
                nullable(Integer.class));
    }

    @Test
    void populateFullCatalogEmbeddings_blockedWhenOpsDisabled() {
        RecommendationController controller = new RecommendationController(semanticService, customEmbeddingImportService);
        assertThrows(
                NotFoundException.class,
                () -> controller.populateFullCatalogEmbeddings(10, 50));
    }

    @Test
    void populateFullCatalogEmbeddings_returnsStatsWhenAuthenticated() {
        RecommendationController controller = new RecommendationController(semanticService, customEmbeddingImportService);
        enableManualOpsEndpoints(controller);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("tester", "pw", "ROLE_USER"));

        Map<String, Object> stats = Map.of("embedded", 100, "failed", 0);
        when(semanticService.populateFullCatalogEmbeddings(10, 50)).thenReturn(stats);

        ResponseEntity<Map<String, Object>> response = controller.populateFullCatalogEmbeddings(10, 50);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("Full catalog embedding population completed", response.getBody().get("message"));
        verify(semanticService).populateFullCatalogEmbeddings(10, 50);
    }

    @Test
    void populationFailureReport_blockedWhenOpsDisabled() {
        RecommendationController controller = new RecommendationController(semanticService, customEmbeddingImportService);
        assertThrows(
                NotFoundException.class,
                () -> controller.getPopulationFailures(null, null, 100));
    }

    @Test
    void populationFailureReport_returnsStatsWhenAuthenticated() {
        RecommendationController controller = new RecommendationController(semanticService, customEmbeddingImportService);
        enableManualOpsEndpoints(controller);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("tester", "pw", "ROLE_USER"));

        Map<String, Object> stats = Map.of("summary", Map.of("open", 3));
        when(semanticService.getPopulationFailureReport(null, "OPEN", 100)).thenReturn(stats);

        ResponseEntity<Map<String, Object>> response = controller.getPopulationFailures(null, "OPEN", 100);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("Population failure report", response.getBody().get("message"));
        verify(semanticService).getPopulationFailureReport(null, "OPEN", 100);
    }

    @Test
    void retryPopulationFailures_blockedWhenOpsDisabled() {
        RecommendationController controller = new RecommendationController(semanticService, customEmbeddingImportService);
        assertThrows(
                NotFoundException.class,
                () -> controller.retryPopulationFailures("active_catalog", 20));
    }

    @Test
    void retryPopulationFailures_returnsStatsWhenAuthenticated() {
        RecommendationController controller = new RecommendationController(semanticService, customEmbeddingImportService);
        enableManualOpsEndpoints(controller);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("tester", "pw", "ROLE_USER"));

        Map<String, Object> stats = Map.of("attempted", 5, "recovered", 4);
        when(semanticService.retryPopulationFailures("active_catalog", 20)).thenReturn(stats);

        ResponseEntity<Map<String, Object>> response = controller.retryPopulationFailures("active_catalog", 20);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("Population failure retry completed", response.getBody().get("message"));
        verify(semanticService).retryPopulationFailures("active_catalog", 20);
    }

    @Test
    void rebuildRelationGraph_blockedWhenOpsDisabled() {
        RecommendationController controller = new RecommendationController(semanticService, customEmbeddingImportService);
        assertThrows(
                NotFoundException.class,
                controller::rebuildRelationGraph);
    }

    @Test
    void rebuildRelationGraph_returnsStatsWhenAuthenticated() {
        RecommendationController controller = new RecommendationController(semanticService, customEmbeddingImportService);
        enableManualOpsEndpoints(controller);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("tester", "pw", "ROLE_USER"));

        Map<String, Object> stats = Map.of("edgesBefore", 1000, "edgesAfter", 2000, "inserted", 2000, "animeWithEdges", 800);
        when(semanticService.rebuildRelationGraphFromCatalog()).thenReturn(stats);

        ResponseEntity<Map<String, Object>> response = controller.rebuildRelationGraph();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("Relation graph rebuild completed", response.getBody().get("message"));
        verify(semanticService).rebuildRelationGraphFromCatalog();
    }

    @Test
    void feedbackEndpoints_requireAuth() {
        RecommendationController controller = new RecommendationController(semanticService, customEmbeddingImportService);
        RecommendationFeedbackRequest request = new RecommendationFeedbackRequest(
                16498,
                "thumbs_down",
                "semantic",
                "sports anime",
                "Haikyuu!!",
                "https://img.test/haikyuu.jpg");

        assertThrows(UnauthorizedException.class, () -> controller.recordFeedback(request));
        assertThrows(UnauthorizedException.class, controller::getFeedback);
        assertThrows(UnauthorizedException.class, () -> controller.removeFeedback(1L));
    }

    @Test
    void feedbackEndpoints_forwardToServiceWhenAuthenticated() {
        RecommendationController controller = new RecommendationController(semanticService, customEmbeddingImportService);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("tester", "pw", "ROLE_USER"));

        RecommendationFeedbackRequest request = new RecommendationFeedbackRequest(
                16498,
                "thumbs_up",
                "similar",
                null,
                "Haikyuu!!",
                "https://img.test/haikyuu.jpg");

        when(semanticService.getFeedback("tester")).thenReturn(List.of(Map.of("id", 1L, "signal", "THUMBS_UP")));

        ResponseEntity<Map<String, String>> post = controller.recordFeedback(request);
        ResponseEntity<List<Map<String, Object>>> get = controller.getFeedback();
        ResponseEntity<Map<String, String>> delete = controller.removeFeedback(1L);

        assertEquals(200, post.getStatusCode().value());
        assertEquals("Feedback recorded", post.getBody().get("message"));
        assertEquals(200, get.getStatusCode().value());
        assertEquals(1, get.getBody().size());
        assertEquals(200, delete.getStatusCode().value());
        assertEquals("Feedback removed", delete.getBody().get("message"));

        verify(semanticService).recordFeedback("tester", request);
        verify(semanticService).getFeedback("tester");
        verify(semanticService).removeFeedback("tester", 1L);
    }

    private AniListResponse.AnimeInfo animeInfo(int anilistId) {
        AniListResponse.AnimeInfo anime = new AniListResponse.AnimeInfo();
        anime.setId(anilistId);
        AniListResponse.AnimeTitle title = new AniListResponse.AnimeTitle();
        title.setRomaji("Anime " + anilistId);
        anime.setTitle(title);
        return anime;
    }

    private void enableManualOpsEndpoints(RecommendationController controller) {
        try {
            Field field = RecommendationController.class.getDeclaredField("manualOpsEndpointsEnabled");
            field.setAccessible(true);
            field.set(controller, true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
