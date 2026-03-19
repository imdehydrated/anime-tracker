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
        anime.setIsAdult(false);

        assertFalse(AnimeFilterPolicy.isAdultCandidate(anime, 70));
    }

    @Test
    void isAdultCandidate_onlyUsesAniListAdultFlag() {
        AniListResponse.AnimeInfo anime = new AniListResponse.AnimeInfo();
        anime.setGenres(List.of("Ecchi", "Drama"));
        anime.setTags(List.of(tag("Sexual Content", 90)));
        anime.setDescription("Contains explicit erotic scenes.");
        anime.setIsAdult(false);

        assertFalse(AnimeFilterPolicy.isAdultCandidate(anime, 70));
    }

    @Test
    void isAdultCandidate_flagsAniListAdultTitles() {
        AniListResponse.AnimeInfo anime = new AniListResponse.AnimeInfo();
        anime.setIsAdult(true);

        assertTrue(AnimeFilterPolicy.isAdultCandidate(anime, 70));
    }

    @Test
    void isMusicCandidate_doesNotTreatMusicGenreTvSeriesAsMusicEntry() {
        AniListResponse.AnimeInfo anime = new AniListResponse.AnimeInfo();
        anime.setFormat("TV");
        anime.setGenres(List.of("Comedy", "Music", "Slice of Life"));
        anime.setDescription("A coming-of-age band anime.");

        assertFalse(AnimeFilterPolicy.isMusicCandidate(anime));
    }

    @Test
    void isMusicCandidate_flagsMusicFormatEntries() {
        AniListResponse.AnimeInfo anime = new AniListResponse.AnimeInfo();
        anime.setFormat("MUSIC");
        anime.setGenres(List.of("Drama"));

        assertTrue(AnimeFilterPolicy.isMusicCandidate(anime));
    }

    private AniListResponse.AnimeTag tag(String name, Integer rank) {
        AniListResponse.AnimeTag tag = new AniListResponse.AnimeTag();
        tag.setName(name);
        tag.setRank(rank);
        return tag;
    }
}
