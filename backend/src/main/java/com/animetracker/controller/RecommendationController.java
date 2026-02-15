package com.animetracker.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
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
import com.animetracker.service.SemanticRecommendationService;

@RestController
@RequestMapping("/api/users/recommendations")
public class RecommendationController {

    private final SemanticRecommendationService semanticService;

    public RecommendationController(SemanticRecommendationService semanticService) {
        this.semanticService = semanticService;
    }

    @PostMapping("/semantic")
    public ResponseEntity<?> getSemanticRecommendations(@RequestBody SemanticRequest request) {
        try {
            String username = getCurrentUsernameOrNull();
            List<AniListResponse.AnimeInfo> results = semanticService.recommend(
                    username,
                    request.getSeedIds(),
                    request.getQuery(),
                    request.getLimit(),
                    Boolean.TRUE.equals(request.getUseListOnly()),
                    request.getListWeight());
            return ResponseEntity.ok(results);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/blacklist")
    public ResponseEntity<?> blacklistAnime(@RequestBody Map<String, Object> request) {
        Integer anilistId = request.get("anilistId") instanceof Number
                ? ((Number) request.get("anilistId")).intValue()
                : null;
        String title = request.get("title") instanceof String ? (String) request.get("title") : null;
        String coverImage = request.get("coverImage") instanceof String ? (String) request.get("coverImage") : null;

        try {
            semanticService.blacklistAnime(getCurrentUsernameRequired(), anilistId, title, coverImage);
            return ResponseEntity.ok(Map.of("message", "Anime hidden from recommendations"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/blacklist")
    public ResponseEntity<?> getBlacklist() {
        return ResponseEntity.ok(semanticService.getBlacklist(getCurrentUsernameRequired()));
    }

    @DeleteMapping("/blacklist/{id}")
    public ResponseEntity<?> removeFromBlacklist(@PathVariable Long id) {
        try {
            semanticService.removeFromBlacklist(getCurrentUsernameRequired(), id);
            return ResponseEntity.ok(Map.of("message", "Removed from blacklist"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
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
            throw new IllegalArgumentException("Authentication required");
        }
        return username;
    }
}
