package com.animetracker.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CustomEmbeddingImportStateRepository {

    private final JdbcTemplate jdbcTemplate;

    public CustomEmbeddingImportStateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ImportState> findCurrent() {
        return jdbcTemplate.query(
                """
                SELECT source_path, source_last_modified, source_size_bytes, source_sha256, imported_at
                FROM custom_embedding_import_state
                WHERE id = 1
                """,
                this::mapState).stream().findFirst();
    }

    public void upsert(ImportState state) {
        jdbcTemplate.update(
                """
                INSERT INTO custom_embedding_import_state
                    (id, source_path, source_last_modified, source_size_bytes, source_sha256, imported_at)
                VALUES
                    (1, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    source_path = EXCLUDED.source_path,
                    source_last_modified = EXCLUDED.source_last_modified,
                    source_size_bytes = EXCLUDED.source_size_bytes,
                    source_sha256 = EXCLUDED.source_sha256,
                    imported_at = EXCLUDED.imported_at
                """,
                state.sourcePath(),
                toTimestamp(state.sourceLastModified()),
                state.sourceSizeBytes(),
                state.sourceSha256(),
                toTimestamp(state.importedAt()));
    }

    private ImportState mapState(ResultSet rs, int rowNum) throws SQLException {
        return new ImportState(
                rs.getString("source_path"),
                toInstant(rs.getTimestamp("source_last_modified")),
                rs.getLong("source_size_bytes"),
                rs.getString("source_sha256"),
                toInstant(rs.getTimestamp("imported_at")));
    }

    private Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record ImportState(
            String sourcePath,
            Instant sourceLastModified,
            long sourceSizeBytes,
            String sourceSha256,
            Instant importedAt) {
    }
}
