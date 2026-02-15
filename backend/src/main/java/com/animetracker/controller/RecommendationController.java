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
import com.animetracker.dto.RecommendationBlacklistRequest;
import com.animetracker.dto.SemanticRequest;
import com.animetracker.exception.UnauthorizedException;
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

    public RecommendationController(SemanticRecommendationService semanticService) {
        this.semanticService = semanticService;
    }

    @PostMapping("/semantic")
    public ResponseEntity<List<AniListResponse.AnimeInfo>> getSemanticRecommendations(
            @Valid @RequestBody SemanticRequest request) {
        String username = getCurrentUsernameOrNull();
        List<AniListResponse.AnimeInfo> results = semanticService.recommend(
                username,
                request.getSeedIds(),
                request.getQuery(),
                request.getLimit(),
                Boolean.TRUE.equals(request.getUseListOnly()),
                request.getListWeight());
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
