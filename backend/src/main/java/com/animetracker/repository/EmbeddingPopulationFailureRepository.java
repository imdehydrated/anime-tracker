package com.animetracker.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.animetracker.service.EmbeddingFailureReason;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

@Repository
public class EmbeddingPopulationFailureRepository {

    private final JdbcTemplate jdbcTemplate;
    private final long deadLetterHoldMs;
    private final RetryPolicy rateLimitPolicy;
    private final RetryPolicy upstreamPolicy;
    private final RetryPolicy networkPolicy;
    private final RetryPolicy embedPolicy;
    private final RetryPolicy unknownPolicy;
    private final int missingMetadataMaxAttempts;
    private final int validationMaxAttempts;

    public EmbeddingPopulationFailureRepository(
            JdbcTemplate jdbcTemplate,
            @Value("${recommendations.metadata-sync.failure-policy.dead-letter-hold-ms:86400000}") long deadLetterHoldMs,
            @Value("${recommendations.metadata-sync.failure-policy.rate-limit-max-attempts:8}") int rateLimitMaxAttempts,
            @Value("${recommendations.metadata-sync.failure-policy.rate-limit-initial-delay-ms:900000}") long rateLimitInitialDelayMs,
            @Value("${recommendations.metadata-sync.failure-policy.rate-limit-max-delay-ms:7200000}") long rateLimitMaxDelayMs,
            @Value("${recommendations.metadata-sync.failure-policy.rate-limit-backoff-multiplier:2.0}") double rateLimitBackoffMultiplier,
            @Value("${recommendations.metadata-sync.failure-policy.upstream-max-attempts:5}") int upstreamMaxAttempts,
            @Value("${recommendations.metadata-sync.failure-policy.upstream-initial-delay-ms:300000}") long upstreamInitialDelayMs,
            @Value("${recommendations.metadata-sync.failure-policy.upstream-max-delay-ms:3600000}") long upstreamMaxDelayMs,
            @Value("${recommendations.metadata-sync.failure-policy.upstream-backoff-multiplier:2.0}") double upstreamBackoffMultiplier,
            @Value("${recommendations.metadata-sync.failure-policy.network-max-attempts:5}") int networkMaxAttempts,
            @Value("${recommendations.metadata-sync.failure-policy.network-initial-delay-ms:300000}") long networkInitialDelayMs,
            @Value("${recommendations.metadata-sync.failure-policy.network-max-delay-ms:3600000}") long networkMaxDelayMs,
            @Value("${recommendations.metadata-sync.failure-policy.network-backoff-multiplier:2.0}") double networkBackoffMultiplier,
            @Value("${recommendations.metadata-sync.failure-policy.embed-max-attempts:3}") int embedMaxAttempts,
            @Value("${recommendations.metadata-sync.failure-policy.embed-initial-delay-ms:120000}") long embedInitialDelayMs,
            @Value("${recommendations.metadata-sync.failure-policy.embed-max-delay-ms:900000}") long embedMaxDelayMs,
            @Value("${recommendations.metadata-sync.failure-policy.embed-backoff-multiplier:2.0}") double embedBackoffMultiplier,
            @Value("${recommendations.metadata-sync.failure-policy.unknown-max-attempts:4}") int unknownMaxAttempts,
            @Value("${recommendations.metadata-sync.failure-policy.unknown-initial-delay-ms:300000}") long unknownInitialDelayMs,
            @Value("${recommendations.metadata-sync.failure-policy.unknown-max-delay-ms:1800000}") long unknownMaxDelayMs,
            @Value("${recommendations.metadata-sync.failure-policy.unknown-backoff-multiplier:2.0}") double unknownBackoffMultiplier,
            @Value("${recommendations.metadata-sync.failure-policy.missing-metadata-max-attempts:1}") int missingMetadataMaxAttempts,
            @Value("${recommendations.metadata-sync.failure-policy.validation-max-attempts:1}") int validationMaxAttempts) {
        this.jdbcTemplate = jdbcTemplate;
        this.deadLetterHoldMs = Math.max(60_000L, deadLetterHoldMs);
        this.rateLimitPolicy = RetryPolicy.of(rateLimitMaxAttempts, rateLimitInitialDelayMs, rateLimitMaxDelayMs, rateLimitBackoffMultiplier);
        this.upstreamPolicy = RetryPolicy.of(upstreamMaxAttempts, upstreamInitialDelayMs, upstreamMaxDelayMs, upstreamBackoffMultiplier);
        this.networkPolicy = RetryPolicy.of(networkMaxAttempts, networkInitialDelayMs, networkMaxDelayMs, networkBackoffMultiplier);
        this.embedPolicy = RetryPolicy.of(embedMaxAttempts, embedInitialDelayMs, embedMaxDelayMs, embedBackoffMultiplier);
        this.unknownPolicy = RetryPolicy.of(unknownMaxAttempts, unknownInitialDelayMs, unknownMaxDelayMs, unknownBackoffMultiplier);
        this.missingMetadataMaxAttempts = Math.max(1, missingMetadataMaxAttempts);
        this.validationMaxAttempts = Math.max(1, validationMaxAttempts);
    }

    public void recordFailure(
            Integer anilistId,
            String source,
            EmbeddingFailureReason failureReason,
            String errorMessage) {
        if (anilistId == null || anilistId <= 0 || source == null || source.isBlank()) {
            return;
        }

        String normalizedSource = source.trim();
        EmbeddingFailureReason reason = failureReason == null ? EmbeddingFailureReason.UNKNOWN : failureReason;
        int existingAttempts = findExistingAttempts(anilistId, normalizedSource);
        int nextAttempts = existingAttempts + 1;
        RetryDecision decision = resolveRetryDecision(reason, nextAttempts);
        String err = truncateError(errorMessage);
        jdbcTemplate.update(
                """
                INSERT INTO embedding_population_failures
                    (anilist_id, source, failure_reason, last_error, attempts, status, last_attempt_at, next_retry_at, created_at, updated_at)
                VALUES
                    (?, ?, ?, ?, ?, ?, NOW(), ?, NOW(), NOW())
                ON CONFLICT (anilist_id, source) DO UPDATE SET
                    failure_reason = EXCLUDED.failure_reason,
                    last_error = EXCLUDED.last_error,
                    attempts = EXCLUDED.attempts,
                    status = EXCLUDED.status,
                    last_attempt_at = NOW(),
                    next_retry_at = EXCLUDED.next_retry_at,
                    updated_at = NOW()
                """,
                anilistId,
                normalizedSource,
                reason.dbValue(),
                err,
                nextAttempts,
                decision.status(),
                Timestamp.from(decision.nextRetryAt()));
    }

    public void markResolved(Integer anilistId, String source) {
        if (anilistId == null || anilistId <= 0 || source == null || source.isBlank()) {
            return;
        }
        jdbcTemplate.update(
                """
                UPDATE embedding_population_failures
                SET status = 'RESOLVED',
                    last_error = NULL,
                    updated_at = NOW()
                WHERE anilist_id = ?
                  AND source = ?
                """,
                anilistId,
                source.trim());
    }

    public List<PopulationFailure> findFailures(String source, String status, int limit) {
        int safeLimit = Math.max(1, Math.min(500, limit));
        String normalizedSource = normalizeBlank(source);
        String normalizedStatus = normalizeStatus(status);
        StringBuilder sql = new StringBuilder("""
                SELECT id, anilist_id, source, failure_reason, last_error, attempts, status, last_attempt_at, next_retry_at, created_at, updated_at
                FROM embedding_population_failures
                WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (normalizedSource != null) {
            sql.append(" AND source = ?");
            args.add(normalizedSource);
        }
        if (normalizedStatus != null) {
            sql.append(" AND status = ?");
            args.add(normalizedStatus);
        }
        sql.append(" ORDER BY updated_at DESC LIMIT ?");
        args.add(safeLimit);
        return jdbcTemplate.query(sql.toString(), this::mapFailure, args.toArray());
    }

    public List<PopulationFailure> findRetryableFailures(String source, int limit) {
        int safeLimit = Math.max(1, Math.min(500, limit));
        String normalizedSource = normalizeBlank(source);
        StringBuilder sql = new StringBuilder("""
                SELECT id, anilist_id, source, failure_reason, last_error, attempts, status, last_attempt_at, next_retry_at, created_at, updated_at
                FROM embedding_population_failures
                WHERE status = 'OPEN'
                  AND next_retry_at <= NOW()
                """);
        List<Object> args = new ArrayList<>();
        if (normalizedSource != null) {
            sql.append(" AND source = ?");
            args.add(normalizedSource);
        }
        sql.append(" ORDER BY next_retry_at ASC, updated_at ASC LIMIT ?");
        args.add(safeLimit);
        return jdbcTemplate.query(sql.toString(), this::mapFailure, args.toArray());
    }

    public FailureSummary summarize(String source) {
        String normalizedSource = normalizeBlank(source);
        StringBuilder sql = new StringBuilder("""
                SELECT
                    COUNT(*) AS total,
                    COUNT(*) FILTER (WHERE status = 'OPEN') AS open_count,
                    COUNT(*) FILTER (WHERE status = 'DEAD_LETTER') AS dead_letter_count,
                    COUNT(*) FILTER (WHERE status = 'RESOLVED') AS resolved_count
                FROM embedding_population_failures
                WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (normalizedSource != null) {
            sql.append(" AND source = ?");
            args.add(normalizedSource);
        }
        return jdbcTemplate.query(
                sql.toString(),
                rs -> {
                    if (!rs.next()) {
                        return new FailureSummary(0L, 0L, 0L, 0L);
                    }
                    return new FailureSummary(
                            rs.getLong("total"),
                            rs.getLong("open_count"),
                            rs.getLong("dead_letter_count"),
                            rs.getLong("resolved_count"));
                },
                args.toArray());
    }

    public Map<EmbeddingFailureReason, Long> summarizeByReason(String source, String status) {
        String normalizedSource = normalizeBlank(source);
        String normalizedStatus = normalizeStatus(status);
        StringBuilder sql = new StringBuilder("""
                SELECT failure_reason, COUNT(*) AS reason_count
                FROM embedding_population_failures
                WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (normalizedSource != null) {
            sql.append(" AND source = ?");
            args.add(normalizedSource);
        }
        if (normalizedStatus != null) {
            sql.append(" AND status = ?");
            args.add(normalizedStatus);
        }
        sql.append(" GROUP BY failure_reason ORDER BY failure_reason ASC");

        Map<EmbeddingFailureReason, Long> out = new LinkedHashMap<>();
        for (EmbeddingFailureReason value : EmbeddingFailureReason.values()) {
            out.put(value, 0L);
        }
        jdbcTemplate.query(
                sql.toString(),
                rs -> {
                    EmbeddingFailureReason reason = EmbeddingFailureReason.fromStoredValue(rs.getString("failure_reason"));
                    out.put(reason, rs.getLong("reason_count"));
                },
                args.toArray());
        return out;
    }

    private PopulationFailure mapFailure(ResultSet rs, int rowNum) throws SQLException {
        return new PopulationFailure(
                rs.getLong("id"),
                rs.getInt("anilist_id"),
                rs.getString("source"),
                rs.getString("failure_reason"),
                rs.getString("last_error"),
                rs.getInt("attempts"),
                rs.getString("status"),
                toInstant(rs.getTimestamp("last_attempt_at")),
                toInstant(rs.getTimestamp("next_retry_at")),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private int findExistingAttempts(Integer anilistId, String source) {
        List<Integer> attempts = jdbcTemplate.query(
                """
                SELECT attempts
                FROM embedding_population_failures
                WHERE anilist_id = ?
                  AND source = ?
                """,
                (rs, rowNum) -> rs.getInt("attempts"),
                anilistId,
                source);
        if (attempts == null || attempts.isEmpty()) {
            return 0;
        }
        return Math.max(0, attempts.get(0));
    }

    private RetryDecision resolveRetryDecision(EmbeddingFailureReason reason, int attempts) {
        EmbeddingFailureReason effectiveReason = reason == null ? EmbeddingFailureReason.UNKNOWN : reason;
        Instant now = Instant.now();

        if (isHardInvalid(effectiveReason)) {
            return new RetryDecision("DEAD_LETTER", now.plusMillis(deadLetterHoldMs));
        }

        RetryPolicy policy = policyFor(effectiveReason);
        if (attempts >= policy.maxAttempts()) {
            return new RetryDecision("DEAD_LETTER", now.plusMillis(deadLetterHoldMs));
        }

        int step = Math.max(0, attempts - 1);
        long delayMs = policy.delayForAttempt(step);
        return new RetryDecision("OPEN", now.plusMillis(delayMs));
    }

    private boolean isHardInvalid(EmbeddingFailureReason reason) {
        if (reason == EmbeddingFailureReason.VALIDATION) {
            return validationMaxAttempts <= 1;
        }
        if (reason == EmbeddingFailureReason.MISSING_METADATA) {
            return missingMetadataMaxAttempts <= 1;
        }
        return false;
    }

    private RetryPolicy policyFor(EmbeddingFailureReason reason) {
        return switch (reason) {
            case RATE_LIMIT -> rateLimitPolicy;
            case UPSTREAM_5XX -> upstreamPolicy;
            case NETWORK_TIMEOUT -> networkPolicy;
            case EMBED_FAILURE -> embedPolicy;
            case MISSING_METADATA -> RetryPolicy.of(
                    missingMetadataMaxAttempts,
                    unknownPolicy.initialDelayMs(),
                    unknownPolicy.maxDelayMs(),
                    unknownPolicy.backoffMultiplier());
            case VALIDATION -> RetryPolicy.of(
                    validationMaxAttempts,
                    unknownPolicy.initialDelayMs(),
                    unknownPolicy.maxDelayMs(),
                    unknownPolicy.backoffMultiplier());
            case UNKNOWN -> unknownPolicy;
        };
    }

    private String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 2000 ? error : error.substring(0, 2000);
    }

    private String normalizeBlank(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record PopulationFailure(
            long id,
            int anilistId,
            String source,
            String failureReason,
            String lastError,
            int attempts,
            String status,
            Instant lastAttemptAt,
            Instant nextRetryAt,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record FailureSummary(
            long total,
            long openCount,
            long deadLetterCount,
            long resolvedCount) {
    }

    private record RetryDecision(String status, Instant nextRetryAt) {
    }

    private record RetryPolicy(
            int maxAttempts,
            long initialDelayMs,
            long maxDelayMs,
            double backoffMultiplier) {
        private static RetryPolicy of(
                int maxAttempts,
                long initialDelayMs,
                long maxDelayMs,
                double backoffMultiplier) {
            return new RetryPolicy(
                    Math.max(1, maxAttempts),
                    Math.max(60_000L, initialDelayMs),
                    Math.max(60_000L, maxDelayMs),
                    Math.max(1.0d, backoffMultiplier));
        }

        private long delayForAttempt(int step) {
            double raw = initialDelayMs * Math.pow(backoffMultiplier, Math.max(0, step));
            long bounded = (long) Math.min(raw, maxDelayMs);
            return Math.max(60_000L, bounded);
        }
    }
}
