package com.animetracker.service;

import com.animetracker.dto.AniListResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimeFilterPolicyTest {

    @Test
    void isAdultCandidate_doesNotTreatAsexualTagAsAdult() {
        AniListResponse.AnimeInfo anime = new AniListResponse.AnimeInfo();
        anime.setGenres(List.of("Mystery", "Psychological", "Thriller"));
        anime.setTags(List.of(tag("Asexual", 20), tag("Crime", 90)));
        anime.setDescription("A psychological cat-and-mouse story.");

        assertFalse(AnimeFilterPolicy.isAdultCandidate(anime, 70));
    }

    @Test
    void isAdultCandidate_flagsExplicitAdultTags() {
        AniListResponse.AnimeInfo anime = new AniListResponse.AnimeInfo();
        anime.setGenres(List.of("Drama"));
        anime.setTags(List.of(tag("Sexual Content", 90)));

        assertTrue(AnimeFilterPolicy.isAdultCandidate(anime, 70));
    }

    private AniListResponse.AnimeTag tag(String name, Integer rank) {
        AniListResponse.AnimeTag tag = new AniListResponse.AnimeTag();
        tag.setName(name);
        tag.setRank(rank);
        return tag;
    }
}
