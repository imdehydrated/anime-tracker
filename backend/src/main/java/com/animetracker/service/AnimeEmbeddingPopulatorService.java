package com.animetracker.service;

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.animetracker.dto.AniListResponse;
import com.animetracker.repository.AnimeEmbeddingRepository;

/**
 * Bulk populator that scrapes anime from AniList (by popularity) and stores
 * their local custom embeddings in the anime_embeddings table.
 *
 * Pipeline per page:
 * 1. Fetch 50 anime from AniList (sorted by POPULARITY_DESC)
 * 2. For each anime, build an embedding text from its metadata
 * 3. Call ML sidecar to embed the text into a 384-dim vector
 * 4. Upsert the anime + custom embedding into the database
 * 5. Wait 700ms before the next AniList page (rate limit: 90 req/min)
 *
 * Triggered manually by a maintenance script/job.
 * Target: ~5,000-15,000 anime, embedded through the local sidecar model.
 */
@Service
public class AnimeEmbeddingPopulatorService {

    private static final Logger log = LoggerFactory.getLogger(AnimeEmbeddingPopulatorService.class);

    private final AniListService aniListService;
    private final MlSidecarService mlSidecarService;
    private final AnimeEmbeddingRepository embeddingRepository;

    public AnimeEmbeddingPopulatorService(AniListService aniListService,
            MlSidecarService mlSidecarService,
            AnimeEmbeddingRepository embeddingRepository) {
        this.aniListService = aniListService;
        this.mlSidecarService = mlSidecarService;
        this.embeddingRepository = embeddingRepository;
    }

	/**
	 * Populate the anime_embeddings table with the top anime by popularity.
	 * @param totalPages Number of AniList pages to fetch (50 anime per page).
	 *                   e.g., 100 pages = 5,000 anime, 300 pages = 15,000 anime.
	 * @return Number of anime successfully embedded.
	 */
	@Transactional
    public int populate(int totalPages) {
        if (!mlSidecarService.isEnabled()) {
            throw new IllegalStateException("ML sidecar must be enabled for embedding population");
        }

        int embedded = 0;
        int skipped = 0;

		for (int page = 1; page <= totalPages; page++) {
			log.info("Fetching AniList page {}/{}", page, totalPages);

			List<AniListResponse.AnimeInfo> animeList;
			try {
				animeList = aniListService.fetchPopularAnimePage(page, 50);
			} catch (Exception e) {
				log.error("Failed to fetch AniList page {}: {}", page, e.getMessage());
				break;
			}

			if (animeList.isEmpty()) {
				log.info("No more anime returned from AniList at page {}, stopping", page);
				break;
			}

			for (AniListResponse.AnimeInfo anime : animeList) {
				try {
					// Skip if already embedded (avoid re-embedding on re-runs)
					if (embeddingRepository.existsByAnilistId(anime.getId())) {
						skipped++;
						continue;
					}

					// Build the text that captures this anime's semantic identity
					String embeddingText = buildEmbeddingText(anime);

                    // Call sidecar to embed the text
                    float[] vector = mlSidecarService.embedText(embeddingText);
                    if (vector == null || vector.length == 0) {
                        log.warn("Skipping anime {} because sidecar embedding failed", anime.getId());
                        continue;
                    }
                    String vectorStr = EmbeddingService.toVectorString(vector);

					// Extract metadata for storage
					String titleRomaji = anime.getTitle() != null ? anime.getTitle().getRomaji() : null;
					String titleEnglish = anime.getTitle() != null ? anime.getTitle().getEnglish() : null;
					String coverImage = anime.getCoverImage() != null ? anime.getCoverImage().getLarge() : null;
					String genres = anime.getGenres() != null ? String.join(", ", anime.getGenres()) : null;
					String description = stripHtml(anime.getDescription());

                    // Upsert into database (insert or update if anilist_id exists)
                    embeddingRepository.upsertCustomEmbedding(
                            anime.getId(), titleRomaji, titleEnglish, coverImage,
                            genres, description, anime.getAverageScore(),
                            anime.getStatus(), anime.getEpisodes(),
                            vectorStr);

					embedded++;

					if (embedded % 50 == 0) {
						log.info("Progress: {} embedded, {} skipped", embedded, skipped);
					}
				} catch (Exception e) {
					log.error("Failed to embed anime {} ({}): {}",
							anime.getId(),
							anime.getTitle() != null ? anime.getTitle().getRomaji() : "unknown",
							e.getMessage());
				}
			}

			// Respect AniList rate limits: 90 requests/min → ~700ms between requests
			if (page < totalPages) {
				try {
					Thread.sleep(700);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					log.warn("Populator interrupted at page {}", page);
					break;
				}
			}
		}

		log.info("Population complete: {} embedded, {} skipped (already existed)", embedded, skipped);
		return embedded;
	}

	/**
     * Build the text string that will be embedded by the local model.
	 * Combines title, genres, tags, and description into a single string
	 * that captures the anime's semantic identity.
	 *
	 * Tags are particularly valuable — they include descriptors like
	 * "Time Travel", "Anti-Hero", "Mind Games" with relevance ranks.
	 * We include tags ranked 60+ to focus on the most relevant ones.
	 */
	String buildEmbeddingText(AniListResponse.AnimeInfo anime) {
		StringBuilder sb = new StringBuilder();

		// Title
		if (anime.getTitle() != null) {
			String title = anime.getTitle().getEnglish() != null
					? anime.getTitle().getEnglish()
					: anime.getTitle().getRomaji();
			if (title != null) {
				sb.append("Title: ").append(title).append("\n");
			}
		}

		// Genres
		if (anime.getGenres() != null && !anime.getGenres().isEmpty()) {
			sb.append("Genres: ").append(String.join(", ", anime.getGenres())).append("\n");
		}

		// Tags (ranked 60+, sorted by rank descending)
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

		// Description (strip HTML tags that AniList includes)
		if (anime.getDescription() != null && !anime.getDescription().isBlank()) {
			String cleanDesc = stripHtml(anime.getDescription());
			// Truncate long descriptions to avoid wasting tokens
			if (cleanDesc.length() > 500) {
				cleanDesc = cleanDesc.substring(0, 500) + "...";
			}
			sb.append("Description: ").append(cleanDesc);
		}

		return sb.toString().trim();
	}

	/**
	 * Remove HTML tags from AniList descriptions.
	 * AniList returns descriptions with <br>, <i>, etc.
	 */
	private static String stripHtml(String html) {
		if (html == null) return null;
		return html.replaceAll("<[^>]*>", "").trim();
	}
}
