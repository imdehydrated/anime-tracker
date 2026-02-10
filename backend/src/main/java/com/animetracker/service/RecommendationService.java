package com.animetracker.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.animetracker.dto.AniListResponse;
import com.animetracker.entity.AnimeListEntry;
import com.animetracker.entity.RecommendationBlacklist;
import com.animetracker.entity.User;
import com.animetracker.repository.RecommendationBlacklistRepository;
import com.animetracker.repository.UserRepository;

/**
 * Recommendation engine — suggests anime based on the user's list.
 *
 * Algorithm: 1. Get all entries from the user's list 2. Parse genres from each
 * entry (stored as comma-separated string) 3. Weight each genre by the entry's
 * score (default 50 if unscored) 4. Pick the top 5 genres by weighted total 5.
 * Query AniList for top-rated anime in those genres (multiple pages if needed)
 * 6. Filter out anime already on the user's list or blacklist
 */
@Service
public class RecommendationService {

    private final AnimeListEntryService animeListEntryService;
    private final AniListService aniListService;
    private final RecommendationBlacklistRepository blacklistRepository;
    private final UserRepository userRepository;

    public RecommendationService(AnimeListEntryService animeListEntryService,
            AniListService aniListService,
            RecommendationBlacklistRepository blacklistRepository,
            UserRepository userRepository) {
        this.animeListEntryService = animeListEntryService;
        this.aniListService = aniListService;
        this.blacklistRepository = blacklistRepository;
        this.userRepository = userRepository;
    }

    public List<AniListResponse.AnimeInfo> getRecommendations(String username) {
        // Step 1: Get the user's anime list
        List<AnimeListEntry> userList = animeListEntryService.getUserList(username);

        if (userList.isEmpty()) {
            return List.of();
        }

        // Step 2-3: Tally genre weights (genre → total weighted score)
        Map<String, Double> genreWeights = new HashMap<>();

        for (AnimeListEntry entry : userList) {
            if (entry.getGenres() == null || entry.getGenres().isBlank()) {
                continue;
            }

            double weight = (entry.getScore() != null && entry.getScore() > 0)
                    ? entry.getScore() : 50.0;

            String[] genres = entry.getGenres().split(",");
            for (String genre : genres) {
                String trimmed = genre.trim();
                genreWeights.merge(trimmed, weight, Double::sum);
            }
        }

        if (genreWeights.isEmpty()) {
            return List.of();
        }

        // Step 4: Pick top genres by weighted score
        List<String> topGenres = genreWeights.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(5)
                .map(Map.Entry::getKey)
                .toList();

        // Collect AniList IDs to exclude (user's list + blacklist)
        Set<Integer> excludeIds = userList.stream()
                .map(AnimeListEntry::getAnilistId)
                .collect(Collectors.toSet());

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
        blacklistRepository.findByUser(user).forEach(
                bl -> excludeIds.add(bl.getAnilistId()));

        // Step 5-6: Fetch pages from AniList until we have 10 recommendations
        // or run out of pages (max 4 pages to avoid excessive API calls)
        List<AniListResponse.AnimeInfo> results = new ArrayList<>();
        int maxPages = 4;

        for (int page = 1; page <= maxPages && results.size() < 10; page++) {
            List<AniListResponse.AnimeInfo> candidates
                    = aniListService.searchByGenres(topGenres, page, 25);

            if (candidates.isEmpty()) {
                break; // No more results from AniList
            }

            candidates.stream()
                    .filter(anime -> !excludeIds.contains(anime.getId()))
                    .forEach(results::add);
        }

        return results.stream().limit(10).toList();
    }

    public void blacklistAnime(String username, Integer anilistId, String title, String coverImage) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!blacklistRepository.existsByUserAndAnilistId(user, anilistId)) {
            blacklistRepository.save(new RecommendationBlacklist(user, anilistId, title, coverImage));
        }
    }

    public List<Map<String, Object>> getBlacklist(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        return blacklistRepository.findByUser(user).stream().map(bl -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", bl.getId());
            item.put("anilistId", bl.getAnilistId());
            item.put("title", bl.getTitle());
            item.put("coverImage", bl.getCoverImage());
            item.put("createdAt", bl.getCreatedAt());
            return item;
        }).toList();
    }

    public void removeFromBlacklist(String username, Long id) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        RecommendationBlacklist entry = blacklistRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Blacklist entry not found"));

        if (!entry.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Not your blacklist entry");
        }

        blacklistRepository.delete(entry);
    }
}
