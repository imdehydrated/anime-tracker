package com.animetracker.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.animetracker.dto.UpdateAnimeEntryRequest;
import com.animetracker.entity.AnimeListEntry;
import com.animetracker.entity.User;
import com.animetracker.exception.BadRequestException;
import com.animetracker.exception.ConflictException;
import com.animetracker.exception.NotFoundException;
import com.animetracker.repository.AnimeListEntryRepository;
import com.animetracker.repository.UserRepository;

/**
 * Business logic for user list CRUD.
 * Owns validation, normalization, and authorization checks for list entries.
 */
@Service
@Transactional
public class AnimeListEntryService {

    private static final Set<String> VALID_STATUSES = Set.of(
            "WATCHING",
            "COMPLETED",
            "PLAN_TO_WATCH",
            "ON_HOLD",
            "DROPPED");

    private final AnimeListEntryRepository animeListEntryRepository;
    private final UserRepository userRepository;

    public AnimeListEntryService(AnimeListEntryRepository animeListEntryRepository,
            UserRepository userRepository) {
        this.animeListEntryRepository = animeListEntryRepository;
        this.userRepository = userRepository;
    }

    public List<AnimeListEntry> getUserList(String username) {
        return animeListEntryRepository.findByUser(findUser(username));
    }

    public AnimeListEntry addAnimeToList(String username, Integer anilistId,
            String status, String title, String coverImage, String genres,
            Integer totalEpisodes) {
        User user = findUser(username);

        if (animeListEntryRepository.existsByUserAndAnilistId(user, anilistId)) {
            throw new ConflictException("Anime already on your list");
        }
        if (totalEpisodes != null && totalEpisodes < 0) {
            throw new BadRequestException("totalEpisodes must be >= 0");
        }

        AnimeListEntry entry = new AnimeListEntry(user, anilistId);
        entry.setStatus(normalizeStatus(status));
        entry.setTitle(title);
        entry.setCoverImage(coverImage);
        entry.setGenres(genres);
        entry.setTotalEpisodes(totalEpisodes);
        return animeListEntryRepository.save(entry);
    }

    public AnimeListEntry updateEntry(String username, Long entryId, UpdateAnimeEntryRequest request) {
        if (!request.hasAnyField()) {
            throw new BadRequestException("At least one field must be provided");
        }

        User user = findUser(username);
        AnimeListEntry entry = findEntry(entryId, user);

        if (request.isStatusProvided()) {
            entry.setStatus(normalizeStatus(request.getStatus()));
        }
        if (request.isScoreProvided()) {
            Integer score = request.getScore();
            if (score != null && (score < 1 || score > 10)) {
                throw new BadRequestException("score must be between 1 and 10");
            }
            entry.setScore(score);
        }
        if (request.isEpisodesWatchedProvided()) {
            Integer episodesWatched = request.getEpisodesWatched();
            int normalizedEpisodes = episodesWatched == null ? 0 : episodesWatched;
            if (normalizedEpisodes < 0) {
                throw new BadRequestException("episodesWatched must be >= 0");
            }
            if (entry.getTotalEpisodes() != null && normalizedEpisodes > entry.getTotalEpisodes()) {
                throw new BadRequestException("episodesWatched cannot exceed totalEpisodes");
            }
            entry.setEpisodesWatched(normalizedEpisodes);
        }
        entry.setUpdatedAt(LocalDateTime.now());
        return animeListEntryRepository.save(entry);
    }

    public void deleteEntry(String username, Long entryId) {
        User user = findUser(username);
        AnimeListEntry entry = findEntry(entryId, user);
        animeListEntryRepository.delete(entry);
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    private AnimeListEntry findEntry(Long entryId, User user) {
        return animeListEntryRepository.findByIdAndUser(entryId, user)
                .orElseThrow(() -> new NotFoundException("Entry not found"));
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "PLAN_TO_WATCH";
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!VALID_STATUSES.contains(normalized)) {
            throw new BadRequestException("Invalid status value");
        }
        return normalized;
    }
}
