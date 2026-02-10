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

import com.animetracker.entity.AnimeListEntry;
import com.animetracker.service.AnimeListEntryService;
/**
 * REST Controller for anime list CRUD operations.
 * 
 * All endpoints require JWT authentication.
 * Each request is scoped to the logged-in user — users can only
 * view and modify their own list.
 * 
 * Endpoints:
 * - GET    /api/users/list       — Get all anime on user's list
 * - POST   /api/users/list       — Add an anime to user's list
 * - PUT    /api/users/list/{id}  — Update an entry (status, score, episodes)
 * - DELETE /api/users/list/{id}  — Remove an entry from user's list
 */
@RestController
@RequestMapping("/api/users/list")
public class AnimeListEntryController {

    private final AnimeListEntryService animeListEntryService;

    public AnimeListEntryController(AnimeListEntryService animeListEntryService) {
        this.animeListEntryService = animeListEntryService;
    }

    /**
     * GET /api/users/list — Returns all anime on the logged-in user's list
     */
    @GetMapping
    public ResponseEntity<?> getUserList() {
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
            item.put("createdAt", entry.getCreatedAt());
            item.put("updatedAt", entry.getUpdatedAt());
            return item;
        }).toList();

        return ResponseEntity.ok(response);
    }

    // Helper: gets the username from the JWT token (stored in SecurityContext by JwtAuthenticationFilter)
    private String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth.getName();
    }

    /**
     * POST /api/users/list — Add an anime to the user's list
     */
    @PostMapping
    public ResponseEntity<?> addAnimeToList(@RequestBody AddAnimeRequest request) {
        try {
            String username = getCurrentUsername();
            AnimeListEntry entry = animeListEntryService.addAnimeToList(
                    username, request.anilistId(), request.status(),
                    request.title(), request.coverImage(), request.genres());

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Anime added to list");
            response.put("id", entry.getId());
            response.put("title", entry.getTitle());
            response.put("coverImage", entry.getCoverImage());
            response.put("anilistId", entry.getAnilistId());
            response.put("status", entry.getStatus());
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (RuntimeException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }

    // Request body for adding an anime
    public record AddAnimeRequest(Integer anilistId, String status, String title,
        String coverImage, String genres) {}

    /**
     * PUT /api/users/list/{id} — Update an anime list entry
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateEntry(@PathVariable Long id,
                                          @RequestBody UpdateEntryRequest request) {
        try {
            String username = getCurrentUsername();
            AnimeListEntry entry = animeListEntryService.updateEntry(
                    username, id, request.status(), request.score(), request.episodesWatched());

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Entry updated");
            response.put("id", entry.getId());
            response.put("status", entry.getStatus());
            response.put("score", entry.getScore());
            response.put("episodesWatched", entry.getEpisodesWatched());
            return ResponseEntity.ok(response);

        } catch (RuntimeException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }

    // Request body for updating an entry
    public record UpdateEntryRequest(String status, Integer score, Integer episodesWatched) {}

    /**
     * DELETE /api/users/list/{id} — Remove an anime from user's list
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteEntry(@PathVariable Long id) {
        try {
            String username = getCurrentUsername();
            animeListEntryService.deleteEntry(username, id);

            Map<String, String> response = new HashMap<>();
            response.put("message", "Entry deleted");
            return ResponseEntity.ok(response);

        } catch (RuntimeException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }
}
