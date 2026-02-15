package com.animetracker.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request payload for adding a new anime to the authenticated user's list.
 */
public record AddAnimeRequest(
        @NotNull(message = "anilistId is required")
        @Positive(message = "anilistId must be positive")
        Integer anilistId,
        @Size(max = 30, message = "status must be at most 30 characters")
        String status,
        @Size(max = 500, message = "title must be at most 500 characters")
        String title,
        @Size(max = 500, message = "coverImage must be at most 500 characters")
        String coverImage,
        String genres,
        Integer totalEpisodes) {
}
