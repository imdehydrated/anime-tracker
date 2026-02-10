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

// Stores anime the user never wants to see in recommendations
@Entity
@Table(name = "recommendation_blacklist")
public class RecommendationBlacklist {

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

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public RecommendationBlacklist() {}

    public RecommendationBlacklist(User user, Integer anilistId, String title, String coverImage) {
        this.user = user;
        this.anilistId = anilistId;
        this.title = title;
        this.coverImage = coverImage;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public Integer getAnilistId() { return anilistId; }
    public String getTitle() { return title; }
    public String getCoverImage() { return coverImage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
