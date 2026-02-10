package com.animetracker.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.animetracker.entity.RecommendationBlacklist;
import com.animetracker.entity.User;

public interface RecommendationBlacklistRepository extends JpaRepository<RecommendationBlacklist, Long> {
    List<RecommendationBlacklist> findByUser(User user);
    boolean existsByUserAndAnilistId(User user, Integer anilistId);
}
