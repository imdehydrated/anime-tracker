package com.animetracker.controller;

import java.util.HashMap;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.animetracker.dto.AddAnimeRequest;
import com.animetracker.dto.UpdateAnimeEntryRequest;
import com.animetracker.entity.AnimeListEntry;
import com.animetracker.service.AnimeListEntryService;

import jakarta.validation.Valid;

/**
 * CRUD endpoints for the authenticated user's anime list.
 */
@RestController
@RequestMapping("/api/users/list")
public class AnimeListEntryController {

    private final AnimeListEntryService animeListEntryService;

    public AnimeListEntryController(AnimeListEntryService animeListEntryService) {
        this.animeListEntryService = animeListEntryService;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getUserList() {
        String username = getCurrentUsername();
        List<AnimeListEntry> entries = animeListEntryService.getUserList(username);

        List<Map<String, Object>> response = entries.stream().map(entry -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", entry.getId());
            item.put("anilistId", entry.getAnilistId());
            item.put("title", entry.getTitle());
            item.put("genres", entry.getGenres());
            item.put("coverImage", entry.getCoverImage());
            item.put("status", entry.getStatus());
            item.put("score", entry.getScore());
            item.put("episodesWatched", entry.getEpisodesWatched());
            item.put("totalEpisodes", entry.getTotalEpisodes());
            item.put("createdAt", entry.getCreatedAt());
            item.put("updatedAt", entry.getUpdatedAt());
            return item;
        }).toList();

        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> addAnimeToList(@Valid @RequestBody AddAnimeRequest request) {
        String username = getCurrentUsername();
        AnimeListEntry entry = animeListEntryService.addAnimeToList(
                username,
                request.anilistId(),
                request.status(),
                request.title(),
                request.coverImage(),
                request.genres(),
                request.totalEpisodes());

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Anime added to list");
        response.put("id", entry.getId());
        response.put("title", entry.getTitle());
        response.put("coverImage", entry.getCoverImage());
        response.put("anilistId", entry.getAnilistId());
        response.put("status", entry.getStatus());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateEntry(@PathVariable Long id,
            @RequestBody UpdateAnimeEntryRequest request) {
        String username = getCurrentUsername();
        AnimeListEntry entry = animeListEntryService.updateEntry(username, id, request);

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Entry updated");
        response.put("id", entry.getId());
        response.put("status", entry.getStatus());
        response.put("score", entry.getScore());
        response.put("episodesWatched", entry.getEpisodesWatched());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteEntry(@PathVariable Long id) {
        String username = getCurrentUsername();
        animeListEntryService.deleteEntry(username, id);
        return ResponseEntity.ok(Map.of("message", "Entry deleted"));
    }

    private String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth.getName();
    }
}
