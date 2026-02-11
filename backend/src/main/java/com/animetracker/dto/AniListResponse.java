package com.animetracker.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

// Top-level wrapper: matches { "data": { "Page": { ... } } }
public class AniListResponse {

    private AniListData data;

    public AniListData getData() { return data; }
    public void setData(AniListData data) { this.data = data; }

    // Matches { "Page": { "media": [...] } }
    public static class AniListData {

        @JsonProperty("Page")  // AniList uses capital "P" — Jackson needs this hint
        private AniListPage page;

        public AniListPage getPage() { return page; }
        public void setPage(AniListPage page) { this.page = page; }
    }

    // Matches { "media": [ { ... }, { ... } ] }
    public static class AniListPage {

        private List<AnimeInfo> media;

        public List<AnimeInfo> getMedia() { return media; }
        public void setMedia(List<AnimeInfo> media) { this.media = media; }
    }

    // Each anime result — the fields we asked for in our GraphQL query
    public static class AnimeInfo {

        private Integer id;
        private AnimeTitle title;
        private Integer episodes;
        private Integer averageScore;
        private AnimeCoverImage coverImage;
        private List<String> genres;
        private List<AnimeTag> tags;  // Only populated by POPULATE_QUERY (includes tag name + rank)
        private String description;
        private String status;

        // Getters and setters
        public Integer getId() { return id; }
        public void setId(Integer id) { this.id = id; }

        public AnimeTitle getTitle() { return title; }
        public void setTitle(AnimeTitle title) { this.title = title; }

        public Integer getEpisodes() { return episodes; }
        public void setEpisodes(Integer episodes) { this.episodes = episodes; }

        public Integer getAverageScore() { return averageScore; }
        public void setAverageScore(Integer averageScore) { this.averageScore = averageScore; }

        public AnimeCoverImage getCoverImage() { return coverImage; }
        public void setCoverImage(AnimeCoverImage coverImage) { this.coverImage = coverImage; }

        public List<String> getGenres() { return genres; }
        public void setGenres(List<String> genres) { this.genres = genres; }

        public List<AnimeTag> getTags() { return tags; }
        public void setTags(List<AnimeTag> tags) { this.tags = tags; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    // Matches { "romaji": "...", "english": "..." }
    public static class AnimeTitle {

        private String romaji;
        private String english;

        public String getRomaji() { return romaji; }
        public void setRomaji(String romaji) { this.romaji = romaji; }

        public String getEnglish() { return english; }
        public void setEnglish(String english) { this.english = english; }
    }

    // Matches { "large": "https://..." }
    public static class AnimeCoverImage {

        private String large;

        public String getLarge() { return large; }
        public void setLarge(String large) { this.large = large; }
    }

    // Matches { "name": "Time Travel", "rank": 95 }
    // Tags are user-voted descriptors with a rank (0-100) indicating relevance.
    // Only returned by the POPULATE_QUERY — existing queries don't request tags.
    public static class AnimeTag {

        private String name;
        private Integer rank;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public Integer getRank() { return rank; }
        public void setRank(Integer rank) { this.rank = rank; }
    }
}
