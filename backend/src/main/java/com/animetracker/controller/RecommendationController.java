package com.animetracker.controller;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.animetracker.dto.RecommendationPageResponse;
import com.animetracker.dto.RecommendationResponse;
import com.animetracker.dto.RecommendationFeedbackRequest;
import com.animetracker.dto.SemanticRequest;
import com.animetracker.exception.NotFoundException;
import com.animetracker.exception.UnauthorizedException;
import com.animetracker.service.CustomEmbeddingImportService;
import com.animetracker.service.SemanticRecommendationService;

import jakarta.validation.Valid;

/**
 * Recommendation endpoints:
 * - semantic recommendations
 * - recommendation feedback
 */
@RestController
@RequestMapping("/api/users/recommendations")
public class RecommendationController {
    private static final Logger log = LoggerFactory.getLogger(RecommendationController.class);
    private final SemanticRecommendationService semanticService;
    private final CustomEmbeddingImportService customEmbeddingImportService;
    @Value("${recommendations.ops.manual-endpoints-enabled:false}")
    private boolean manualOpsEndpointsEnabled;
    @Value("${recommendations.ops.token:}")
    private String opsToken;

    public RecommendationController(
            SemanticRecommendationService semanticService,
            CustomEmbeddingImportService customEmbeddingImportService) {
        this.semanticService = semanticService;
        this.customEmbeddingImportService = customEmbeddingImportService;
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
                request.getMode(),
                request.getFilters());
        return ResponseEntity.ok(results);
    }

    @PostMapping("/semantic/scored/paged")
    public ResponseEntity<RecommendationPageResponse> getSemanticRecommendationsScoredPaged(
            @Valid @RequestBody SemanticRequest request) {
        String username = getCurrentUsernameOrNull();
        RecommendationPageResponse page = semanticService.recommendPaged(
                username,
                request.getSeedIds(),
                request.getQuery(),
                request.getLimit(),
                Boolean.TRUE.equals(request.getUseListOnly()),
                request.getListWeight(),
                request.getMode(),
                request.getFilters(),
                request.getCursor(),
                request.getPageSize());
        return ResponseEntity.ok(page);
    }

    @PostMapping("/feedback")
    public ResponseEntity<Map<String, String>> recordFeedback(
            @Valid @RequestBody RecommendationFeedbackRequest request) {
        semanticService.recordFeedback(getCurrentUsernameRequired(), request);
        return ResponseEntity.ok(Map.of("message", "Feedback recorded"));
    }

    @GetMapping("/feedback")
    public ResponseEntity<List<Map<String, Object>>> getFeedback() {
        return ResponseEntity.ok(semanticService.getFeedback(getCurrentUsernameRequired()));
    }

    @DeleteMapping("/feedback/{id}")
    public ResponseEntity<Map<String, String>> removeFeedback(@PathVariable Long id) {
        semanticService.removeFeedback(getCurrentUsernameRequired(), id);
        return ResponseEntity.ok(Map.of("message", "Feedback removed"));
    }

    /**
     * Manual import endpoint for custom 384-dim embeddings.
     * Requires manual ops access. Uses default path unless overridden by request param.
     */
    @PostMapping("/custom-embeddings/import")
    public ResponseEntity<Map<String, Object>> importCustomEmbeddings(
            @RequestParam(required = false) String path) {
        ensureManualOpsAccess();

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
     * Manual population endpoint (legacy alias) for catalog embeddings.
     * Requires manual ops access and uses shared full-catalog cursor state.
     */
    @PostMapping("/custom-embeddings/populate-active-catalog")
    public ResponseEntity<Map<String, Object>> populateActiveCatalogEmbeddings(
            @RequestParam(defaultValue = "3000") int maxPages,
            @RequestParam(defaultValue = "10") int perPage) {
        ensureManualOpsAccess();
        Map<String, Object> stats = semanticService.populateActiveCatalogEmbeddings(maxPages, perPage);
        return ResponseEntity.ok(Map.of(
                "message", "Catalog embedding population completed",
                "stats", stats));
    }

    /**
     * Manual population endpoint for full-catalog embeddings.
     * Requires manual ops access and uses shared full-catalog cursor state.
     */
    @PostMapping("/custom-embeddings/populate-full-catalog")
    public ResponseEntity<Map<String, Object>> populateFullCatalogEmbeddings(
            @RequestParam(defaultValue = "3000") int maxPages,
            @RequestParam(defaultValue = "10") int perPage) {
        ensureManualOpsAccess();
        Map<String, Object> stats = semanticService.populateFullCatalogEmbeddings(maxPages, perPage);
        return ResponseEntity.ok(Map.of(
                "message", "Full catalog embedding population completed",
                "stats", stats));
    }

    /**
     * Inspect embedding population failures and retry backlog.
     * Requires manual ops access.
     */
    @GetMapping("/custom-embeddings/population-failures")
    public ResponseEntity<Map<String, Object>> getPopulationFailures(
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        ensureManualOpsAccess();
        Map<String, Object> report = semanticService.getPopulationFailureReport(source, status, limit);
        return ResponseEntity.ok(Map.of(
                "message", "Population failure report",
                "stats", report));
    }

    /**
     * Retry open embedding population failures due for retry.
     * Requires manual ops access.
     */
    @PostMapping("/custom-embeddings/population-failures/retry")
    public ResponseEntity<Map<String, Object>> retryPopulationFailures(
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "50") int limit) {
        ensureManualOpsAccess();
        Map<String, Object> stats = semanticService.retryPopulationFailures(source, limit);
        return ResponseEntity.ok(Map.of(
                "message", "Population failure retry completed",
                "stats", stats));
    }

    /**
     * Rebuild relation graph edges from local catalog metadata_json.
     * Requires manual ops access.
     */
    @PostMapping("/custom-embeddings/rebuild-relation-graph")
    public ResponseEntity<Map<String, Object>> rebuildRelationGraph() {
        ensureManualOpsAccess();
        Map<String, Object> stats = semanticService.rebuildRelationGraphFromCatalog();
        return ResponseEntity.ok(Map.of(
                "message", "Relation graph rebuild completed",
                "stats", stats));
    }

    private void ensureManualOpsAccess() {
        HttpServletRequest request = currentRequestOrNull();
        if (!manualOpsEndpointsEnabled) {
            log.warn(
                    "Denied manual ops request while endpoints disabled: path={} ip={}",
                    request == null ? "unknown" : request.getRequestURI(),
                    request == null ? "unknown" : request.getRemoteAddr());
            throw new NotFoundException("Not found");
        }
        if (opsToken == null || opsToken.isBlank()) {
            return;
        }
        String header = request == null ? null : request.getHeader("X-Ops-Token");
        if (header == null || !opsToken.equals(header)) {
            log.warn(
                    "Denied manual ops request due to invalid/missing token: path={} ip={}",
                    request == null ? "unknown" : request.getRequestURI(),
                    request == null ? "unknown" : request.getRemoteAddr());
            throw new UnauthorizedException("Invalid ops token");
        }
    }

    private HttpServletRequest currentRequestOrNull() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs == null ? null : attrs.getRequest();
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
