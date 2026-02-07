package com.animetracker.controller;

import com.animetracker.dto.AniListResponse;
import com.animetracker.service.AniListService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/anime")
public class AnimeSearchController {

    private final AniListService aniListService;

    public AnimeSearchController(AniListService aniListService) {
        this.aniListService = aniListService;
    }

    // GET /api/anime/search?q=naruto
    @GetMapping("/search")
    public ResponseEntity<?> searchAnime(@RequestParam String q) {
        if (q == null || q.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Search query cannot be empty"));
        }

        List<AniListResponse.AnimeInfo> results = aniListService.searchAnime(q.trim());
        return ResponseEntity.ok(results);
    }
}
