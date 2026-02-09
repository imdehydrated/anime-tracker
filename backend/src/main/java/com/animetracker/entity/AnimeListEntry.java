package com.animetracker.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "anime_list_entries")
public class AnimeListEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "anilist_id", nullable = false)
    private Integer anilistId;

    @Column(name = "title")
    private String title;

    @Column(name = "cover_image", length = 500)
    private String coverImage;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "plan_to_watch";

    @Column(name = "score")
    private Integer score;

    @Column(name = "episodes_watched", nullable = false)
    private Integer episodesWatched = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Default constructor (required by JPA)
    public AnimeListEntry() {
    }

    // Constructor for creating new entries
    public AnimeListEntry(User user, Integer anilistId) {
        this.user = user;
        this.anilistId = anilistId;
        this.status = "plan_to_watch";
        this.episodesWatched = 0;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCoverImage() {
        return coverImage;
    }

    public void setCoverImage(String coverImage) {
        this.coverImage = coverImage;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Integer getAnilistId() {
        return anilistId;
    }

    public void setAnilistId(Integer anilistId) {
        this.anilistId = anilistId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getScore() {
        return score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }

    public Integer getEpisodesWatched() {
        return episodesWatched;
    }

    public void setEpisodesWatched(Integer episodesWatched) {
        this.episodesWatched = episodesWatched;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
