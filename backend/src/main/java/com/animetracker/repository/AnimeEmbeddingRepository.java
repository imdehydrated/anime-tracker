package com.animetracker.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.animetracker.entity.AnimeEmbedding;

/**
 * Repository for anime_embeddings table.
 * Uses native SQL queries for anything involving the vector column,
 * since Hibernate can't map pgvector's vector type directly.
 */
@Repository
public interface AnimeEmbeddingRepository extends JpaRepository<AnimeEmbedding, Long> {

	// Standard JPA query - find by AniList ID (no vector involved)
	Optional<AnimeEmbedding> findByAnilistId(Integer anilistId);

	// Check if an anime is already embedded
	boolean existsByAnilistId(Integer anilistId);

	/**
	 * Cosine similarity search using pgvector's <=> operator.
	 * Lower distance = more similar (0 = identical, 2 = opposite).
	 * We cast the input string to vector type and exclude any IDs the caller specifies.
	 *
	 * Returns metadata columns (not the embedding itself - that would be wasteful).
	 * Results are ordered by similarity (closest first).
	 */
	@Query(value = """
			SELECT id, anilist_id, title_romaji, title_english, cover_image,
			       genres, description, average_score, status, episodes, anilist_popularity,
			       embedding_text, created_at, updated_at,
			       (embedding <=> CAST(:vector AS vector)) AS distance
			FROM anime_embeddings
			WHERE embedding IS NOT NULL
			  AND anilist_id NOT IN (:excludeIds)
			ORDER BY embedding <=> CAST(:vector AS vector)
			LIMIT :limit
			""", nativeQuery = true)
	List<Object[]> findSimilar(
			@Param("vector") String vector,
			@Param("excludeIds") List<Integer> excludeIds,
			@Param("limit") int limit);

	/**
	 * Indexed lexical retrieval for semantic mode.
	 * Combines full-text ranking (title/genres/description) and trigram title
	 * similarity into one lexical score, then emits a synthetic distance for
	 * downstream compatibility with vector result mapping.
	 */
	@Query(value = """
			WITH ranked AS (
				SELECT id, anilist_id, title_romaji, title_english, cover_image,
				       genres, description, average_score, status, episodes, anilist_popularity,
				       embedding_text, created_at, updated_at,
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
				       ) AS ts_rank,
				       GREATEST(
				         similarity(LOWER(COALESCE(title_romaji, '')), :queryText),
				         similarity(LOWER(COALESCE(title_english, '')), :queryText)
				       ) AS trigram_score
				FROM anime_embeddings
				WHERE anilist_id NOT IN (:excludeIds)
			)
			SELECT id, anilist_id, title_romaji, title_english, cover_image,
			       genres, description, average_score, status, episodes, anilist_popularity,
			       embedding_text, created_at, updated_at,
			       (
			         1.0 - LEAST(
			           1.0,
			           (0.70 * ts_rank) + (0.30 * trigram_score)
			         )
			       ) AS distance
			FROM ranked
			WHERE ts_rank > 0
			   OR trigram_score > 0.10
			ORDER BY ((0.70 * ts_rank) + (0.30 * trigram_score)) DESC, anilist_id ASC
			LIMIT :limit
			""", nativeQuery = true)
	List<Object[]> findLexicalMatches(
			@Param("queryText") String queryText,
			@Param("excludeIds") List<Integer> excludeIds,
			@Param("limit") int limit);

	/**
	 * Upsert: insert a new anime embedding, or update if anilist_id already exists.
	 * Uses PostgreSQL's ON CONFLICT ... DO UPDATE for atomic upsert.
	 * The embedding is passed as a string like "[0.1,0.2,...]" and cast to vector.
	 */
	@Transactional
	@Modifying
	@Query(value = """
			INSERT INTO anime_embeddings
			    (anilist_id, title_romaji, title_english, cover_image, genres,
			     description, average_score, status, episodes, anilist_popularity,
			     format, season, season_year, is_adult, metadata_json, embedding_text,
			     embedding, metadata_refreshed_at, metadata_fingerprint, created_at, updated_at)
			VALUES
			    (:anilistId, :titleRomaji, :titleEnglish, :coverImage, :genres,
			     :description, :averageScore, :status, :episodes, :anilistPopularity,
			     :format, :season, :seasonYear, :isAdult, :metadataJson, :embeddingText,
			     CAST(:embedding AS vector), NOW(), :metadataFingerprint, NOW(), NOW())
			ON CONFLICT (anilist_id) DO UPDATE SET
			    title_romaji = EXCLUDED.title_romaji,
			    title_english = EXCLUDED.title_english,
			    cover_image = EXCLUDED.cover_image,
			    genres = EXCLUDED.genres,
			    description = EXCLUDED.description,
			    average_score = EXCLUDED.average_score,
			    status = EXCLUDED.status,
			    episodes = EXCLUDED.episodes,
			    anilist_popularity = EXCLUDED.anilist_popularity,
			    format = COALESCE(EXCLUDED.format, anime_embeddings.format),
			    season = COALESCE(EXCLUDED.season, anime_embeddings.season),
			    season_year = COALESCE(EXCLUDED.season_year, anime_embeddings.season_year),
			    is_adult = COALESCE(EXCLUDED.is_adult, anime_embeddings.is_adult),
			    metadata_json = COALESCE(EXCLUDED.metadata_json, anime_embeddings.metadata_json),
			    embedding_text = EXCLUDED.embedding_text,
			    embedding = EXCLUDED.embedding,
			    metadata_refreshed_at = NOW(),
			    metadata_fingerprint = COALESCE(EXCLUDED.metadata_fingerprint, anime_embeddings.metadata_fingerprint),
			    updated_at = NOW()
			""", nativeQuery = true)
	void upsertWithEmbedding(
			@Param("anilistId") Integer anilistId,
			@Param("titleRomaji") String titleRomaji,
			@Param("titleEnglish") String titleEnglish,
			@Param("coverImage") String coverImage,
			@Param("genres") String genres,
			@Param("description") String description,
			@Param("averageScore") Integer averageScore,
			@Param("status") String status,
			@Param("episodes") Integer episodes,
			@Param("anilistPopularity") Integer anilistPopularity,
			@Param("format") String format,
			@Param("season") String season,
			@Param("seasonYear") Integer seasonYear,
			@Param("isAdult") Boolean isAdult,
			@Param("metadataJson") String metadataJson,
			@Param("embeddingText") String embeddingText,
			@Param("metadataFingerprint") String metadataFingerprint,
			@Param("embedding") String embedding);

	/**
	 * Cosine similarity search against custom 384-dim embeddings.
	 */
	@Query(value = """
			SELECT id, anilist_id, title_romaji, title_english, cover_image,
			       genres, description, average_score, status, episodes, anilist_popularity,
			       embedding_text, created_at, updated_at,
			       (embedding_custom <=> CAST(:vector AS vector)) AS distance
			FROM anime_embeddings
			WHERE embedding_custom IS NOT NULL
			  AND anilist_id NOT IN (:excludeIds)
			ORDER BY embedding_custom <=> CAST(:vector AS vector)
			LIMIT :limit
			""", nativeQuery = true)
	List<Object[]> findSimilarCustom(
			@Param("vector") String vector,
			@Param("excludeIds") List<Integer> excludeIds,
			@Param("limit") int limit);

	/**
	 * Upsert only the custom 384-dim embedding.
	 * Supports rows that don't have legacy 1536-dim embeddings.
	 */
	@Transactional
	@Modifying
	@Query(value = """
			INSERT INTO anime_embeddings
			    (anilist_id, title_romaji, title_english, cover_image, genres,
			     description, average_score, status, episodes, anilist_popularity,
			     format, season, season_year, is_adult, metadata_json,
			     embedding_text, embedding_custom,
			     metadata_refreshed_at, metadata_fingerprint,
			     created_at, updated_at)
			VALUES
			    (:anilistId, :titleRomaji, :titleEnglish, :coverImage, :genres,
			     :description, :averageScore, :status, :episodes, :anilistPopularity,
			     :format, :season, :seasonYear, :isAdult, :metadataJson, :embeddingText,
			     CAST(:embeddingCustom AS vector), NOW(), :metadataFingerprint, NOW(), NOW())
			ON CONFLICT (anilist_id) DO UPDATE SET
			    title_romaji = COALESCE(EXCLUDED.title_romaji, anime_embeddings.title_romaji),
			    title_english = COALESCE(EXCLUDED.title_english, anime_embeddings.title_english),
			    cover_image = COALESCE(EXCLUDED.cover_image, anime_embeddings.cover_image),
			    genres = COALESCE(EXCLUDED.genres, anime_embeddings.genres),
			    description = COALESCE(EXCLUDED.description, anime_embeddings.description),
			    average_score = COALESCE(EXCLUDED.average_score, anime_embeddings.average_score),
			    status = COALESCE(EXCLUDED.status, anime_embeddings.status),
			    episodes = COALESCE(EXCLUDED.episodes, anime_embeddings.episodes),
			    anilist_popularity = COALESCE(EXCLUDED.anilist_popularity, anime_embeddings.anilist_popularity),
			    format = COALESCE(EXCLUDED.format, anime_embeddings.format),
			    season = COALESCE(EXCLUDED.season, anime_embeddings.season),
			    season_year = COALESCE(EXCLUDED.season_year, anime_embeddings.season_year),
			    is_adult = COALESCE(EXCLUDED.is_adult, anime_embeddings.is_adult),
			    metadata_json = COALESCE(EXCLUDED.metadata_json, anime_embeddings.metadata_json),
			    embedding_text = COALESCE(EXCLUDED.embedding_text, anime_embeddings.embedding_text),
			    embedding_custom = EXCLUDED.embedding_custom,
			    metadata_refreshed_at = NOW(),
			    metadata_fingerprint = COALESCE(EXCLUDED.metadata_fingerprint, anime_embeddings.metadata_fingerprint),
			    updated_at = NOW()
			""", nativeQuery = true)
	void upsertCustomEmbedding(
			@Param("anilistId") Integer anilistId,
			@Param("titleRomaji") String titleRomaji,
			@Param("titleEnglish") String titleEnglish,
			@Param("coverImage") String coverImage,
			@Param("genres") String genres,
			@Param("description") String description,
			@Param("averageScore") Integer averageScore,
			@Param("status") String status,
			@Param("episodes") Integer episodes,
			@Param("anilistPopularity") Integer anilistPopularity,
			@Param("format") String format,
			@Param("season") String season,
			@Param("seasonYear") Integer seasonYear,
			@Param("isAdult") Boolean isAdult,
			@Param("metadataJson") String metadataJson,
			@Param("embeddingText") String embeddingText,
			@Param("metadataFingerprint") String metadataFingerprint,
			@Param("embeddingCustom") String embeddingCustom);

	@Transactional
	@Modifying
	@Query(value = """
			UPDATE anime_embeddings
			SET title_romaji = COALESCE(:titleRomaji, title_romaji),
			    title_english = COALESCE(:titleEnglish, title_english),
			    cover_image = COALESCE(:coverImage, cover_image),
			    genres = COALESCE(:genres, genres),
			    description = COALESCE(:description, description),
			    average_score = COALESCE(:averageScore, average_score),
			    anilist_popularity = COALESCE(:anilistPopularity, anilist_popularity),
			    status = COALESCE(:status, status),
			    episodes = COALESCE(:episodes, episodes),
			    format = COALESCE(:format, format),
			    season = COALESCE(:season, season),
			    season_year = COALESCE(:seasonYear, season_year),
			    is_adult = COALESCE(:isAdult, is_adult),
			    metadata_json = COALESCE(:metadataJson, metadata_json),
			    metadata_refreshed_at = NOW(),
			    metadata_fingerprint = COALESCE(:metadataFingerprint, metadata_fingerprint),
			    updated_at = NOW()
			WHERE anilist_id = :anilistId
			""", nativeQuery = true)
	int updateMetadataByAnilistId(
			@Param("anilistId") Integer anilistId,
			@Param("titleRomaji") String titleRomaji,
			@Param("titleEnglish") String titleEnglish,
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
			SELECT COUNT(*)
			FROM anime_embeddings
			WHERE embedding_custom IS NOT NULL
			""", nativeQuery = true)
	long countCustomEmbeddings();

	/**
	 * Find multiple anime embeddings by their AniList IDs.
	 * Returns the embedding as a string so we can parse it back to float[].
	 */
	@Query(value = """
			SELECT anilist_id, CAST(embedding AS TEXT) AS embedding_str
			FROM anime_embeddings
			WHERE anilist_id IN (:anilistIds)
			  AND embedding IS NOT NULL
			""", nativeQuery = true)
	List<Object[]> findEmbeddingsByAnilistIds(@Param("anilistIds") List<Integer> anilistIds);

	@Query(value = """
			SELECT anilist_id, CAST(embedding_custom AS TEXT) AS embedding_str
			FROM anime_embeddings
			WHERE anilist_id IN (:anilistIds)
			  AND embedding_custom IS NOT NULL
			""", nativeQuery = true)
	List<Object[]> findCustomEmbeddingsByAnilistIds(@Param("anilistIds") List<Integer> anilistIds);

	/**
	 * Fetch metadata fields used by recommendation cards for a batch of AniList IDs.
	 */
	@Query(value = """
			SELECT anilist_id, title_romaji, title_english, cover_image,
			       genres, description, average_score, status, episodes, anilist_popularity,
			       format, season, season_year, is_adult, metadata_json
			FROM anime_embeddings
			WHERE anilist_id IN (:anilistIds)
			""", nativeQuery = true)
	List<Object[]> findMetadataByAnilistIds(@Param("anilistIds") List<Integer> anilistIds);

	/**
	 * Popularity-ordered local metadata candidates for CF cold-start fallback.
	 */
	@Query(value = """
			SELECT anilist_id, title_romaji, title_english, cover_image,
			       genres, description, average_score, status, episodes, anilist_popularity,
			       format, season, season_year, is_adult, metadata_json
			FROM anime_embeddings
			WHERE anilist_id NOT IN (:excludeIds)
			  AND COALESCE(status, '') <> 'CANCELLED'
			ORDER BY COALESCE(anilist_popularity, 0) DESC,
			         COALESCE(average_score, 0) DESC,
			         anilist_id ASC
			LIMIT :limit
			""", nativeQuery = true)
	List<Object[]> findTopPopularMetadataExcluding(
			@Param("excludeIds") List<Integer> excludeIds,
			@Param("limit") int limit);

	/**
	 * Local text search over embedded catalog metadata.
	 * Used as a rate-limit-safe first pass before AniList API search.
	 */
	@Query(value = """
			SELECT anilist_id, title_romaji, title_english, cover_image,
			       genres, description, average_score, status, episodes, anilist_popularity,
			       format, season, season_year, is_adult, metadata_json
			FROM anime_embeddings
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
	List<Object[]> searchLocalMetadata(
			@Param("queryText") String queryText,
			@Param("limit") int limit);

	@Query(value = """
			SELECT metadata_json
			FROM anime_embeddings
			WHERE anilist_id = :anilistId
			""", nativeQuery = true)
	String findMetadataJsonByAnilistId(@Param("anilistId") Integer anilistId);

	@Query(value = """
			SELECT CASE WHEN embedding_custom IS NULL THEN FALSE ELSE TRUE END
			FROM anime_embeddings
			WHERE anilist_id = :anilistId
			""", nativeQuery = true)
	Boolean hasCustomEmbedding(@Param("anilistId") Integer anilistId);

	@Query(value = """
			SELECT metadata_fingerprint
			FROM anime_embeddings
			WHERE anilist_id = :anilistId
			""", nativeQuery = true)
	String findMetadataFingerprintByAnilistId(@Param("anilistId") Integer anilistId);
}
