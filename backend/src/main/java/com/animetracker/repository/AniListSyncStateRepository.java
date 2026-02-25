package com.animetracker.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AniListSyncStateRepository {

    private final JdbcTemplate jdbcTemplate;

    public AniListSyncStateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<SyncState> findBySourceKey(String sourceKey) {
        return jdbcTemplate.query(
                """
                SELECT source_key, next_page, last_success_at, last_error, last_run_at, budget_state
                FROM anilist_sync_state
                WHERE source_key = ?
                """,
                this::mapState,
                sourceKey).stream().findFirst();
    }

    public SyncState findOrCreate(String sourceKey, int defaultNextPage, String defaultBudgetState) {
        return findBySourceKey(sourceKey).orElseGet(() -> {
            SyncState created = new SyncState(
                    sourceKey,
                    Math.max(1, defaultNextPage),
                    null,
                    null,
                    null,
                    defaultBudgetState);
            upsert(created);
            return created;
        });
    }

    public void upsert(SyncState state) {
        jdbcTemplate.update(
                """
                INSERT INTO anilist_sync_state
                    (source_key, next_page, last_success_at, last_error, last_run_at, budget_state)
                VALUES
                    (?, ?, ?, ?, ?, ?)
                ON CONFLICT (source_key) DO UPDATE SET
                    next_page = EXCLUDED.next_page,
                    last_success_at = EXCLUDED.last_success_at,
                    last_error = EXCLUDED.last_error,
                    last_run_at = EXCLUDED.last_run_at,
                    budget_state = EXCLUDED.budget_state
                """,
                state.sourceKey(),
                Math.max(1, state.nextPage()),
                toTimestamp(state.lastSuccessAt()),
                state.lastError(),
                toTimestamp(state.lastRunAt()),
                state.budgetState());
    }

    public void markSuccess(String sourceKey, int nextPage, String budgetState, Instant now) {
        upsert(new SyncState(
                sourceKey,
                Math.max(1, nextPage),
                now,
                null,
                now,
                budgetState));
    }

    public void markFailure(String sourceKey, int nextPage, String error, String budgetState, Instant now) {
        upsert(new SyncState(
                sourceKey,
                Math.max(1, nextPage),
                null,
                truncateError(error),
                now,
                budgetState));
    }

    private String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 2000 ? error : error.substring(0, 2000);
    }

    private SyncState mapState(ResultSet rs, int rowNum) throws SQLException {
        return new SyncState(
                rs.getString("source_key"),
                rs.getInt("next_page"),
                toInstant(rs.getTimestamp("last_success_at")),
                rs.getString("last_error"),
                toInstant(rs.getTimestamp("last_run_at")),
                rs.getString("budget_state"));
    }

    private Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record SyncState(
            String sourceKey,
            int nextPage,
            Instant lastSuccessAt,
            String lastError,
            Instant lastRunAt,
            String budgetState) {
    }
}
