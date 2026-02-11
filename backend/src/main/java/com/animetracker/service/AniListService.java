package com.animetracker.service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.animetracker.dto.AniListResponse;

@Service
public class AniListService {

    private final WebClient webClient;

    // WebClient is created once and reused for all requests
    public AniListService() {
        this.webClient = WebClient.builder()
                .baseUrl("https://graphql.anilist.co")
                .build();
    }

    // The GraphQL query we send to AniList — asks for exactly the fields we need
    private static final String SEARCH_QUERY = """
            query ($search: String) {
              Page(page: 1, perPage: 10) {
                media(search: $search, type: ANIME) {
                  id
                  title {
                    romaji
                    english
                  }
                  episodes
                  averageScore
                  coverImage {
                    large
                  }
                  genres
                  description
                  status
                }
              }
            }
            """;

    // Called by the controller — searches AniList for anime matching the query
    public List<AniListResponse.AnimeInfo> searchAnime(String query) {
        // Build the GraphQL request body: { "query": "...", "variables": { "search": "Naruto" } }
        Map<String, Object> requestBody = Map.of(
                "query", SEARCH_QUERY,
                "variables", Map.of("search", query)
        );

        // Send POST request to AniList and parse response into our DTO
        AniListResponse response = webClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(AniListResponse.class) // Deserialize JSON → AniListResponse
                .block();                             // Wait for the response (synchronous)

        // Safely extract the media list, returning empty list if anything is null
        if (response == null || response.getData() == null
                || response.getData().getPage() == null
                || response.getData().getPage().getMedia() == null) {
            return Collections.emptyList();
        }

        return response.getData().getPage().getMedia();
    }

    // GraphQL query to fetch a single anime by its AniList ID
    private static final String GET_BY_ID_QUERY = """
            query ($id: Int) {
              Page(page: 1, perPage: 1) {
                media(id: $id, type: ANIME) {
                  id
                  title {
                    romaji
                    english
                  }
                  episodes
                  averageScore
                  coverImage {
                    large
                  }
                  genres
                  description
                  status
                }
              }
            }
            """;

    // Fetches a single anime by AniList ID — used by the detail page
    public AniListResponse.AnimeInfo getAnimeById(Integer id) {
        Map<String, Object> requestBody = Map.of(
                "query", GET_BY_ID_QUERY,
                "variables", Map.of("id", id)
        );

        AniListResponse response = webClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(AniListResponse.class)
                .block();

        if (response == null || response.getData() == null
                || response.getData().getPage() == null
                || response.getData().getPage().getMedia() == null
                || response.getData().getPage().getMedia().isEmpty()) {
            return null;
        }

        // Only one result — get the first (and only) item
        return response.getData().getPage().getMedia().get(0);
    }

    // GraphQL query for recommendations — finds top-rated anime matching given genres
    private static final String GENRE_SEARCH_QUERY = """
            query ($genres: [String], $page: Int, $perPage: Int) {
              Page(page: $page, perPage: $perPage) {
                media(genre_in: $genres, type: ANIME, sort: SCORE_DESC) {
                  id
                  title {
                    romaji
                    english
                  }
                  episodes
                  averageScore
                  coverImage {
                    large
                  }
                  genres
                  description
                  status
                }
              }
            }
            """;

    // GraphQL query for the embedding populator — includes tags (name + rank) for richer semantic signal.
    // Tags are user-voted descriptors like "Time Travel", "Anti-Hero", "Mind Games" with a relevance rank.
    // Sorted by POPULARITY_DESC so we embed the most popular anime first.
    private static final String POPULATE_QUERY = """
            query ($page: Int, $perPage: Int) {
              Page(page: $page, perPage: $perPage) {
                media(type: ANIME, sort: POPULARITY_DESC) {
                  id
                  title {
                    romaji
                    english
                  }
                  episodes
                  averageScore
                  coverImage {
                    large
                  }
                  genres
                  tags {
                    name
                    rank
                  }
                  description
                  status
                }
              }
            }
            """;

    /**
     * Fetches a page of anime sorted by popularity (most popular first).
     * Includes tags for richer embedding text. Used by the populator service
     * to bulk-scrape anime for the embeddings database.
     */
    public List<AniListResponse.AnimeInfo> fetchPopularAnimePage(int page, int perPage) {
        Map<String, Object> requestBody = Map.of(
                "query", POPULATE_QUERY,
                "variables", Map.of("page", page, "perPage", perPage)
        );

        AniListResponse response = webClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(AniListResponse.class)
                .block();

        if (response == null || response.getData() == null
                || response.getData().getPage() == null
                || response.getData().getPage().getMedia() == null) {
            return Collections.emptyList();
        }

        return response.getData().getPage().getMedia();
    }

    /**
     * Searches AniList for top-rated anime in the given genres. Used by
     * RecommendationService to find anime similar to the user's taste. Returns
     * up to perPage results sorted by score descending.
     */
    public List<AniListResponse.AnimeInfo> searchByGenres(List<String> genres, int page, int perPage) {
        Map<String, Object> requestBody = Map.of(
                "query", GENRE_SEARCH_QUERY,
                "variables", Map.of("genres", genres, "page", page, "perPage", perPage)
        );

        AniListResponse response = webClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(AniListResponse.class)
                .block();

        if (response == null || response.getData() == null
                || response.getData().getPage() == null
                || response.getData().getPage().getMedia() == null) {
            return Collections.emptyList();
        }

        return response.getData().getPage().getMedia();
    }

}
