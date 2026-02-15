package com.animetracker.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request payload for adding an anime to a user's recommendation blacklist.
 */
public record RecommendationBlacklistRequest(
        @NotNull(message = "anilistId is required")
        @Positive(message = "anilistId must be positive")
        Integer anilistId,
        @Size(max = 500, message = "title must be at most 500 characters")
        String title,
        @Size(max = 500, message = "coverImage must be at most 500 characters")
        String coverImage) {
}
