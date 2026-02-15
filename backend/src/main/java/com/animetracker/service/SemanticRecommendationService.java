package com.animetracker.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.animetracker.dto.AniListResponse;
import com.animetracker.entity.AnimeListEntry;
import com.animetracker.entity.User;
import com.animetracker.repository.AnimeEmbeddingRepository;
import com.animetracker.repository.RecommendationBlacklistRepository;
import com.animetracker.repository.UserRepository;

/**
 * Semantic recommendation engine using OpenAI embeddings + pgvector cosine
 * similarity.
 *
 * Algorithm: 1. Look up seed anime embeddings from anime_embeddings table 2.
 * Average seed vectors into a centroid representing "what the user likes" 3. If
 * a text query is provided, embed it and blend with the seed centroid 4. Run
 * pgvector cosine similarity search, excluding user's list + blacklist + seeds
 * 5. Return top N results as AnimeInfo objects (reusable by frontend)
 */
@Service
public class SemanticRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(SemanticRecommendationService.class);

    private final EmbeddingService embeddingService;
    private final AnimeEmbeddingRepository embeddingRepository;
    private final AnimeListEntryService animeListEntryService;
    private final RecommendationBlacklistRepository blacklistRepository;
    private final UserRepository userRepository;
    private final AniListService aniListService;
    private final AnimeEmbeddingPopulatorService populatorService;

    public SemanticRecommendationService(EmbeddingService embeddingService,
            AnimeEmbeddingRepository embeddingRepository,
            AnimeListEntryService animeListEntryService,
            RecommendationBlacklistRepository blacklistRepository,
            UserRepository userRepository,
            AniListService aniListService,
            AnimeEmbeddingPopulatorService populatorService) {
        this.embeddingService = embeddingService;
        this.embeddingRepository = embeddingRepository;
        this.animeListEntryService = animeListEntryService;
        this.blacklistRepository = blacklistRepository;
        this.userRepository = userRepository;
        this.aniListService = aniListService;
        this.populatorService = populatorService;
    }

    /**
     * Find semantically similar anime based on seed anime + optional text
     * query.
     *
     * @param username The logged-in user (for exclusion list)
     * @param seedIds 1-5 AniList IDs to use as the semantic anchor
     * @param query Optional natural language description (e.g. "dark
     * psychological thriller")
     * @param limit Max results to return (default 15)
     * @return List of AnimeInfo results ordered by similarity
     */
    public List<AniListResponse.AnimeInfo> recommend(String username, List<Integer> seedIds,
            String query, int limit) {

        boolean hasSeeds = seedIds != null && !seedIds.isEmpty();
        boolean hasQuery = query != null && !query.isBlank();

        if (!hasSeeds && !hasQuery) {
            throw new IllegalArgumentException("Provide at least one seed anime or a text query");
        }
        if (seedIds != null && seedIds.size() > 5) {
            throw new IllegalArgumentException("Maximum 5 seed anime allowed");
        }

        if (limit <= 0 || limit > 50) {
            limit = 15;
        }

        // Step 1: Get seed embeddings and compute centroid (if seeds provided)
        float[] centroid = null;

        if (hasSeeds) {
            List<Object[]> seedRows = embeddingRepository.findEmbeddingsByAnilistIds(seedIds);
            log.info("Found {}/{} seed embeddings in database", seedRows.size(), seedIds.size());

            // Embed missing seeds on the fly (fetch from AniList → embed → store)
            if (seedRows.size() < seedIds.size()) {
                Set<Integer> foundIds = seedRows.stream()
                        .map(row -> (Integer) row[0])
                        .collect(Collectors.toSet());

                for (Integer seedId : seedIds) {
                    if (!foundIds.contains(seedId)) {
                        log.info("Seed {} not in database, embedding on the fly", seedId);
                        embedOnTheFly(seedId);
                    }
                }

                // Re-fetch after embedding missing seeds
                seedRows = embeddingRepository.findEmbeddingsByAnilistIds(seedIds);
                log.info("After on-the-fly embedding: {}/{} seeds available", seedRows.size(), seedIds.size());
            }

            if (seedRows.isEmpty()) {
                log.warn("No seed embeddings available — cannot generate recommendations");
                return List.of();
            }

            // Step 2: Parse seed vectors and compute the centroid (average)
            int seedCount = 0;

            for (Object[] row : seedRows) {
                String vectorStr = (String) row[1];
                float[] vec = EmbeddingService.fromVectorString(vectorStr);

                if (centroid == null) {
                    centroid = new float[vec.length];
                }
                for (int i = 0; i < vec.length; i++) {
                    centroid[i] += vec[i];
                }
                seedCount++;
            }

            // Average
            for (int i = 0; i < centroid.length; i++) {
                centroid[i] /= seedCount;
            }
            log.info("Computed centroid from {} seed vectors ({} dimensions)", seedCount, centroid.length);
        }

        // Step 3: Build final search vector
        if (hasQuery) {
            if (query.length() > 500) {
                query = query.substring(0, 500);
            }
            log.info("Embedding text query: '{}'", query);
            float[] queryVector = embeddingService.embed(query);

            if (centroid != null) {
                // Both seeds + query: blend 60% seed centroid + 40% query vector
                for (int i = 0; i < centroid.length; i++) {
                    centroid[i] = 0.6f * centroid[i] + 0.4f * queryVector[i];
                }
                log.info("Blended centroid with query vector (0.6/0.4 split)");
            } else {
                // Query only: use query vector directly as search vector
                centroid = queryVector;
                log.info("Using query vector as sole search vector (no seeds)");
            }
        }
        // If seeds only (no query): centroid already set from step 1-2

        // Step 4: Build exclusion list (seeds + user's anime list + blacklist)
        List<Integer> excludeIds = new ArrayList<>();
        if (hasSeeds) {
            excludeIds.addAll(seedIds);
        }

        if (username != null) {
            List<AnimeListEntry> userList = animeListEntryService.getUserList(username);
            for (AnimeListEntry entry : userList) {
                excludeIds.add(entry.getAnilistId());
            }

            User user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new RuntimeException("User not found"));
            blacklistRepository.findByUser(user).forEach(
                    bl -> excludeIds.add(bl.getAnilistId()));
        }

        // Prevent NOT IN (null) when exclusion list is empty — use impossible ID
        if (excludeIds.isEmpty()) {
            excludeIds.add(-1);
        }

        log.info("Excluding {} IDs (seeds + list + blacklist)", excludeIds.size());

        // Step 5: Cosine similarity search via pgvector
        String vectorStr = EmbeddingService.toVectorString(centroid);
        List<Object[]> results = embeddingRepository.findSimilar(vectorStr, excludeIds, limit);
        log.info("pgvector returned {} results", results.size());

        // Step 6: Map database rows to AnimeInfo objects (same shape as existing recs)
        List<AniListResponse.AnimeInfo> recommendations = new ArrayList<>();
        for (Object[] row : results) {
            AniListResponse.AnimeInfo anime = mapRowToAnimeInfo(row);
            recommendations.add(anime);
        }

        return recommendations;
    }

    /**
     * Fetch a single anime from AniList, embed it, and store in the database.
     * Used when a seed anime isn't in our local embedding database yet.
     */
    private void embedOnTheFly(Integer anilistId) {
        try {
            AniListResponse.AnimeInfo anime = aniListService.getAnimeById(anilistId);
            if (anime == null) {
                log.warn("Could not fetch anime {} from AniList", anilistId);
                return;
            }

            String embeddingText = populatorService.buildEmbeddingText(anime);
            float[] vector = embeddingService.embed(embeddingText);
            String vectorStr = EmbeddingService.toVectorString(vector);

            String titleRomaji = anime.getTitle() != null ? anime.getTitle().getRomaji() : null;
            String titleEnglish = anime.getTitle() != null ? anime.getTitle().getEnglish() : null;
            String coverImage = anime.getCoverImage() != null ? anime.getCoverImage().getLarge() : null;
            String genres = anime.getGenres() != null ? String.join(", ", anime.getGenres()) : null;
            String description = anime.getDescription() != null
                    ? anime.getDescription().replaceAll("<[^>]*>", "").trim() : null;

            embeddingRepository.upsertWithEmbedding(
                    anime.getId(), titleRomaji, titleEnglish, coverImage,
                    genres, description, anime.getAverageScore(),
                    anime.getStatus(), anime.getEpisodes(),
                    embeddingText, vectorStr);

            log.info("Embedded anime {} ({}) on the fly", anilistId, titleEnglish != null ? titleEnglish : titleRomaji);
        } catch (Exception e) {
            log.error("Failed to embed anime {} on the fly: {}", anilistId, e.getMessage());
        }
    }

    /**
     * Map a findSimilar result row to an AnimeInfo object. Row order matches
     * the SELECT in AnimeEmbeddingRepository.findSimilar: id(0), anilist_id(1),
     * title_romaji(2), title_english(3), cover_image(4), genres(5),
     * description(6), average_score(7), status(8), episodes(9),
     * embedding_text(10), created_at(11), updated_at(12), distance(13)
     */
    private AniListResponse.AnimeInfo mapRowToAnimeInfo(Object[] row) {
        AniListResponse.AnimeInfo anime = new AniListResponse.AnimeInfo();
        anime.setId((Integer) row[1]);

        AniListResponse.AnimeTitle title = new AniListResponse.AnimeTitle();
        title.setRomaji((String) row[2]);
        title.setEnglish((String) row[3]);
        anime.setTitle(title);

        AniListResponse.AnimeCoverImage cover = new AniListResponse.AnimeCoverImage();
        cover.setLarge((String) row[4]);
        anime.setCoverImage(cover);

        anime.setGenres(row[5] != null ? List.of(((String) row[5]).split(", ")) : null);
        anime.setDescription((String) row[6]);
        anime.setAverageScore((Integer) row[7]);
        anime.setStatus((String) row[8]);
        anime.setEpisodes((Integer) row[9]);

        return anime;
    }
}
