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
import com.animetracker.service.RecommendationService;

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

    public RecommendationController(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
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

    // Same helper pattern as AnimeListEntryController
    private String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth.getName();
    }
}
