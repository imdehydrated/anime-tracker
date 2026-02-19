package com.animetracker.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Wrapper DTO for recommendation responses that can include scoring metadata.
 * Phase 1 infrastructure only: existing endpoints may still return AnimeInfo directly.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecommendationResponse {

    public static final String SIMILAR_TO_SEED = "SIMILAR_TO_SEED";
    public static final String MATCHES_QUERY = "MATCHES_QUERY";
    public static final String MATCHES_TASTE_PROFILE = "MATCHES_TASTE_PROFILE";
    public static final String CF_SIGNAL = "CF_SIGNAL";

    private final AniListResponse.AnimeInfo anime;
    private final Double fusionScore;
    private final List<String> reasonCodes;

    public RecommendationResponse(AniListResponse.AnimeInfo anime, Double fusionScore, List<String> reasonCodes) {
        this.anime = anime;
        this.fusionScore = fusionScore;
        this.reasonCodes = (reasonCodes == null || reasonCodes.isEmpty()) ? null : List.copyOf(reasonCodes);
    }

    public AniListResponse.AnimeInfo getAnime() {
        return anime;
    }

    public Double getFusionScore() {
        return fusionScore;
    }

    public List<String> getReasonCodes() {
        return reasonCodes;
    }
}
