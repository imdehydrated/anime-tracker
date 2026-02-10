package com.animetracker.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.animetracker.entity.AnimeListEntry;
import com.animetracker.entity.User;
import com.animetracker.repository.AnimeListEntryRepository;
import com.animetracker.repository.UserRepository;
/**
 * Service layer for managing anime list entries.
 * Handles business logic for CRUD operations on a user's anime watchlist.
 * 
 * Each operation requires a username (from JWT) to scope entries
 * to the authenticated user — users can only access their own list.
 */
@Service //Spring tag
public class AnimeListEntryService {
    private final AnimeListEntryRepository animeListEntryRepository;
    private final UserRepository userRepository;

    public AnimeListEntryService(AnimeListEntryRepository animeListEntryRepository,
        UserRepository userRepository) {
        this.animeListEntryRepository = animeListEntryRepository;
        this.userRepository = userRepository;
    }

    /**
     * Gets all the anime in a user's list. Throws an exception if user is not found
     */
    public List<AnimeListEntry> getUserList(String username) {
        User user = userRepository.findByUsername(username)
        .orElseThrow(() -> new RuntimeException("User not found"));

        return animeListEntryRepository.findByUser(user);
    }

    /**
     * Adds an anime to a user's list
     */
    public AnimeListEntry addAnimeToList(String username, Integer anilistId,
            String status, String title, String coverImage, String genres,
            Integer totalEpisodes) {
        User user = userRepository.findByUsername(username)
        .orElseThrow(() -> new RuntimeException("User not found"));

        if (animeListEntryRepository.existsByUserAndAnilistId(user, anilistId)) {
            throw new RuntimeException("Anime already on your list");
        }

        AnimeListEntry entry = new AnimeListEntry(user, anilistId);
        entry.setStatus(status);
        entry.setTitle(title);
        entry.setCoverImage(coverImage);
        entry.setGenres(genres);
        entry.setTotalEpisodes(totalEpisodes);
        return animeListEntryRepository.save(entry);
    }

    /**
     * Updates an existing anime in the list.
     * Only updates fields that are explicitly present in the request map,
     * so sending { status: "WATCHING" } won't wipe score or episodesWatched.
     */
    public AnimeListEntry updateEntry(String username, Long entryId,
        java.util.Map<String, Object> updates) {
        User user = userRepository.findByUsername(username)
        .orElseThrow(() -> new RuntimeException("User not found"));

        AnimeListEntry entry = animeListEntryRepository.findById(entryId)
        .orElseThrow(() -> new RuntimeException("Entry not found"));

        if (!entry.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Incorrect user's list");
        }

        if (updates.containsKey("status")) {
            entry.setStatus((String) updates.get("status"));
        }
        if (updates.containsKey("score")) {
            Object scoreVal = updates.get("score");
            entry.setScore(scoreVal == null ? null : ((Number) scoreVal).intValue());
        }
        if (updates.containsKey("episodesWatched")) {
            Object epVal = updates.get("episodesWatched");
            entry.setEpisodesWatched(epVal == null ? 0 : ((Number) epVal).intValue());
        }
        entry.setUpdatedAt(java.time.LocalDateTime.now());

        return animeListEntryRepository.save(entry);
    }

    /**
     * Deletes an anime from list
     */
    public void deleteEntry(String username, Long entryId) {
        User user = userRepository.findByUsername(username)
        .orElseThrow(() -> new RuntimeException("User not found"));

        AnimeListEntry entry = animeListEntryRepository.findById(entryId)
        .orElseThrow(() -> new RuntimeException("Entry not found"));

        if (!entry.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Incorrect user's list");
        }

        animeListEntryRepository.delete(entry);
    }
}
