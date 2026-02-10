package com.animetracker.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.animetracker.dto.AniListResponse;
import com.animetracker.entity.AnimeListEntry;
import com.animetracker.entity.User;
import com.animetracker.repository.RecommendationBlacklistRepository;
import com.animetracker.repository.UserRepository;

/**
 * Recommendation engine — suggests anime based on the user's list.
 */
@Service
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);

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
        List<AnimeListEntry> userList = animeListEntryService.getUserList(username);
        log.info("Recommendations for {}: {} entries on list", username, userList.size());

        if (userList.isEmpty()) {
            return List.of();
        }

        // Tally genre weights
        Map<String, Double> genreWeights = new HashMap<>();

        for (AnimeListEntry entry : userList) {
            if (entry.getGenres() == null || entry.getGenres().isBlank()) {
                log.info("  Entry '{}' (anilistId={}) has NO genres, skipping",
                        entry.getTitle(), entry.getAnilistId());
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

        log.info("Genre weights: {}", genreWeights);

        if (genreWeights.isEmpty()) {
            log.warn("No genres found across all entries — returning empty");
            return List.of();
        }

        List<String> topGenres = genreWeights.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(5)
                .map(Map.Entry::getKey)
                .toList();

        log.info("Top genres: {}", topGenres);

        // Collect AniList IDs to exclude
        Set<Integer> excludeIds = userList.stream()
                .map(AnimeListEntry::getAnilistId)
                .collect(Collectors.toSet());

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
        blacklistRepository.findByUser(user).forEach(
                bl -> excludeIds.add(bl.getAnilistId()));

        log.info("Excluding {} IDs (list + blacklist)", excludeIds.size());

        // Query each top genre individually to get a broad candidate pool.
        // genre_in with multiple genres is too restrictive (requires ALL to match).
        Set<Integer> seenIds = new java.util.HashSet<>();
        List<AniListResponse.AnimeInfo> results = new ArrayList<>();

        for (String genre : topGenres) {
            if (results.size() >= 15) break;

            List<AniListResponse.AnimeInfo> candidates
                    = aniListService.searchByGenres(List.of(genre), 1, 25);

            log.info("Genre '{}': got {} candidates from AniList", genre, candidates.size());

            for (AniListResponse.AnimeInfo anime : candidates) {
                if (!excludeIds.contains(anime.getId()) && seenIds.add(anime.getId())) {
                    results.add(anime);
                }
            }

            log.info("Genre '{}': total unique results so far: {}", genre, results.size());
        }

        log.info("Returning {} recommendations", Math.min(results.size(), 10));
        return results.stream().limit(10).toList();
    }

    public void blacklistAnime(String username, Integer anilistId, String title, String coverImage) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!blacklistRepository.existsByUserAndAnilistId(user, anilistId)) {
            blacklistRepository.save(new com.animetracker.entity.RecommendationBlacklist(user, anilistId, title, coverImage));
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

        com.animetracker.entity.RecommendationBlacklist entry = blacklistRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Blacklist entry not found"));

        if (!entry.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Not your blacklist entry");
        }

        blacklistRepository.delete(entry);
    }
}
