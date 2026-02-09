package com.animetracker.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.animetracker.dto.AniListResponse;
import com.animetracker.service.AniListService;

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

    // GET /api/anime/{id} — Fetch a single anime by AniList ID
    @GetMapping("/{id}")
    public ResponseEntity<?> getAnimeById(@PathVariable Integer id) {
        AniListResponse.AnimeInfo anime = aniListService.getAnimeById(id);

        if (anime == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Anime not found"));
        }

        return ResponseEntity.ok(anime);
    }
}
