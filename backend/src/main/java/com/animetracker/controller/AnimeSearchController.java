package com.animetracker.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.animetracker.dto.AniListResponse;
import com.animetracker.dto.AnimeSearchPageResponse;
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
    public ResponseEntity<List<AniListResponse.AnimeInfo>> searchAnime(
            @RequestParam String q,
            @RequestParam(required = false) Boolean includeExtraSeasons,
            @RequestParam(required = false) Boolean includeMovies,
            @RequestParam(required = false) Boolean includeOnasOvasSpecials,
            @RequestParam(required = false) Boolean includeMusic,
            @RequestParam(required = false) Boolean includeAdult,
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) Integer pageSize) {
        if (q == null || q.trim().isEmpty()) {
            throw new BadRequestException("Search query cannot be empty");
        }
        AniListService.SearchFilters filters = AniListService.SearchFilters.fromNullable(
                includeExtraSeasons,
                includeMovies,
                includeOnasOvasSpecials,
                includeMusic,
                includeAdult);
        return ResponseEntity.ok(aniListService.searchAnime(q.trim(), filters, offset, pageSize));
    }

    @GetMapping("/search/paged")
    public ResponseEntity<AnimeSearchPageResponse> searchAnimePaged(
            @RequestParam String q,
            @RequestParam(required = false) Boolean includeExtraSeasons,
            @RequestParam(required = false) Boolean includeMovies,
            @RequestParam(required = false) Boolean includeOnasOvasSpecials,
            @RequestParam(required = false) Boolean includeMusic,
            @RequestParam(required = false) Boolean includeAdult,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer pageSize) {
        if (q == null || q.trim().isEmpty()) {
            throw new BadRequestException("Search query cannot be empty");
        }
        AniListService.SearchFilters filters = AniListService.SearchFilters.fromNullable(
                includeExtraSeasons,
                includeMovies,
                includeOnasOvasSpecials,
                includeMusic,
                includeAdult);
        return ResponseEntity.ok(aniListService.searchAnimePaged(q.trim(), filters, cursor, pageSize));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AniListResponse.AnimeInfo> getAnimeById(@PathVariable Integer id) {
        AniListResponse.AnimeInfo anime = aniListService.getAnimeByIdWithRelations(id);
        if (anime == null) {
            throw new NotFoundException("Anime not found");
        }
        return ResponseEntity.ok(anime);
    }
}
