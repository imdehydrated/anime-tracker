package com.animetracker.service;

import com.animetracker.dto.AniListResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;

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
                .bodyToMono(AniListResponse.class)  // Deserialize JSON → AniListResponse
                .block();                             // Wait for the response (synchronous)

        // Safely extract the media list, returning empty list if anything is null
        if (response == null || response.getData() == null
                || response.getData().getPage() == null
                || response.getData().getPage().getMedia() == null) {
            return Collections.emptyList();
        }

        return response.getData().getPage().getMedia();
    }
}
