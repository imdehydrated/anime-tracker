package com.animetracker.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.animetracker.dto.AniListResponse;
import com.animetracker.dto.RecommendationResponse;
import com.animetracker.dto.RecommendationBlacklistRequest;
import com.animetracker.dto.SemanticRequest;
import com.animetracker.exception.UnauthorizedException;
import com.animetracker.service.CustomEmbeddingImportService;
import com.animetracker.service.SemanticRecommendationService;

import jakarta.validation.Valid;

/**
 * Recommendation endpoints:
 * - semantic recommendations
 * - recommendation blacklist management
 */
@RestController
@RequestMapping("/api/users/recommendations")
public class RecommendationController {

    private final SemanticRecommendationService semanticService;
    private final CustomEmbeddingImportService customEmbeddingImportService;

    public RecommendationController(
            SemanticRecommendationService semanticService,
            CustomEmbeddingImportService customEmbeddingImportService) {
        this.semanticService = semanticService;
        this.customEmbeddingImportService = customEmbeddingImportService;
    }

    @PostMapping("/semantic")
    public ResponseEntity<List<AniListResponse.AnimeInfo>> getSemanticRecommendations(
            @Valid @RequestBody SemanticRequest request) {
        String username = getCurrentUsernameOrNull();
        List<RecommendationResponse> scored = semanticService.recommend(
                username,
                request.getSeedIds(),
                request.getQuery(),
                request.getLimit(),
                Boolean.TRUE.equals(request.getUseListOnly()),
                request.getListWeight(),
                request.getMode());
        List<AniListResponse.AnimeInfo> legacy = scored.stream()
                .map(RecommendationResponse::getAnime)
                .toList();
        return ResponseEntity.ok(legacy);
    }

    @PostMapping("/semantic/scored")
    public ResponseEntity<List<RecommendationResponse>> getSemanticRecommendationsScored(
            @Valid @RequestBody SemanticRequest request) {
        String username = getCurrentUsernameOrNull();
        List<RecommendationResponse> results = semanticService.recommend(
                username,
                request.getSeedIds(),
                request.getQuery(),
                request.getLimit(),
                Boolean.TRUE.equals(request.getUseListOnly()),
                request.getListWeight(),
                request.getMode());
        return ResponseEntity.ok(results);
    }

    @PostMapping("/blacklist")
    public ResponseEntity<Map<String, String>> blacklistAnime(@Valid @RequestBody RecommendationBlacklistRequest request) {
        semanticService.blacklistAnime(
                getCurrentUsernameRequired(),
                request.anilistId(),
                request.title(),
                request.coverImage());
        return ResponseEntity.ok(Map.of("message", "Anime hidden from recommendations"));
    }

    @GetMapping("/blacklist")
    public ResponseEntity<List<Map<String, Object>>> getBlacklist() {
        return ResponseEntity.ok(semanticService.getBlacklist(getCurrentUsernameRequired()));
    }

    @DeleteMapping("/blacklist/{id}")
    public ResponseEntity<Map<String, String>> removeFromBlacklist(@PathVariable Long id) {
        semanticService.removeFromBlacklist(getCurrentUsernameRequired(), id);
        return ResponseEntity.ok(Map.of("message", "Removed from blacklist"));
    }

    /**
     * Manual import endpoint for custom 384-dim embeddings.
     * Requires authentication. Uses default path unless overridden by request param.
     */
    @PostMapping("/custom-embeddings/import")
    public ResponseEntity<Map<String, Object>> importCustomEmbeddings(
            @RequestParam(required = false) String path) {
        getCurrentUsernameRequired();

        CustomEmbeddingImportService.ImportStats stats = (path == null || path.isBlank())
                ? customEmbeddingImportService.importFromDefaultPath()
                : customEmbeddingImportService.importFromPath(path);

        return ResponseEntity.ok(Map.of(
                "message", "Custom embeddings import completed",
                "path", stats.path(),
                "processed", stats.processed(),
                "imported", stats.imported(),
                "failed", stats.failed(),
                "totalCustomEmbeddings", stats.totalCustomEmbeddings(),
                "scoreCoverage", stats.scoreCoverage(),
                "popularityCoverage", stats.popularityCoverage(),
                "tagCoverage", stats.tagCoverage(),
                "aliasCoverage", stats.aliasCoverage()));
    }

    /**
     * Manual population endpoint for active-catalog embeddings.
     * Requires authentication and calls AniList full-catalog paging with active format filters.
     */
    @PostMapping("/custom-embeddings/populate-active-catalog")
    public ResponseEntity<Map<String, Object>> populateActiveCatalogEmbeddings(
            @RequestParam(defaultValue = "200") int maxPages,
            @RequestParam(defaultValue = "50") int perPage) {
        getCurrentUsernameRequired();
        Map<String, Object> stats = semanticService.populateActiveCatalogEmbeddings(maxPages, perPage);
        return ResponseEntity.ok(Map.of(
                "message", "Active catalog embedding population completed",
                "stats", stats));
    }

    private String getCurrentUsernameOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return null;
        }
        return auth.getName();
    }

    private String getCurrentUsernameRequired() {
        String username = getCurrentUsernameOrNull();
        if (username == null) {
            throw new UnauthorizedException("Authentication required");
        }
        return username;
    }
}
