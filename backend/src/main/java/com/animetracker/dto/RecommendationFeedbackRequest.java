package com.animetracker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RecommendationFeedbackRequest(
        @NotNull(message = "anilistId is required") Integer anilistId,
        @NotBlank(message = "signal is required") String signal,
        String sourceMode,
        String queryContext,
        String title,
        String coverImage) {
}
