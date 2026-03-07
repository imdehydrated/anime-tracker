package com.animetracker.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Additive paged response contract for recommendation endpoints.
 * Keeps existing scored endpoint stable while enabling lazy loading/infinite scroll.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecommendationPageResponse {

    private final List<RecommendationResponse> items;
    private final String nextCursor;
    private final boolean hasMore;
    private final Map<String, Object> diagnostics;

    public RecommendationPageResponse(
            List<RecommendationResponse> items,
            String nextCursor,
            boolean hasMore,
            Map<String, Object> diagnostics) {
        this.items = items == null ? List.of() : List.copyOf(items);
        this.nextCursor = nextCursor;
        this.hasMore = hasMore;
        this.diagnostics = diagnostics;
    }

    public List<RecommendationResponse> getItems() {
        return items;
    }

    public String getNextCursor() {
        return nextCursor;
    }

    public boolean isHasMore() {
        return hasMore;
    }

    public Map<String, Object> getDiagnostics() {
        return diagnostics;
    }
}

