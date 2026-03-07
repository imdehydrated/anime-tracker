package com.animetracker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Centralized sizing/tuning knobs for search and recommendation candidate pools.
 * Keeps candidate-limit constants in one place to reduce drift.
 */
@Component
public class RecommendationCandidateTuning {

    @Value("${recommendations.semantic.lexical-candidate-limit:60}")
    private int semanticLexicalCandidateLimit;
    @Value("${recommendations.semantic.vector-candidate-limit:140}")
    private int semanticVectorCandidateLimit;
    @Value("${recommendations.semantic.merged-candidate-limit:140}")
    private int semanticMergedCandidateLimit;
    @Value("${recommendations.semantic.similar-candidate-limit:90}")
    private int semanticSimilarCandidateLimit;

    @Value("${recommendations.search.result-limit:25}")
    private int searchResultLimit;
    @Value("${recommendations.search.local-candidate-limit:80}")
    private int searchLocalCandidateLimit;
    @Value("${recommendations.search.sufficient-results:5}")
    private int searchSufficientResults;
    @Value("${recommendations.search.anilist-page-size:20}")
    private int searchAniListPageSize;
    @Value("${recommendations.search.anilist-max-pages:3}")
    private int searchAniListMaxPages;
    @Value("${recommendations.search.page-max-result-limit:120}")
    private int searchPageMaxResultLimit;

    public int semanticLexicalCandidateLimit() {
        return Math.max(1, semanticLexicalCandidateLimit);
    }

    public int semanticVectorCandidateLimit() {
        return Math.max(1, semanticVectorCandidateLimit);
    }

    public int semanticMergedCandidateLimit() {
        return Math.max(1, semanticMergedCandidateLimit);
    }

    public int semanticSimilarCandidateLimit() {
        return Math.max(1, semanticSimilarCandidateLimit);
    }

    public int searchResultLimit() {
        return Math.max(1, searchResultLimit);
    }

    public int searchLocalCandidateLimit() {
        return Math.max(1, searchLocalCandidateLimit);
    }

    public int searchSufficientResults() {
        return Math.max(1, searchSufficientResults);
    }

    public int searchAniListPageSize() {
        return Math.max(1, searchAniListPageSize);
    }

    public int searchAniListMaxPages() {
        return Math.max(1, searchAniListMaxPages);
    }

    public int searchPageMaxResultLimit() {
        return Math.max(1, searchPageMaxResultLimit);
    }
}
