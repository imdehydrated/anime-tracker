package com.animetracker.repository;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AnimeRelationGraphRepository {

    private final JdbcTemplate jdbcTemplate;

    public AnimeRelationGraphRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public RelationGraphRebuildStats rebuildFromCatalogMetadata() {
        Long edgesBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM anime_relation_graph",
                Long.class);
        jdbcTemplate.execute("TRUNCATE TABLE anime_relation_graph");

        int inserted = jdbcTemplate.update("""
                INSERT INTO anime_relation_graph
                    (anime_id, related_anime_id, relation_type, created_at, updated_at)
                SELECT DISTINCT
                    c.anilist_id AS anime_id,
                    CASE
                        WHEN (rel->>'id') ~ '^[0-9]+$' THEN (rel->>'id')::int
                        WHEN (rel->'node'->>'id') ~ '^[0-9]+$' THEN (rel->'node'->>'id')::int
                        ELSE NULL
                    END AS related_anime_id,
                    UPPER(REPLACE(REPLACE(
                        COALESCE(rel->>'relationType', rel->>'relation_type'),
                        ' ',
                        '_'),
                        '-',
                        '_')) AS relation_type,
                    NOW(),
                    NOW()
                FROM anime_catalog c
                CROSS JOIN LATERAL jsonb_array_elements(
                    CASE
                        WHEN c.metadata_json IS NULL THEN '[]'::jsonb
                        WHEN jsonb_typeof((c.metadata_json::jsonb)->'relations') = 'array'
                            THEN (c.metadata_json::jsonb)->'relations'
                        WHEN jsonb_typeof((c.metadata_json::jsonb)->'relations'->'edges') = 'array'
                            THEN (c.metadata_json::jsonb)->'relations'->'edges'
                        ELSE '[]'::jsonb
                    END
                ) rel
                WHERE
                    COALESCE(rel->>'relationType', rel->>'relation_type', '') <> ''
                    AND (
                        (rel->>'id') ~ '^[0-9]+$'
                        OR (rel->'node'->>'id') ~ '^[0-9]+$'
                    )
                """);

        Long animeWithEdges = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT anime_id) FROM anime_relation_graph",
                Long.class);
        Long edgesAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM anime_relation_graph",
                Long.class);

        return new RelationGraphRebuildStats(
                edgesBefore == null ? 0L : edgesBefore,
                inserted,
                edgesAfter == null ? 0L : edgesAfter,
                animeWithEdges == null ? 0L : animeWithEdges);
    }

    @Transactional
    public void replaceRelations(Integer animeId, List<RelationEdge> relations) {
        if (animeId == null || animeId <= 0) {
            return;
        }
        jdbcTemplate.update(
                "DELETE FROM anime_relation_graph WHERE anime_id = ?",
                animeId);
        if (relations == null || relations.isEmpty()) {
            return;
        }

        String sql = """
                INSERT INTO anime_relation_graph
                    (anime_id, related_anime_id, relation_type, created_at, updated_at)
                VALUES (?, ?, ?, NOW(), NOW())
                ON CONFLICT (anime_id, related_anime_id, relation_type) DO UPDATE SET
                    updated_at = NOW()
                """;
        jdbcTemplate.batchUpdate(
                sql,
                relations,
                relations.size(),
                (PreparedStatement ps, RelationEdge edge) -> {
                    ps.setInt(1, edge.animeId());
                    ps.setInt(2, edge.relatedAnimeId());
                    ps.setString(3, edge.relationType());
                });
    }

    public Set<Integer> findAnimeIdsHavingRelationType(
            Collection<Integer> animeIds,
            Collection<String> relationTypes) {
        if (animeIds == null || animeIds.isEmpty() || relationTypes == null || relationTypes.isEmpty()) {
            return Set.of();
        }
        List<Integer> safeIds = animeIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        List<String> safeTypes = normalizeTypes(relationTypes);
        if (safeIds.isEmpty() || safeTypes.isEmpty()) {
            return Set.of();
        }

        String idsClause = placeholders(safeIds.size());
        String typesClause = placeholders(safeTypes.size());
        String sql = "SELECT DISTINCT anime_id FROM anime_relation_graph WHERE anime_id IN (" + idsClause
                + ") AND relation_type IN (" + typesClause + ")";
        List<Object> args = new ArrayList<>(safeIds.size() + safeTypes.size());
        args.addAll(safeIds);
        args.addAll(safeTypes);
        List<Integer> rows = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> rs.getInt(1),
                args.toArray());
        return rows == null || rows.isEmpty() ? Set.of() : Set.copyOf(rows);
    }

    public List<Integer> findRelatedAnimeIds(Integer animeId, Collection<String> relationTypes) {
        if (animeId == null || animeId <= 0 || relationTypes == null || relationTypes.isEmpty()) {
            return List.of();
        }
        List<String> safeTypes = normalizeTypes(relationTypes);
        if (safeTypes.isEmpty()) {
            return List.of();
        }
        String typesClause = placeholders(safeTypes.size());
        String sql = "SELECT related_anime_id FROM anime_relation_graph WHERE anime_id = ? AND relation_type IN ("
                + typesClause + ")";
        List<Object> args = new ArrayList<>(1 + safeTypes.size());
        args.add(animeId);
        args.addAll(safeTypes);
        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> rs.getInt(1),
                args.toArray());
    }

    public Integer resolveEntrypoint(Integer startAnimeId, Collection<String> relationTypes, int maxDepth) {
        if (startAnimeId == null || startAnimeId <= 0 || relationTypes == null || relationTypes.isEmpty()) {
            return startAnimeId;
        }
        int safeDepth = Math.max(1, maxDepth);
        List<String> safeTypes = normalizeTypes(relationTypes);
        if (safeTypes.isEmpty()) {
            return startAnimeId;
        }

        Integer cursor = startAnimeId;
        Set<Integer> visited = new LinkedHashSet<>();
        for (int depth = 0; depth < safeDepth; depth++) {
            if (cursor == null || cursor <= 0 || !visited.add(cursor)) {
                break;
            }
            List<Integer> nextCandidates = findRelatedAnimeIds(cursor, safeTypes);
            if (nextCandidates == null || nextCandidates.isEmpty()) {
                break;
            }
            cursor = nextCandidates.stream()
                    .filter(id -> id != null && id > 0)
                    .min(Integer::compareTo)
                    .orElse(cursor);
        }
        return cursor == null ? startAnimeId : cursor;
    }

    public List<RelatedAnimeRecord> findBidirectionalRelatedAnime(Integer animeId, int limit) {
        if (animeId == null || animeId <= 0 || limit <= 0) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(200, limit));
        String sql = """
                SELECT rel.related_id,
                       rel.relation_type,
                       c.title_romaji,
                       c.title_english,
                       c.title_native
                FROM (
                    SELECT related_anime_id AS related_id,
                           relation_type
                    FROM anime_relation_graph
                    WHERE anime_id = ?
                    UNION ALL
                    SELECT anime_id AS related_id,
                           CASE relation_type
                               WHEN 'PREQUEL' THEN 'SEQUEL'
                               WHEN 'SEQUEL' THEN 'PREQUEL'
                               WHEN 'PARENT' THEN 'CHILD'
                               WHEN 'PARENT_STORY' THEN 'CHILD_STORY'
                               ELSE relation_type
                           END AS relation_type
                    FROM anime_relation_graph
                    WHERE related_anime_id = ?
                ) rel
                INNER JOIN anime_catalog c
                    ON c.anilist_id = rel.related_id
                WHERE rel.related_id IS NOT NULL
                  AND rel.related_id <> ?
                LIMIT ?
                """;
        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new RelatedAnimeRecord(
                        rs.getInt("related_id"),
                        rs.getString("relation_type"),
                        rs.getString("title_romaji"),
                        rs.getString("title_english"),
                        rs.getString("title_native")),
                animeId,
                animeId,
                animeId,
                safeLimit);
    }

    public Set<Integer> collectConnectedSeasonIds(
            Collection<Integer> rootAnimeIds,
            Collection<String> relationTypes,
            int maxDepth) {
        if (rootAnimeIds == null || rootAnimeIds.isEmpty() || relationTypes == null || relationTypes.isEmpty()) {
            return Set.of();
        }
        int safeDepth = Math.max(1, maxDepth);
        List<String> safeTypes = normalizeTypes(relationTypes);
        if (safeTypes.isEmpty()) {
            return Set.of();
        }

        Set<Integer> visited = new HashSet<>();
        ArrayDeque<Integer> frontier = new ArrayDeque<>();
        for (Integer id : rootAnimeIds) {
            if (id != null && id > 0) {
                visited.add(id);
                frontier.add(id);
            }
        }
        if (frontier.isEmpty()) {
            return Set.of();
        }

        for (int depth = 0; depth < safeDepth && !frontier.isEmpty(); depth++) {
            List<Integer> layerIds = new ArrayList<>(frontier.size());
            while (!frontier.isEmpty()) {
                layerIds.add(frontier.poll());
            }
            Map<Integer, List<Integer>> neighborsByAnime = findRelationsBySources(layerIds, safeTypes);
            for (Map.Entry<Integer, List<Integer>> entry : neighborsByAnime.entrySet()) {
                List<Integer> neighbors = entry.getValue();
                if (neighbors == null) {
                    continue;
                }
                for (Integer neighbor : neighbors) {
                    if (neighbor == null || neighbor <= 0) {
                        continue;
                    }
                    if (visited.add(neighbor)) {
                        frontier.add(neighbor);
                    }
                }
            }
        }
        return visited;
    }

    private Map<Integer, List<Integer>> findRelationsBySources(
            List<Integer> sourceIds,
            List<String> relationTypes) {
        if (sourceIds == null || sourceIds.isEmpty() || relationTypes == null || relationTypes.isEmpty()) {
            return Map.of();
        }
        String idsClause = placeholders(sourceIds.size());
        String typesClause = placeholders(relationTypes.size());
        String sql = "SELECT anime_id, related_anime_id FROM anime_relation_graph WHERE anime_id IN (" + idsClause
                + ") AND relation_type IN (" + typesClause + ")";
        List<Object> args = new ArrayList<>(sourceIds.size() + relationTypes.size());
        args.addAll(sourceIds);
        args.addAll(relationTypes);
        ResultSetExtractor<Map<Integer, List<Integer>>> extractor = rs -> {
            Map<Integer, List<Integer>> out = new java.util.HashMap<>();
            while (rs.next()) {
                int animeId = rs.getInt(1);
                int relatedId = rs.getInt(2);
                out.computeIfAbsent(animeId, ignored -> new ArrayList<>()).add(relatedId);
            }
            return out;
        };
        return jdbcTemplate.query(sql, extractor, args.toArray());
    }

    private List<String> normalizeTypes(Collection<String> relationTypes) {
        return relationTypes.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .map(String::toUpperCase)
                .map(value -> value.replace(' ', '_').replace('-', '_'))
                .filter(value -> !value.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }

    private String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(Math.max(1, count), "?"));
    }

    public record RelationEdge(
            Integer animeId,
            Integer relatedAnimeId,
            String relationType,
            Instant updatedAt) {
    }

    public record RelationGraphRebuildStats(
            long edgesBefore,
            int inserted,
            long edgesAfter,
            long animeWithEdges) {
    }

    public record RelatedAnimeRecord(
            Integer relatedAnimeId,
            String relationType,
            String titleRomaji,
            String titleEnglish,
            String titleNative) {
    }
}
