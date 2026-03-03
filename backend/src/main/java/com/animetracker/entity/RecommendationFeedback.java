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
@Table(name = "recommendation_feedback")
public class RecommendationFeedback {

    public static final String SIGNAL_THUMBS_UP = "THUMBS_UP";
    public static final String SIGNAL_THUMBS_DOWN = "THUMBS_DOWN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "anilist_id", nullable = false)
    private Integer anilistId;

    @Column(name = "signal", nullable = false, length = 32)
    private String signal;

    @Column(name = "source_mode", length = 32)
    private String sourceMode;

    @Column(name = "query_hash", length = 64)
    private String queryHash;

    @Column(name = "title")
    private String title;

    @Column(name = "cover_image", length = 500)
    private String coverImage;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public RecommendationFeedback() {
    }

    public RecommendationFeedback(
            User user,
            Integer anilistId,
            String signal,
            String sourceMode,
            String queryHash,
            String title,
            String coverImage) {
        this.user = user;
        this.anilistId = anilistId;
        this.signal = signal;
        this.sourceMode = sourceMode;
        this.queryHash = queryHash;
        this.title = title;
        this.coverImage = coverImage;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public Integer getAnilistId() {
        return anilistId;
    }

    public String getSignal() {
        return signal;
    }

    public void setSignal(String signal) {
        this.signal = signal;
    }

    public String getSourceMode() {
        return sourceMode;
    }

    public void setSourceMode(String sourceMode) {
        this.sourceMode = sourceMode;
    }

    public String getQueryHash() {
        return queryHash;
    }

    public void setQueryHash(String queryHash) {
        this.queryHash = queryHash;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
