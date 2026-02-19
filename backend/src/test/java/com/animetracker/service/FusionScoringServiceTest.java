package com.animetracker.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import com.animetracker.dto.AniListResponse;
import com.animetracker.dto.RecommendationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class FusionScoringServiceTest {

    private static final double EPS = 1e-9;

    @Test
    void normalizeSemanticDistance_zeroDistance_isOne() {
        assertEquals(1.0, FusionScoringService.normalizeSemanticDistance(0.0), EPS);
    }

    @Test
    void normalizeSemanticDistance_midDistance_isHalf() {
        assertEquals(0.5, FusionScoringService.normalizeSemanticDistance(1.0), EPS);
    }

    @Test
    void normalizeSemanticDistance_maxDistance_isZero() {
        assertEquals(0.0, FusionScoringService.normalizeSemanticDistance(2.0), EPS);
    }

    @Test
    void normalizeSemanticDistance_negativeDistance_clampsToOne() {
        assertEquals(1.0, FusionScoringService.normalizeSemanticDistance(-0.2), EPS);
    }

    @Test
    void normalizeSemanticDistance_aboveMaxDistance_clampsToZero() {
        assertEquals(0.0, FusionScoringService.normalizeSemanticDistance(2.4), EPS);
    }

    @Test
    void normalizeRerankedScore_min_isZero() {
        assertEquals(0.0, FusionScoringService.normalizeRerankedScore(-1.0), EPS);
    }

    @Test
    void normalizeRerankedScore_zero_isHalf() {
        assertEquals(0.5, FusionScoringService.normalizeRerankedScore(0.0), EPS);
    }

    @Test
    void normalizeRerankedScore_max_isOne() {
        assertEquals(1.0, FusionScoringService.normalizeRerankedScore(1.0), EPS);
    }

    @Test
    void normalizeRerankedScore_belowMin_clampsToZero() {
        assertEquals(0.0, FusionScoringService.normalizeRerankedScore(-2.0), EPS);
    }

    @Test
    void normalizeRerankedScore_aboveMax_clampsToOne() {
        assertEquals(1.0, FusionScoringService.normalizeRerankedScore(4.0), EPS);
    }

    @Test
    void normalizeCfScore_perfectPrediction_isOne() {
        assertEquals(1.0, FusionScoringService.normalizeCfScore(10.0, 1.0), EPS);
    }

    @Test
    void normalizeCfScore_minPrediction_isZero() {
        assertEquals(0.0, FusionScoringService.normalizeCfScore(1.0, 1.0), EPS);
    }

    @Test
    void normalizeCfScore_midPrediction_isHalf() {
        assertEquals(0.5, FusionScoringService.normalizeCfScore(5.5, 1.0), EPS);
    }

    @Test
    void normalizeCfScore_halfConfidence_halvesScore() {
        assertEquals(0.5, FusionScoringService.normalizeCfScore(10.0, 0.5), EPS);
    }

    @Test
    void normalizeCfScore_predictedScoreAboveRange_clampsToOne() {
        assertEquals(1.0, FusionScoringService.normalizeCfScore(11.5, 1.0), EPS);
    }

    @Test
    void normalizeCfScore_predictedScoreBelowRange_clampsToZero() {
        assertEquals(0.0, FusionScoringService.normalizeCfScore(-5.0, 1.0), EPS);
    }

    @Test
    void normalizeCfScore_confidenceAboveRange_clampsConfidence() {
        assertEquals(1.0, FusionScoringService.normalizeCfScore(10.0, 1.4), EPS);
    }

    @Test
    void normalizeCfScore_confidenceBelowRange_clampsConfidence() {
        assertEquals(0.0, FusionScoringService.normalizeCfScore(10.0, -0.2), EPS);
    }

    @Test
    void scoredCandidate_scoreAboveOne_clamps() {
        FusionScoringService.ScoredCandidate candidate = new FusionScoringService.ScoredCandidate(
                1, animeInfo(1, List.of("Action")), 1.5, List.of("A"));
        assertEquals(1.0, candidate.score(), EPS);
    }

    @Test
    void scoredCandidate_scoreBelowZero_clamps() {
        FusionScoringService.ScoredCandidate candidate = new FusionScoringService.ScoredCandidate(
                1, animeInfo(1, List.of("Action")), -0.3, List.of("A"));
        assertEquals(0.0, candidate.score(), EPS);
    }

    @Test
    void scoredCandidate_nullReasonCodes_becomesEmptyImmutableList() {
        FusionScoringService.ScoredCandidate candidate = new FusionScoringService.ScoredCandidate(
                1, animeInfo(1, List.of("Action")), 0.5, null);
        assertTrue(candidate.reasonCodes().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> candidate.reasonCodes().add("X"));
    }

    @Test
    void fuseAndRank_sameAnimeInBothLists_blendsAndUnionsReasons() throws Exception {
        FusionScoringService service = serviceWithWeights(0.6, 0.4, 0.1, 2);

        List<FusionScoringService.FusedCandidate> fused = service.fuseAndRank(
                List.of(scored(10, 0.8, List.of("MATCHES_QUERY"))),
                List.of(scored(10, 0.2, List.of("CF_SIGNAL"))));

        assertEquals(1, fused.size());
        assertEquals(0.56, fused.get(0).fusionScore(), EPS);
        assertEquals(List.of("MATCHES_QUERY", "CF_SIGNAL"), fused.get(0).reasonCodes());
    }

    @Test
    void fuseAndRank_semanticOnly_passthrough() throws Exception {
        FusionScoringService service = serviceWithWeights(0.6, 0.4, 0.1, 2);
        List<FusionScoringService.FusedCandidate> fused = service.fuseAndRank(
                List.of(scored(11, 0.42, List.of("MATCHES_QUERY"))),
                List.of());
        assertEquals(1, fused.size());
        assertEquals(0.42, fused.get(0).fusionScore(), EPS);
    }

    @Test
    void fuseAndRank_cfOnly_passthrough() throws Exception {
        FusionScoringService service = serviceWithWeights(0.6, 0.4, 0.1, 2);
        List<FusionScoringService.FusedCandidate> fused = service.fuseAndRank(
                List.of(),
                List.of(scored(12, 0.33, List.of("CF_SIGNAL"))));
        assertEquals(1, fused.size());
        assertEquals(0.33, fused.get(0).fusionScore(), EPS);
    }

    @Test
    void fuseAndRank_tieBreakByAnilistIdAscending() throws Exception {
        FusionScoringService service = serviceWithWeights(0.5, 0.5, 0.1, 2);
        List<FusionScoringService.FusedCandidate> fused = service.fuseAndRank(
                List.of(scored(20, 0.4, List.of("A")), scored(10, 0.4, List.of("B"))),
                List.of());
        assertEquals(10, fused.get(0).anilistId());
        assertEquals(20, fused.get(1).anilistId());
    }

    @Test
    void fuseAndRank_bothNullInputs_returnsEmpty() throws Exception {
        FusionScoringService service = serviceWithWeights(0.6, 0.4, 0.1, 2);
        assertTrue(service.fuseAndRank(null, null).isEmpty());
    }

    @Test
    void fuseAndRank_mixedCandidates_sortsByScoreThenId() throws Exception {
        FusionScoringService service = serviceWithWeights(0.75, 0.25, 0.1, 2);
        List<FusionScoringService.FusedCandidate> fused = service.fuseAndRank(
                List.of(
                        scored(1, 0.9, List.of("S")),
                        scored(2, 0.5, List.of("S")),
                        scored(3, 0.2, List.of("S"))),
                List.of(
                        scored(2, 0.9, List.of("C")),
                        scored(4, 0.95, List.of("C"))));

        assertEquals(List.of(1, 2, 4, 3), fused.stream().map(FusionScoringService.FusedCandidate::anilistId).toList());
        assertEquals(0.6, fused.get(1).fusionScore(), EPS);
    }

    @Test
    void fuseAndRank_duplicateReasonCodes_dedupesPreservingOrder() throws Exception {
        FusionScoringService service = serviceWithWeights(0.5, 0.5, 0.1, 2);
        List<FusionScoringService.FusedCandidate> fused = service.fuseAndRank(
                List.of(scored(30, 0.8, List.of("A", "B"))),
                List.of(scored(30, 0.2, List.of("B", "C"))));
        assertEquals(List.of("A", "B", "C"), fused.get(0).reasonCodes());
    }

    @Test
    void fuseAndRank_duplicateIds_lastWinsInSourceMap() throws Exception {
        FusionScoringService service = serviceWithWeights(1.0, 0.0, 0.1, 2);
        List<FusionScoringService.FusedCandidate> fused = service.fuseAndRank(
                List.of(scored(40, 0.1, List.of("A")), scored(40, 0.7, List.of("B"))),
                List.of());
        assertEquals(1, fused.size());
        assertEquals(0.7, fused.get(0).fusionScore(), EPS);
        assertEquals(List.of("B"), fused.get(0).reasonCodes());
    }

    @Test
    void fuseAndRank_nullCandidateEntries_ignored() throws Exception {
        FusionScoringService service = serviceWithWeights(1.0, 0.0, 0.1, 2);
        List<FusionScoringService.FusedCandidate> fused = service.fuseAndRank(
                List.of(null, scored(41, 0.6, List.of("S"))),
                List.of(null));
        assertEquals(1, fused.size());
        assertEquals(41, fused.get(0).anilistId());
    }

    @Test
    void init_zeroWeights_fallsBackToSemanticOnly() throws Exception {
        FusionScoringService service = serviceWithWeights(0.0, 0.0, 0.1, 2);
        assertEquals(1.0, service.getSemanticWeight(), EPS);
        assertEquals(0.0, service.getCfWeight(), EPS);
    }

    @Test
    void init_negativeWeights_clampsAndFallsBack() throws Exception {
        FusionScoringService service = serviceWithWeights(-2.0, -5.0, 0.1, 2);
        assertEquals(1.0, service.getSemanticWeight(), EPS);
        assertEquals(0.0, service.getCfWeight(), EPS);
    }

    @Test
    void init_nonNormalizedWeights_areRenormalized() throws Exception {
        FusionScoringService service = serviceWithWeights(0.3, 0.3, 0.1, 2);
        assertEquals(0.5, service.getSemanticWeight(), EPS);
        assertEquals(0.5, service.getCfWeight(), EPS);
    }

    @Test
    void init_oneNegativeWeight_otherRenormalizesToOne() throws Exception {
        FusionScoringService service = serviceWithWeights(-0.3, 0.7, 0.1, 2);
        assertEquals(0.0, service.getSemanticWeight(), EPS);
        assertEquals(1.0, service.getCfWeight(), EPS);
    }

    @Test
    void init_diversityPenaltyClampedLowerBound() throws Exception {
        FusionScoringService service = serviceWithWeights(0.6, 0.4, -2.0, 2);
        assertEquals(0.0, service.getDiversityPenalty(), EPS);
    }

    @Test
    void init_diversityPenaltyClampedUpperBound() throws Exception {
        FusionScoringService service = serviceWithWeights(0.6, 0.4, 3.0, 2);
        assertEquals(1.0, service.getDiversityPenalty(), EPS);
    }

    @Test
    void init_cfCandidateMultiplier_exposedForPhase2() throws Exception {
        FusionScoringService service = serviceWithWeights(0.6, 0.4, 0.1, 5);
        assertEquals(5, service.getCfCandidateMultiplier());
    }

    @Test
    void applyDiversityPass_nullGenres_noPenalty() throws Exception {
        FusionScoringService service = serviceWithWeights(0.6, 0.4, 0.5, 2);
        AniListResponse.AnimeInfo anime = animeInfo(1, null);
        List<FusionScoringService.FusedCandidate> adjusted = service.applyDiversityPass(
                List.of(new FusionScoringService.FusedCandidate(1, anime, 0.8, List.of("X"))));
        assertEquals(0.8, adjusted.get(0).fusionScore(), EPS);
    }

    @Test
    void applyDiversityPass_emptyGenres_noPenalty() throws Exception {
        FusionScoringService service = serviceWithWeights(0.6, 0.4, 0.5, 2);
        AniListResponse.AnimeInfo anime = animeInfo(1, List.of());
        List<FusionScoringService.FusedCandidate> adjusted = service.applyDiversityPass(
                List.of(new FusionScoringService.FusedCandidate(1, anime, 0.8, List.of("X"))));
        assertEquals(0.8, adjusted.get(0).fusionScore(), EPS);
    }

    @Test
    void applyDiversityPass_identicalGenres_fullPenaltyApplied() throws Exception {
        FusionScoringService service = serviceWithWeights(0.6, 0.4, 1.0, 2);
        List<FusionScoringService.FusedCandidate> input = List.of(
                new FusionScoringService.FusedCandidate(1, animeInfo(1, List.of("Action", "Drama")), 0.9, List.of("A")),
                new FusionScoringService.FusedCandidate(2, animeInfo(2, List.of("Action", "Drama")), 0.8, List.of("B")));

        List<FusionScoringService.FusedCandidate> adjusted = service.applyDiversityPass(input);
        FusionScoringService.FusedCandidate candidate2 = adjusted.stream()
                .filter(c -> c.anilistId() == 2)
                .findFirst()
                .orElseThrow();

        assertEquals(0.0, candidate2.fusionScore(), EPS);
    }

    @Test
    void applyDiversityPass_partialGenreOverlap_appliesProportionalPenalty() throws Exception {
        FusionScoringService service = serviceWithWeights(0.6, 0.4, 0.4, 2);
        List<FusionScoringService.FusedCandidate> input = List.of(
                new FusionScoringService.FusedCandidate(1, animeInfo(1, List.of("Action", "Drama")), 0.9, List.of("A")),
                new FusionScoringService.FusedCandidate(2, animeInfo(2, List.of("Action", "Comedy")), 0.8, List.of("B")));

        List<FusionScoringService.FusedCandidate> adjusted = service.applyDiversityPass(input);
        FusionScoringService.FusedCandidate candidate2 = adjusted.stream()
                .filter(c -> c.anilistId() == 2)
                .findFirst()
                .orElseThrow();

        // Jaccard = 1/3, penalty = 0.4 * 1/3
        assertEquals(0.8 - (0.4 / 3.0), candidate2.fusionScore(), EPS);
    }

    @Test
    void applyDiversityPass_resortsAfterPenalty() throws Exception {
        FusionScoringService service = serviceWithWeights(0.6, 0.4, 1.0, 2);
        List<FusionScoringService.FusedCandidate> input = List.of(
                new FusionScoringService.FusedCandidate(1, animeInfo(1, List.of("Action", "Drama")), 0.8, List.of("A")),
                new FusionScoringService.FusedCandidate(2, animeInfo(2, List.of("Action", "Drama")), 0.79, List.of("B")),
                new FusionScoringService.FusedCandidate(3, animeInfo(3, List.of("Sci-Fi")), 0.6, List.of("C")));

        List<FusionScoringService.FusedCandidate> adjusted = service.applyDiversityPass(input);
        assertEquals(List.of(1, 3, 2), adjusted.stream().map(FusionScoringService.FusedCandidate::anilistId).toList());
    }

    @Test
    void applyDiversityPass_emptyInput_returnsEmpty() throws Exception {
        FusionScoringService service = serviceWithWeights(0.6, 0.4, 0.1, 2);
        assertTrue(service.applyDiversityPass(List.of()).isEmpty());
    }

    @Test
    void applyDiversityPass_nullInput_returnsEmpty() throws Exception {
        FusionScoringService service = serviceWithWeights(0.6, 0.4, 0.1, 2);
        assertTrue(service.applyDiversityPass(null).isEmpty());
    }

    @Test
    void jaccard_nullCandidateGenres_returnsZero() {
        assertEquals(0.0, FusionScoringService.jaccard(null, Set.of("action")), EPS);
    }

    @Test
    void jaccard_emptySeenGenres_returnsZero() {
        assertEquals(0.0, FusionScoringService.jaccard(List.of("Action"), Set.of()), EPS);
    }

    @Test
    void jaccard_identicalSets_returnsOne() {
        Set<String> seen = new LinkedHashSet<>(List.of("action", "drama"));
        assertEquals(1.0, FusionScoringService.jaccard(List.of("Action", "Drama"), seen), EPS);
    }

    @Test
    void jaccard_disjointSets_returnsZero() {
        Set<String> seen = new LinkedHashSet<>(List.of("action", "drama"));
        assertEquals(0.0, FusionScoringService.jaccard(List.of("Comedy"), seen), EPS);
    }

    @Test
    void jaccard_partialOverlap_returnsExpectedFraction() {
        Set<String> seen = new LinkedHashSet<>(List.of("action", "drama"));
        assertEquals(1.0 / 3.0, FusionScoringService.jaccard(List.of("Action", "Comedy"), seen), EPS);
    }

    @Test
    void clamp_nan_returnsMin() {
        assertEquals(0.0, FusionScoringService.clamp(Double.NaN, 0.0, 1.0), EPS);
    }

    @Test
    void clamp_positiveInfinity_returnsMin() {
        assertEquals(0.0, FusionScoringService.clamp(Double.POSITIVE_INFINITY, 0.0, 1.0), EPS);
    }

    @Test
    void clamp_negativeInfinity_returnsMin() {
        assertEquals(0.0, FusionScoringService.clamp(Double.NEGATIVE_INFINITY, 0.0, 1.0), EPS);
    }

    @Test
    void clamp_valueWithinRange_isUnchanged() {
        assertEquals(0.7, FusionScoringService.clamp(0.7, 0.0, 1.0), EPS);
    }

    @Test
    void recommendationResponse_nullOptionalFields_omitsNullsInJson() throws Exception {
        RecommendationResponse response = new RecommendationResponse(animeInfo(1, List.of("Action")), null, null);
        String json = new ObjectMapper().writeValueAsString(response);
        assertTrue(json.contains("\"anime\""));
        assertFalse(json.contains("fusionScore"));
        assertFalse(json.contains("reasonCodes"));
    }

    @Test
    void recommendationResponse_emptyReasonCodes_normalizedToNull() {
        RecommendationResponse response = new RecommendationResponse(animeInfo(1, List.of("Action")), 0.5, List.of());
        assertNotNull(response.getAnime());
        assertEquals(0.5, response.getFusionScore(), EPS);
        assertNull(response.getReasonCodes());
    }

    @Test
    void recommendationResponse_populatedReasonCodes_preservedImmutable() {
        RecommendationResponse response = new RecommendationResponse(
                animeInfo(1, List.of("Action")),
                0.8,
                List.of("MATCHES_QUERY", "CF_SIGNAL"));
        assertEquals(List.of("MATCHES_QUERY", "CF_SIGNAL"), response.getReasonCodes());
        assertThrows(UnsupportedOperationException.class, () -> response.getReasonCodes().add("X"));
    }

    private FusionScoringService serviceWithWeights(double sem, double cf, double diversity, int multiplier) throws Exception {
        FusionScoringService service = new FusionScoringService();
        setField(service, "rawSemanticWeight", sem);
        setField(service, "rawCfWeight", cf);
        setField(service, "rawDiversityPenalty", diversity);
        setField(service, "cfCandidateMultiplier", multiplier);
        service.init();
        return service;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private FusionScoringService.ScoredCandidate scored(int anilistId, double score, List<String> reasons) {
        return new FusionScoringService.ScoredCandidate(anilistId, animeInfo(anilistId, List.of("Action")), score, reasons);
    }

    private AniListResponse.AnimeInfo animeInfo(int anilistId, List<String> genres) {
        AniListResponse.AnimeInfo anime = new AniListResponse.AnimeInfo();
        anime.setId(anilistId);
        anime.setGenres(genres);

        AniListResponse.AnimeTitle title = new AniListResponse.AnimeTitle();
        title.setRomaji("Anime " + anilistId);
        anime.setTitle(title);

        return anime;
    }
}
