package com.animetracker.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.animetracker.entity.AnimeCatalog;

@Repository
public interface AnimeCatalogRepository extends JpaRepository<AnimeCatalog, Long> {

    boolean existsByAnilistId(Integer anilistId);

    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO anime_catalog
                (anilist_id, mal_id, title_romaji, title_english, title_native, cover_image, genres,
                 description, average_score, anilist_popularity, status, episodes,
                 format, season, season_year, is_adult,
                 metadata_json, metadata_refreshed_at, metadata_fingerprint,
                 created_at, updated_at)
            VALUES
                (:anilistId, :malId, :titleRomaji, :titleEnglish, :titleNative, :coverImage, :genres,
                 :description, :averageScore, :anilistPopularity, :status, :episodes,
                 :format, :season, :seasonYear, :isAdult,
                 :metadataJson, NOW(), :metadataFingerprint,
                 NOW(), NOW())
            ON CONFLICT (anilist_id) DO UPDATE SET
                mal_id = COALESCE(EXCLUDED.mal_id, anime_catalog.mal_id),
                title_romaji = COALESCE(EXCLUDED.title_romaji, anime_catalog.title_romaji),
                title_english = COALESCE(EXCLUDED.title_english, anime_catalog.title_english),
                title_native = COALESCE(EXCLUDED.title_native, anime_catalog.title_native),
                cover_image = COALESCE(EXCLUDED.cover_image, anime_catalog.cover_image),
                genres = COALESCE(EXCLUDED.genres, anime_catalog.genres),
                description = COALESCE(EXCLUDED.description, anime_catalog.description),
                average_score = COALESCE(EXCLUDED.average_score, anime_catalog.average_score),
                anilist_popularity = COALESCE(EXCLUDED.anilist_popularity, anime_catalog.anilist_popularity),
                status = COALESCE(EXCLUDED.status, anime_catalog.status),
                episodes = COALESCE(EXCLUDED.episodes, anime_catalog.episodes),
                format = COALESCE(EXCLUDED.format, anime_catalog.format),
                season = COALESCE(EXCLUDED.season, anime_catalog.season),
                season_year = COALESCE(EXCLUDED.season_year, anime_catalog.season_year),
                is_adult = COALESCE(EXCLUDED.is_adult, anime_catalog.is_adult),
                metadata_json = COALESCE(EXCLUDED.metadata_json, anime_catalog.metadata_json),
                metadata_refreshed_at = NOW(),
                metadata_fingerprint = COALESCE(EXCLUDED.metadata_fingerprint, anime_catalog.metadata_fingerprint),
                updated_at = NOW()
            """, nativeQuery = true)
    void upsertCatalogEntry(
            @Param("anilistId") Integer anilistId,
            @Param("malId") Integer malId,
            @Param("titleRomaji") String titleRomaji,
            @Param("titleEnglish") String titleEnglish,
            @Param("titleNative") String titleNative,
            @Param("coverImage") String coverImage,
            @Param("genres") String genres,
            @Param("description") String description,
            @Param("averageScore") Integer averageScore,
            @Param("anilistPopularity") Integer anilistPopularity,
            @Param("status") String status,
            @Param("episodes") Integer episodes,
            @Param("format") String format,
            @Param("season") String season,
            @Param("seasonYear") Integer seasonYear,
            @Param("isAdult") Boolean isAdult,
            @Param("metadataJson") String metadataJson,
            @Param("metadataFingerprint") String metadataFingerprint);

    @Query(value = """
            SELECT anilist_id, title_romaji, title_english, cover_image,
                   genres, description, average_score, status, episodes, anilist_popularity,
                   format, season, season_year, is_adult, metadata_json
            FROM anime_catalog
            WHERE anilist_id IN (:anilistIds)
            """, nativeQuery = true)
    List<Object[]> findMetadataByAnilistIds(@Param("anilistIds") List<Integer> anilistIds);

    @Query(value = """
            SELECT anilist_id, title_romaji, title_english, cover_image,
                   genres, description, average_score, status, episodes, anilist_popularity,
                   format, season, season_year, is_adult, metadata_json
            FROM anime_catalog
            WHERE
                ts_rank_cd(
                  to_tsvector(
                    'simple',
                    LOWER(
                      COALESCE(title_romaji, '') || ' ' ||
                      COALESCE(title_english, '') || ' ' ||
                      COALESCE(genres, '') || ' ' ||
                      COALESCE(description, '')
                    )
                  ),
                  plainto_tsquery('simple', :queryText)
                ) > 0
                OR similarity(LOWER(COALESCE(title_romaji, '')), :queryText) > 0.18
                OR similarity(LOWER(COALESCE(title_english, '')), :queryText) > 0.18
            ORDER BY
                (
                  0.70 * ts_rank_cd(
                    to_tsvector(
                      'simple',
                      LOWER(
                        COALESCE(title_romaji, '') || ' ' ||
                        COALESCE(title_english, '') || ' ' ||
                        COALESCE(genres, '') || ' ' ||
                        COALESCE(description, '')
                      )
                    ),
                    plainto_tsquery('simple', :queryText)
                  )
                  + 0.30 * GREATEST(
                      similarity(LOWER(COALESCE(title_romaji, '')), :queryText),
                      similarity(LOWER(COALESCE(title_english, '')), :queryText)
                    )
                ) DESC,
                anilist_id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> searchLocalCatalogMetadata(
            @Param("queryText") String queryText,
            @Param("limit") int limit);

    @Query(value = """
            SELECT COUNT(*)
            FROM anime_catalog
            """, nativeQuery = true)
    long countCatalogRows();

    @Query(value = """
            SELECT anilist_id
            FROM anime_catalog
            WHERE mal_id = :malId
            LIMIT 1
            """, nativeQuery = true)
    Optional<Integer> findAnilistIdByMalId(@Param("malId") Integer malId);
}
