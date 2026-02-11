package com.animetracker.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA entity for the anime_embeddings table.
 * Stores denormalized anime metadata + a 1536-dim OpenAI embedding vector.
 *
 * The embedding column is NOT mapped here because Hibernate doesn't natively
 * understand pgvector's vector type. All vector operations (insert, search)
 * are done via native SQL queries in the repository.
 */
@Entity
@Table(name = "anime_embeddings")
public class AnimeEmbedding {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "anilist_id", nullable = false, unique = true)
	private Integer anilistId;

	@Column(name = "title_romaji", length = 500)
	private String titleRomaji;

	@Column(name = "title_english", length = 500)
	private String titleEnglish;

	@Column(name = "cover_image", length = 500)
	private String coverImage;

	@Column(name = "genres", columnDefinition = "TEXT")
	private String genres;

	@Column(name = "description", columnDefinition = "TEXT")
	private String description;

	@Column(name = "average_score")
	private Integer averageScore;

	@Column(name = "status", length = 30)
	private String status;

	@Column(name = "episodes")
	private Integer episodes;

	@Column(name = "embedding_text", columnDefinition = "TEXT")
	private String embeddingText;

	// embedding vector(1536) is NOT mapped here — handled via native queries
	// Hibernate validate mode only checks mapped fields, so unmapped columns are fine

	@Column(name = "created_at")
	private LocalDateTime createdAt;

	@Column(name = "updated_at")
	private LocalDateTime updatedAt;

	public AnimeEmbedding() {
	}

	// Getters and Setters
	public Long getId() { return id; }
	public void setId(Long id) { this.id = id; }

	public Integer getAnilistId() { return anilistId; }
	public void setAnilistId(Integer anilistId) { this.anilistId = anilistId; }

	public String getTitleRomaji() { return titleRomaji; }
	public void setTitleRomaji(String titleRomaji) { this.titleRomaji = titleRomaji; }

	public String getTitleEnglish() { return titleEnglish; }
	public void setTitleEnglish(String titleEnglish) { this.titleEnglish = titleEnglish; }

	public String getCoverImage() { return coverImage; }
	public void setCoverImage(String coverImage) { this.coverImage = coverImage; }

	public String getGenres() { return genres; }
	public void setGenres(String genres) { this.genres = genres; }

	public String getDescription() { return description; }
	public void setDescription(String description) { this.description = description; }

	public Integer getAverageScore() { return averageScore; }
	public void setAverageScore(Integer averageScore) { this.averageScore = averageScore; }

	public String getStatus() { return status; }
	public void setStatus(String status) { this.status = status; }

	public Integer getEpisodes() { return episodes; }
	public void setEpisodes(Integer episodes) { this.episodes = episodes; }

	public String getEmbeddingText() { return embeddingText; }
	public void setEmbeddingText(String embeddingText) { this.embeddingText = embeddingText; }

	public LocalDateTime getCreatedAt() { return createdAt; }
	public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

	public LocalDateTime getUpdatedAt() { return updatedAt; }
	public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
