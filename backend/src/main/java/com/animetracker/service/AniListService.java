package com.animetracker.service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.animetracker.dto.AniListResponse;

/**
 * Thin adapter around AniList GraphQL.
 * Centralizes query definitions, timeout behavior, and error handling.
 */
@Service
public class AniListService {

    private static final Logger log = LoggerFactory.getLogger(AniListService.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);

    private final WebClient webClient;

    public AniListService() {
        this.webClient = WebClient.builder()
                .baseUrl("https://graphql.anilist.co")
                .defaultHeader("User-Agent", "animetracker/1.0")
                .build();
    }

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

    public List<AniListResponse.AnimeInfo> searchAnime(String query) {
        Map<String, Object> requestBody = Map.of(
                "query", SEARCH_QUERY,
                "variables", Map.of("search", query));

        AniListResponse response = executeGraphql(requestBody);
        if (response == null || response.getData() == null
                || response.getData().getPage() == null
                || response.getData().getPage().getMedia() == null) {
            return Collections.emptyList();
        }
        return response.getData().getPage().getMedia();
    }

    public AniListResponse.AnimeInfo getAnimeById(Integer id) {
        Map<String, Object> requestBody = Map.of(
                "query", GET_BY_ID_QUERY,
                "variables", Map.of("id", id));

        AniListResponse response = executeGraphql(requestBody);
        if (response == null || response.getData() == null
                || response.getData().getPage() == null
                || response.getData().getPage().getMedia() == null
                || response.getData().getPage().getMedia().isEmpty()) {
            return null;
        }
        return response.getData().getPage().getMedia().get(0);
    }

    public List<AniListResponse.AnimeInfo> fetchPopularAnimePage(int page, int perPage) {
        Map<String, Object> requestBody = Map.of(
                "query", POPULATE_QUERY,
                "variables", Map.of("page", page, "perPage", perPage));

        AniListResponse response = executeGraphql(requestBody);
        if (response == null || response.getData() == null
                || response.getData().getPage() == null
                || response.getData().getPage().getMedia() == null) {
            return Collections.emptyList();
        }
        return response.getData().getPage().getMedia();
    }

    private AniListResponse executeGraphql(Map<String, Object> requestBody) {
        try {
            return webClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(AniListResponse.class)
                    .block(REQUEST_TIMEOUT);
        } catch (WebClientResponseException ex) {
            log.warn("AniList request failed: status={} body={}",
                    ex.getStatusCode().value(), ex.getResponseBodyAsString());
            return null;
        } catch (Exception ex) {
            log.warn("AniList request failed: {}", ex.getMessage());
            return null;
        }
    }
}
