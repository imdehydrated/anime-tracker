package com.animetracker.service;

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
 * score (default 50 if unscored) 4. Pick the top 3 genres by weighted total 5.
 * Query AniList for top-rated anime in those genres 6. Filter out anime already
 * on the user's list
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
            return List.of(); // No list = no recommendations
        }

        // Step 2-3: Tally genre weights (genre → total weighted score)
        Map<String, Double> genreWeights = new HashMap<>();

        for (AnimeListEntry entry : userList) {
            if (entry.getGenres() == null || entry.getGenres().isBlank()) {
                continue; // Skip entries without genres
            }

            // Score defaults to 50 if user hasn't rated it
            double weight = (entry.getScore() != null && entry.getScore() > 0)
                    ? entry.getScore() : 50.0;

            // Split "Action,Adventure,Sci-Fi" → ["Action", "Adventure", "Sci-Fi"]
            String[] genres = entry.getGenres().split(",");
            for (String genre : genres) {
                String trimmed = genre.trim();
                genreWeights.merge(trimmed, weight, Double::sum);
            }
        }

        if (genreWeights.isEmpty()) {
            return List.of(); // No genres found
        }

        // Step 4: Pick top genres by weighted score
        List<String> topGenres = genreWeights.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(5)
                .map(Map.Entry::getKey)
                .toList();

        // Collect AniList IDs already on user's list (to exclude from results)
        Set<Integer> userAnilistIds = userList.stream()
                .map(AnimeListEntry::getAnilistId)
                .collect(Collectors.toSet());

        // Also exclude blacklisted anime
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
        blacklistRepository.findByUser(user).forEach(
                bl -> userAnilistIds.add(bl.getAnilistId()));

        // Step 5: Query AniList for top-rated anime in those genres
        List<AniListResponse.AnimeInfo> candidates
                = aniListService.searchByGenres(topGenres, 1, 25);

        // Step 6: Filter out anime already on the user's list
        return candidates.stream()
                .filter(anime -> !userAnilistIds.contains(anime.getId()))
                .limit(10)
                .toList();
    }

    // Adds an anime to the user's blacklist so it won't appear in recommendations
    public void blacklistAnime(String username, Integer anilistId, String title, String coverImage) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!blacklistRepository.existsByUserAndAnilistId(user, anilistId)) {
            blacklistRepository.save(new RecommendationBlacklist(user, anilistId, title, coverImage));
        }
    }

    // Returns all blacklisted AniList IDs for this user
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

    // Removes an anime from the user's blacklist
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
