package com.animetracker.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.animetracker.dto.AniListResponse;

import jakarta.annotation.PostConstruct;

/**
 * Infrastructure service for blending recommendation candidates from multiple sources.
 * Phase 1: provides score normalization and deterministic fusion utilities.
 */
@Service
public class FusionScoringService {

    private static final Logger log = LoggerFactory.getLogger(FusionScoringService.class);

    @Value("${recommendations.fusion.semantic-weight:0.6}")
    private double rawSemanticWeight;

    @Value("${recommendations.fusion.cf-weight:0.4}")
    private double rawCfWeight;

    @Value("${recommendations.fusion.diversity-penalty:0.10}")
    private double rawDiversityPenalty;

    @Value("${recommendations.fusion.cf-candidate-multiplier:2}")
    private int cfCandidateMultiplier;

    private double semanticWeight;
    private double cfWeight;
    private double diversityPenalty;

    @PostConstruct
    void init() {
        double semantic = Math.max(0.0, rawSemanticWeight);
        double cf = Math.max(0.0, rawCfWeight);
        double sum = semantic + cf;

        if (sum <= 0.0) {
            semanticWeight = 1.0;
            cfWeight = 0.0;
        } else {
            semanticWeight = semantic / sum;
            cfWeight = cf / sum;
        }

        diversityPenalty = clamp(rawDiversityPenalty, 0.0, 1.0);

        log.info(
                "Fusion config initialized: semanticWeight={}, cfWeight={}, diversityPenalty={}, cfCandidateMultiplier={} (unused until phase 2)",
                semanticWeight, cfWeight, diversityPenalty, cfCandidateMultiplier);
    }

    /**
     * Convert pgvector cosine distance [0, 2] to normalized similarity [0, 1].
     */
    public static double normalizeSemanticDistance(double distance) {
        return clamp(1.0 - (distance / 2.0), 0.0, 1.0);
    }

    /**
     * Convert sidecar rerank score [-1, 1] to normalized score [0, 1].
     */
    public static double normalizeRerankedScore(double sidecarScore) {
        return clamp((sidecarScore + 1.0) / 2.0, 0.0, 1.0);
    }

    /**
     * Convert CF outputs to normalized score [0, 1].
     */
    public static double normalizeCfScore(double predictedScore, double watchConfidence) {
        double clampedPred = clamp(predictedScore, 1.0, 10.0);
        double clampedConf = clamp(watchConfidence, 0.0, 1.0);
        double normalizedPred = (clampedPred - 1.0) / 9.0;
        return clamp(normalizedPred * clampedConf, 0.0, 1.0);
    }

    /**
     * Merge semantic and CF candidates into one fused ranking.
     */
    public List<FusedCandidate> fuseAndRank(List<ScoredCandidate> semanticCandidates, List<ScoredCandidate> cfCandidates) {
        return fuseAndRank(semanticCandidates, cfCandidates, null, null);
    }

    /**
     * Merge semantic and CF candidates into one fused ranking, optionally overriding
     * configured weights for this call.
     */
    public List<FusedCandidate> fuseAndRank(
            List<ScoredCandidate> semanticCandidates,
            List<ScoredCandidate> cfCandidates,
            Double semanticWeightOverride,
            Double cfWeightOverride) {
        if ((semanticCandidates == null || semanticCandidates.isEmpty())
                && (cfCandidates == null || cfCandidates.isEmpty())) {
            return List.of();
        }

        WeightPair weights = resolveWeights(semanticWeightOverride, cfWeightOverride);

        Map<Integer, ScoredCandidate> semanticById = indexById(semanticCandidates);
        Map<Integer, ScoredCandidate> cfById = indexById(cfCandidates);

        Set<Integer> allIds = new LinkedHashSet<>();
        allIds.addAll(semanticById.keySet());
        allIds.addAll(cfById.keySet());

        List<FusedCandidate> fused = new ArrayList<>(allIds.size());
        for (Integer anilistId : allIds) {
            ScoredCandidate sem = semanticById.get(anilistId);
            ScoredCandidate cf = cfById.get(anilistId);

            if (sem != null && cf != null) {
                double score = clamp((weights.semantic() * sem.score()) + (weights.cf() * cf.score()), 0.0, 1.0);
                fused.add(new FusedCandidate(
                        anilistId,
                        sem.animeInfo(),
                        score,
                        unionReasonCodes(sem.reasonCodes(), cf.reasonCodes())));
            } else if (sem != null) {
                fused.add(new FusedCandidate(
                        anilistId,
                        sem.animeInfo(),
                        clamp(sem.score(), 0.0, 1.0),
                        sem.reasonCodes()));
            } else if (cf != null) {
                fused.add(new FusedCandidate(
                        anilistId,
                        cf.animeInfo(),
                        clamp(cf.score(), 0.0, 1.0),
                        cf.reasonCodes()));
            }
        }

        fused.sort((a, b) -> {
            int byScore = Double.compare(b.fusionScore(), a.fusionScore());
            if (byScore != 0) {
                return byScore;
            }
            return Integer.compare(a.anilistId(), b.anilistId());
        });

        return fused;
    }

    private WeightPair resolveWeights(Double semanticOverride, Double cfOverride) {
        if (semanticOverride == null && cfOverride == null) {
            return new WeightPair(semanticWeight, cfWeight);
        }

        double sem = semanticOverride == null ? semanticWeight : Math.max(0.0, semanticOverride);
        double cf = cfOverride == null ? cfWeight : Math.max(0.0, cfOverride);
        double sum = sem + cf;
        if (sum <= 0.0) {
            return new WeightPair(1.0, 0.0);
        }
        return new WeightPair(sem / sum, cf / sum);
    }

    /**
     * Apply a conservative diversity penalty based on genre overlap with already seen genres.
     */
    public List<FusedCandidate> applyDiversityPass(List<FusedCandidate> ranked) {
        if (ranked == null || ranked.isEmpty()) {
            return List.of();
        }

        Set<String> seenGenres = new LinkedHashSet<>();
        List<FusedCandidate> adjusted = new ArrayList<>(ranked.size());

        for (FusedCandidate candidate : ranked) {
            List<String> candidateGenres = candidate.animeInfo() != null ? candidate.animeInfo().getGenres() : null;
            double overlapRatio = jaccard(candidateGenres, seenGenres);
            double adjustedScore = clamp(candidate.fusionScore() - (diversityPenalty * overlapRatio), 0.0, 1.0);

            adjusted.add(new FusedCandidate(
                    candidate.anilistId(),
                    candidate.animeInfo(),
                    adjustedScore,
                    candidate.reasonCodes()));

            if (candidateGenres != null) {
                for (String genre : candidateGenres) {
                    if (genre != null && !genre.isBlank()) {
                        seenGenres.add(normalizeGenre(genre));
                    }
                }
            }
        }

        adjusted.sort((a, b) -> {
            int byScore = Double.compare(b.fusionScore(), a.fusionScore());
            if (byScore != 0) {
                return byScore;
            }
            return Integer.compare(a.anilistId(), b.anilistId());
        });

        return adjusted;
    }

    static double jaccard(List<String> candidateGenres, Set<String> seenGenres) {
        if (candidateGenres == null || candidateGenres.isEmpty() || seenGenres == null || seenGenres.isEmpty()) {
            return 0.0;
        }

        Set<String> candidateSet = new LinkedHashSet<>();
        for (String genre : candidateGenres) {
            if (genre != null && !genre.isBlank()) {
                candidateSet.add(normalizeGenre(genre));
            }
        }
        if (candidateSet.isEmpty()) {
            return 0.0;
        }

        Set<String> union = new LinkedHashSet<>(candidateSet);
        union.addAll(seenGenres);
        if (union.isEmpty()) {
            return 0.0;
        }

        int intersectionCount = 0;
        for (String genre : candidateSet) {
            if (seenGenres.contains(genre)) {
                intersectionCount++;
            }
        }

        return (double) intersectionCount / (double) union.size();
    }

    static double clamp(double value, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return min;
        }
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static String normalizeGenre(String genre) {
        return genre.trim().toLowerCase();
    }

    private Map<Integer, ScoredCandidate> indexById(List<ScoredCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Map.of();
        }

        Map<Integer, ScoredCandidate> byId = new HashMap<>();
        for (ScoredCandidate candidate : candidates) {
            if (candidate != null) {
                byId.put(candidate.anilistId(), candidate);
            }
        }
        return byId;
    }

    private List<String> unionReasonCodes(List<String> first, List<String> second) {
        if ((first == null || first.isEmpty()) && (second == null || second.isEmpty())) {
            return List.of();
        }

        LinkedHashSet<String> union = new LinkedHashSet<>();
        if (first != null) {
            for (String reason : first) {
                if (reason != null && !reason.isBlank()) {
                    union.add(reason);
                }
            }
        }
        if (second != null) {
            for (String reason : second) {
                if (reason != null && !reason.isBlank()) {
                    union.add(reason);
                }
            }
        }
        return List.copyOf(union);
    }

    double getSemanticWeight() {
        return semanticWeight;
    }

    double getCfWeight() {
        return cfWeight;
    }

    double getDiversityPenalty() {
        return diversityPenalty;
    }

    int getCfCandidateMultiplier() {
        return cfCandidateMultiplier;
    }

    public record ScoredCandidate(
            int anilistId,
            AniListResponse.AnimeInfo animeInfo,
            double score,
            List<String> reasonCodes) {
        public ScoredCandidate {
            score = clamp(score, 0.0, 1.0);
            reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        }
    }

    public record FusedCandidate(
            int anilistId,
            AniListResponse.AnimeInfo animeInfo,
            double fusionScore,
            List<String> reasonCodes) {
        public FusedCandidate {
            fusionScore = clamp(fusionScore, 0.0, 1.0);
            reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        }
    }

    private record WeightPair(double semantic, double cf) {
    }
}
