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
			       genres, description, average_score, status, episodes,
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
	 * Lightweight lexical fallback/boost retrieval used by semantic mode.
	 * Matches title/genres/description with LIKE pattern and returns a synthetic
	 * distance so rows can be merged with vector candidates.
	 */
	@Query(value = """
			SELECT id, anilist_id, title_romaji, title_english, cover_image,
			       genres, description, average_score, status, episodes,
			       embedding_text, created_at, updated_at,
			       CASE
			         WHEN LOWER(COALESCE(title_romaji, '')) LIKE :pattern
			           OR LOWER(COALESCE(title_english, '')) LIKE :pattern THEN 0.15
			         WHEN LOWER(COALESCE(genres, '')) LIKE :pattern THEN 0.30
			         ELSE 0.40
			       END AS distance
			FROM anime_embeddings
			WHERE anilist_id NOT IN (:excludeIds)
			  AND (
			       LOWER(COALESCE(title_romaji, '')) LIKE :pattern
			    OR LOWER(COALESCE(title_english, '')) LIKE :pattern
			    OR LOWER(COALESCE(genres, '')) LIKE :pattern
			    OR LOWER(COALESCE(description, '')) LIKE :pattern
			  )
			ORDER BY distance ASC, anilist_id ASC
			LIMIT :limit
			""", nativeQuery = true)
	List<Object[]> findLexicalMatches(
			@Param("pattern") String pattern,
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
			     description, average_score, status, episodes, embedding_text,
			     embedding, created_at, updated_at)
			VALUES
			    (:anilistId, :titleRomaji, :titleEnglish, :coverImage, :genres,
			     :description, :averageScore, :status, :episodes, :embeddingText,
			     CAST(:embedding AS vector), NOW(), NOW())
			ON CONFLICT (anilist_id) DO UPDATE SET
			    title_romaji = EXCLUDED.title_romaji,
			    title_english = EXCLUDED.title_english,
			    cover_image = EXCLUDED.cover_image,
			    genres = EXCLUDED.genres,
			    description = EXCLUDED.description,
			    average_score = EXCLUDED.average_score,
			    status = EXCLUDED.status,
			    episodes = EXCLUDED.episodes,
			    embedding_text = EXCLUDED.embedding_text,
			    embedding = EXCLUDED.embedding,
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
			@Param("embeddingText") String embeddingText,
			@Param("embedding") String embedding);

	/**
	 * Cosine similarity search against custom 384-dim embeddings.
	 */
	@Query(value = """
			SELECT id, anilist_id, title_romaji, title_english, cover_image,
			       genres, description, average_score, status, episodes,
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
	 * Supports rows that don't have OpenAI embeddings yet.
	 */
	@Transactional
	@Modifying
	@Query(value = """
			INSERT INTO anime_embeddings
			    (anilist_id, title_romaji, title_english, cover_image, genres,
			     description, average_score, status, episodes, embedding_custom,
			     created_at, updated_at)
			VALUES
			    (:anilistId, :titleRomaji, :titleEnglish, :coverImage, :genres,
			     :description, :averageScore, :status, :episodes,
			     CAST(:embeddingCustom AS vector), NOW(), NOW())
			ON CONFLICT (anilist_id) DO UPDATE SET
			    title_romaji = COALESCE(EXCLUDED.title_romaji, anime_embeddings.title_romaji),
			    title_english = COALESCE(EXCLUDED.title_english, anime_embeddings.title_english),
			    cover_image = COALESCE(EXCLUDED.cover_image, anime_embeddings.cover_image),
			    genres = COALESCE(EXCLUDED.genres, anime_embeddings.genres),
			    description = COALESCE(EXCLUDED.description, anime_embeddings.description),
			    average_score = COALESCE(EXCLUDED.average_score, anime_embeddings.average_score),
			    status = COALESCE(EXCLUDED.status, anime_embeddings.status),
			    episodes = COALESCE(EXCLUDED.episodes, anime_embeddings.episodes),
			    embedding_custom = EXCLUDED.embedding_custom,
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
			    status = COALESCE(:status, status),
			    episodes = COALESCE(:episodes, episodes),
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
			@Param("status") String status,
			@Param("episodes") Integer episodes);

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
			       genres, description, average_score, status, episodes
			FROM anime_embeddings
			WHERE anilist_id IN (:anilistIds)
			""", nativeQuery = true)
	List<Object[]> findMetadataByAnilistIds(@Param("anilistIds") List<Integer> anilistIds);
}
