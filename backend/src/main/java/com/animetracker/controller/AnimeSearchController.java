package com.animetracker.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.animetracker.dto.AniListResponse;
import com.animetracker.exception.BadRequestException;
import com.animetracker.exception.NotFoundException;
import com.animetracker.service.AniListService;

/**
 * Public anime search/detail endpoints backed by AniList GraphQL.
 */
@RestController
@RequestMapping("/api/anime")
public class AnimeSearchController {

    private final AniListService aniListService;

    public AnimeSearchController(AniListService aniListService) {
        this.aniListService = aniListService;
    }

    @GetMapping("/search")
    public ResponseEntity<List<AniListResponse.AnimeInfo>> searchAnime(@RequestParam String q) {
        if (q == null || q.trim().isEmpty()) {
            throw new BadRequestException("Search query cannot be empty");
        }
        return ResponseEntity.ok(aniListService.searchAnime(q.trim()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AniListResponse.AnimeInfo> getAnimeById(@PathVariable Integer id) {
        AniListResponse.AnimeInfo anime = aniListService.getAnimeById(id);
        if (anime == null) {
            throw new NotFoundException("Anime not found");
        }
        return ResponseEntity.ok(anime);
    }
}
