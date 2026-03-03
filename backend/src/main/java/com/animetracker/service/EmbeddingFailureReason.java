package com.animetracker.service;

import java.util.Locale;

/**
 * Canonical failure taxonomy for embedding population/retry flows.
 * Keep these stable for reporting and policy configuration.
 */
public enum EmbeddingFailureReason {
    RATE_LIMIT("rate_limit"),
    UPSTREAM_5XX("upstream_5xx"),
    NETWORK_TIMEOUT("network_timeout"),
    MISSING_METADATA("missing_metadata"),
    EMBED_FAILURE("embed_failure"),
    VALIDATION("validation"),
    UNKNOWN("unknown");

    private final String dbValue;

    EmbeddingFailureReason(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static EmbeddingFailureReason fromStoredValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "rate_limit" -> RATE_LIMIT;
            case "upstream_5xx", "upstream5xx" -> UPSTREAM_5XX;
            case "network_timeout", "network", "timeout" -> NETWORK_TIMEOUT;
            case "missing_metadata" -> MISSING_METADATA;
            case "embed_failure" -> EMBED_FAILURE;
            case "validation" -> VALIDATION;
            default -> UNKNOWN;
        };
    }
}

