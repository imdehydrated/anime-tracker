package com.animetracker.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Canonical AniList catalog row.
 * This table is source-of-truth metadata and intentionally separate from vector artifacts.
 */
@Entity
@Table(name = "anime_catalog")
public class AnimeCatalog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "anilist_id", nullable = false, unique = true)
    private Integer anilistId;

    @Column(name = "mal_id")
    private Integer malId;

    @Column(name = "title_romaji", length = 500)
    private String titleRomaji;

    @Column(name = "title_english", length = 500)
    private String titleEnglish;

    @Column(name = "title_native", length = 500)
    private String titleNative;

    @Column(name = "cover_image", length = 500)
    private String coverImage;

    @Column(name = "genres", columnDefinition = "TEXT")
    private String genres;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "average_score")
    private Integer averageScore;

    @Column(name = "anilist_popularity")
    private Integer anilistPopularity;

    @Column(name = "status", length = 30)
    private String status;

    @Column(name = "episodes")
    private Integer episodes;

    @Column(name = "format", length = 32)
    private String format;

    @Column(name = "season", length = 16)
    private String season;

    @Column(name = "season_year")
    private Integer seasonYear;

    @Column(name = "is_adult")
    private Boolean isAdult;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @Column(name = "metadata_refreshed_at")
    private LocalDateTime metadataRefreshedAt;

    @Column(name = "metadata_fingerprint", length = 64)
    private String metadataFingerprint;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
