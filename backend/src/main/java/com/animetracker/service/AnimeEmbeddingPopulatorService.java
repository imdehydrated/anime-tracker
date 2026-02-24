package com.animetracker.service;

import com.animetracker.dto.AniListResponse;
import com.animetracker.repository.AnimeEmbeddingRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

@Service
public class AnimeEmbeddingPopulatorService {

    private static final Logger log = LoggerFactory.getLogger(AnimeEmbeddingPopulatorService.class);
    private static final List<String> ACTIVE_FORMATS = List.of(
            "TV",
            "TV_SHORT",
            "MOVIE",
            "OVA",
            "ONA",
            "SPECIAL");

    private final AniListService aniListService;
    private final MlSidecarService mlSidecarService;
    private final AnimeEmbeddingRepository embeddingRepository;

    public AnimeEmbeddingPopulatorService(
            AniListService aniListService,
            MlSidecarService mlSidecarService,
            AnimeEmbeddingRepository embeddingRepository) {
        this.aniListService = aniListService;
        this.mlSidecarService = mlSidecarService;
        this.embeddingRepository = embeddingRepository;
    }

    /**
     * Populate from popular anime pages. Existing behavior kept for compatibility.
     */
    @Transactional
    public int populate(int totalPages) {
        PopulationStats stats = populatePages(
                totalPages,
                50,
                (page, perPage) -> aniListService.fetchPopularAnimePage(page, perPage),
                "popular");
        return stats.embedded();
    }

    /**
     * Populate from active catalog pages (broader than popularity sorted subset).
     */
    @Transactional
    public PopulationStats populateActiveCatalog(int maxPages, int perPage) {
        int effectivePerPage = Math.max(1, Math.min(50, perPage));
        return populatePages(
                maxPages,
                effectivePerPage,
                (page, pageSize) -> aniListService.fetchActiveCatalogPage(page, pageSize, ACTIVE_FORMATS),
                "active_catalog");
    }

    private PopulationStats populatePages(
            int totalPages,
            int perPage,
            BiFunction<Integer, Integer, List<AniListResponse.AnimeInfo>> fetchPage,
            String source) {
        if (!mlSidecarService.isEnabled()) {
            throw new IllegalStateException("ML sidecar must be enabled for embedding population");
        }

        int embedded = 0;
        int skipped = 0;
        int failed = 0;
        int discovered = 0;
        int pagesVisited = 0;

        for (int page = 1; page <= Math.max(1, totalPages); page++) {
            pagesVisited++;
            log.info("Fetching AniList {} page {}/{}", source, page, totalPages);

            List<AniListResponse.AnimeInfo> animeList;
            try {
                animeList = fetchPage.apply(page, perPage);
            } catch (Exception e) {
                log.error("Failed to fetch AniList {} page {}: {}", source, page, e.getMessage());
                break;
            }

            if (animeList == null || animeList.isEmpty()) {
                log.info("No more anime returned from AniList {} at page {}, stopping", source, page);
                break;
            }

            discovered += animeList.size();
            for (AniListResponse.AnimeInfo anime : animeList) {
                if (anime == null || anime.getId() == null || anime.getId() <= 0) {
                    failed++;
                    continue;
                }
                try {
                    if (embeddingRepository.existsByAnilistId(anime.getId())) {
                        skipped++;
                        continue;
                    }
                    if (upsertEmbeddedAnime(anime)) {
                        embedded++;
                        if (embedded % 50 == 0) {
                            log.info(
                                    "Population progress ({}): embedded={}, skipped={}, failed={}",
                                    source,
                                    embedded,
                                    skipped,
                                    failed);
                        }
                    } else {
                        failed++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.error(
                            "Failed to embed anime {} ({}) from {}: {}",
                            anime.getId(),
                            anime.getTitle() != null ? anime.getTitle().getRomaji() : "unknown",
                            source,
                            e.getMessage());
                }
            }
        }

        long totalCustomEmbeddings = embeddingRepository.countCustomEmbeddings();
        log.info(
                "Population complete ({}) pages={} discovered={} embedded={} skipped={} failed={} total_custom={}",
                source,
                pagesVisited,
                discovered,
                embedded,
                skipped,
                failed,
                totalCustomEmbeddings);
        return new PopulationStats(
                source,
                pagesVisited,
                discovered,
                embedded,
                skipped,
                failed,
                totalCustomEmbeddings);
    }

    private boolean upsertEmbeddedAnime(AniListResponse.AnimeInfo anime) {
        String embeddingText = buildEmbeddingText(anime);
        float[] vector = mlSidecarService.embedText(embeddingText);
        if (vector == null || vector.length == 0) {
            log.warn("Skipping anime {} because sidecar embedding failed", anime.getId());
            return false;
        }

        String vectorStr = EmbeddingService.toVectorString(vector);
        String titleRomaji = anime.getTitle() != null ? anime.getTitle().getRomaji() : null;
        String titleEnglish = anime.getTitle() != null ? anime.getTitle().getEnglish() : null;
        String coverImage = anime.getCoverImage() != null ? anime.getCoverImage().getLarge() : null;
        String genres = anime.getGenres() != null ? String.join(", ", anime.getGenres()) : null;
        String description = stripHtml(anime.getDescription());

        embeddingRepository.upsertCustomEmbedding(
                anime.getId(),
                titleRomaji,
                titleEnglish,
                coverImage,
                genres,
                description,
                anime.getAverageScore(),
                anime.getStatus(),
                anime.getEpisodes(),
                vectorStr);
        return true;
    }

    /**
     * Build embedding text that captures title + topical metadata.
     */
    String buildEmbeddingText(AniListResponse.AnimeInfo anime) {
        StringBuilder sb = new StringBuilder();

        if (anime.getTitle() != null) {
            String title = anime.getTitle().getEnglish() != null
                    ? anime.getTitle().getEnglish()
                    : anime.getTitle().getRomaji();
            if (title != null && !title.isBlank()) {
                sb.append("Title: ").append(title).append("\n");
            }
            if (anime.getTitle().getNativeTitle() != null && !anime.getTitle().getNativeTitle().isBlank()) {
                sb.append("Title Native: ").append(anime.getTitle().getNativeTitle()).append("\n");
            }
        }

        if (anime.getSynonyms() != null && !anime.getSynonyms().isEmpty()) {
            List<String> cleaned = anime.getSynonyms().stream()
                    .filter(x -> x != null && !x.isBlank())
                    .limit(6)
                    .toList();
            if (!cleaned.isEmpty()) {
                sb.append("Synonyms: ").append(String.join(", ", cleaned)).append("\n");
            }
        }

        if (anime.getFormat() != null && !anime.getFormat().isBlank()) {
            sb.append("Format: ").append(anime.getFormat()).append("\n");
        }
        if (anime.getSeason() != null && !anime.getSeason().isBlank()) {
            sb.append("Season: ").append(anime.getSeason());
            if (anime.getSeasonYear() != null) {
                sb.append(" ").append(anime.getSeasonYear());
            }
            sb.append("\n");
        }

        if (anime.getGenres() != null && !anime.getGenres().isEmpty()) {
            sb.append("Genres: ").append(String.join(", ", anime.getGenres())).append("\n");
        }

        if (anime.getTags() != null && !anime.getTags().isEmpty()) {
            String tagStr = anime.getTags().stream()
                    .filter(t -> t.getRank() != null && t.getRank() >= 60)
                    .sorted((a, b) -> b.getRank() - a.getRank())
                    .map(t -> t.getName() + " (" + t.getRank() + "%)")
                    .collect(Collectors.joining(", "));
            if (!tagStr.isEmpty()) {
                sb.append("Tags: ").append(tagStr).append("\n");
            }
        }

        if (anime.getStudios() != null && !anime.getStudios().isEmpty()) {
            List<String> studios = new ArrayList<>();
            for (AniListResponse.AnimeStudio studio : anime.getStudios()) {
                if (studio == null || studio.getName() == null || studio.getName().isBlank()) {
                    continue;
                }
                studios.add(studio.getName());
            }
            if (!studios.isEmpty()) {
                sb.append("Studios: ").append(String.join(", ", studios)).append("\n");
            }
        }

        if (anime.getRelations() != null && !anime.getRelations().isEmpty()) {
            List<String> relationTitles = new ArrayList<>();
            for (AniListResponse.AnimeRelation relation : anime.getRelations()) {
                if (relation == null || relation.getTitle() == null) {
                    continue;
                }
                String title = relation.getTitle().getEnglish() != null
                        ? relation.getTitle().getEnglish()
                        : relation.getTitle().getRomaji();
                if (title != null && !title.isBlank()) {
                    relationTitles.add(title);
                }
                if (relationTitles.size() >= 5) {
                    break;
                }
            }
            if (!relationTitles.isEmpty()) {
                sb.append("Related: ").append(String.join(", ", relationTitles)).append("\n");
            }
        }

        if (anime.getDescription() != null && !anime.getDescription().isBlank()) {
            String cleanDesc = stripHtml(anime.getDescription());
            if (cleanDesc != null && cleanDesc.length() > 500) {
                cleanDesc = cleanDesc.substring(0, 500) + "...";
            }
            if (cleanDesc != null && !cleanDesc.isBlank()) {
                sb.append("Description: ").append(cleanDesc);
            }
        }

        return sb.toString().trim();
    }

    private static String stripHtml(String html) {
        if (html == null) {
            return null;
        }
        return html.replaceAll("<[^>]*>", "").trim();
    }

    public record PopulationStats(
            String source,
            int pagesVisited,
            int discovered,
            int embedded,
            int skipped,
            int failed,
            long totalCustomEmbeddings) {
    }
}
