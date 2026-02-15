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
import org.springframework.web.bind.annotation.RestController;

import com.animetracker.dto.AniListResponse;
import com.animetracker.dto.SemanticRequest;
import com.animetracker.service.AnimeEmbeddingPopulatorService;
import com.animetracker.service.RecommendationService;
import com.animetracker.service.SemanticRecommendationService;

/**
 * REST Controller for anime recommendations.
 *
 * GET /api/users/recommendations — returns suggested anime based on the
 * logged-in user's list genres and scores. Requires JWT authentication.
 */
@RestController
@RequestMapping("/api/users/recommendations")
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final AnimeEmbeddingPopulatorService populatorService;
    private final SemanticRecommendationService semanticService;

    public RecommendationController(RecommendationService recommendationService,
            AnimeEmbeddingPopulatorService populatorService,
            SemanticRecommendationService semanticService) {
        this.recommendationService = recommendationService;
        this.populatorService = populatorService;
        this.semanticService = semanticService;
    }

    @GetMapping
    public ResponseEntity<?> getRecommendations() {
        String username = getCurrentUsername();
        List<AniListResponse.AnimeInfo> recommendations
                = recommendationService.getRecommendations(username);
        return ResponseEntity.ok(recommendations);
    }

    // POST /api/users/recommendations/blacklist — hide an anime from recommendations
    @PostMapping("/blacklist")
    public ResponseEntity<?> blacklistAnime(@RequestBody Map<String, Object> request) {
        String username = getCurrentUsername();
        Integer anilistId = (Integer) request.get("anilistId");
        String title = (String) request.get("title");
        String coverImage = (String) request.get("coverImage");
        recommendationService.blacklistAnime(username, anilistId, title, coverImage);
        return ResponseEntity.ok(Map.of("message", "Anime hidden from recommendations"));
    }

    // GET /api/users/recommendations/blacklist — view blacklisted anime
    @GetMapping("/blacklist")
    public ResponseEntity<?> getBlacklist() {
        String username = getCurrentUsername();
        return ResponseEntity.ok(recommendationService.getBlacklist(username));
    }

    // DELETE /api/users/recommendations/blacklist/{id} — remove from blacklist
    @DeleteMapping("/blacklist/{id}")
    public ResponseEntity<?> removeFromBlacklist(@PathVariable Long id) {
        String username = getCurrentUsername();
        recommendationService.removeFromBlacklist(username, id);
        return ResponseEntity.ok(Map.of("message", "Removed from blacklist"));
    }

    /**
     * POST /api/users/recommendations/semantic — AI-powered semantic search.
     * Takes seed anime IDs + optional text query, returns similar anime via
     * embeddings.
     */
    @PostMapping("/semantic")
    public ResponseEntity<?> getSemanticRecommendations(@RequestBody SemanticRequest request) {
        // Anonymous users get null username — service skips list/blacklist exclusion
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getName())) ? auth.getName() : null;

        int limit = (request.getLimit() != null) ? request.getLimit() : 15;

        List<AniListResponse.AnimeInfo> results = semanticService.recommend(
                username, request.getSeedIds(), request.getQuery(), limit);
        return ResponseEntity.ok(results);
    }

    /**
     * POST /api/users/recommendations/populate — trigger bulk embedding of
     * anime. Fetches anime from AniList by popularity, embeds with OpenAI,
     * stores in DB.
     *
     * @param request Optional body with "pages" (default 100 = 5,000 anime).
     */
    @PostMapping("/populate")
    public ResponseEntity<?> populateEmbeddings(@RequestBody(required = false) Map<String, Object> request) {
        int pages = 100; // default: 100 pages × 50 per page = 5,000 anime
        if (request != null && request.get("pages") != null) {
            pages = ((Number) request.get("pages")).intValue();
        }

        int embedded = populatorService.populate(pages);
        return ResponseEntity.ok(Map.of(
                "message", "Population complete",
                "embedded", embedded
        ));
    }

    // Same helper pattern as AnimeListEntryController
    private String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth.getName();
    }
}
