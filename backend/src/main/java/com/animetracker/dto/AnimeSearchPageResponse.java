package com.animetracker.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Additive cursor-paged response for /api/anime/search/paged.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnimeSearchPageResponse(
        List<AniListResponse.AnimeInfo> items,
        String nextCursor,
        boolean hasMore,
        Map<String, Object> diagnostics) {
    public AnimeSearchPageResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
