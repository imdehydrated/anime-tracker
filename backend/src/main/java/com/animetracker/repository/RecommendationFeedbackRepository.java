package com.animetracker.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.animetracker.entity.RecommendationFeedback;
import com.animetracker.entity.User;

public interface RecommendationFeedbackRepository extends JpaRepository<RecommendationFeedback, Long> {
    Optional<RecommendationFeedback> findByUserAndAnilistId(User user, Integer anilistId);

    List<RecommendationFeedback> findByUserOrderByUpdatedAtDesc(User user);

    List<RecommendationFeedback> findByUserAndSignal(User user, String signal);
}
