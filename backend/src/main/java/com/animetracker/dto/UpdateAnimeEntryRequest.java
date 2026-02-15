package com.animetracker.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;

/**
 * Partial update payload for anime list entries.
 * Tracks field presence so we can distinguish:
 * - omitted field (leave unchanged)
 * - explicit null (clear/reset value)
 */
public class UpdateAnimeEntryRequest {

    private String status;
    private Integer score;
    private Integer episodesWatched;

    @JsonIgnore
    private boolean statusProvided;
    @JsonIgnore
    private boolean scoreProvided;
    @JsonIgnore
    private boolean episodesWatchedProvided;

    @JsonSetter("status")
    public void setStatus(String status) {
        this.status = status;
        this.statusProvided = true;
    }

    @JsonSetter("score")
    public void setScore(Integer score) {
        this.score = score;
        this.scoreProvided = true;
    }

    @JsonSetter("episodesWatched")
    public void setEpisodesWatched(Integer episodesWatched) {
        this.episodesWatched = episodesWatched;
        this.episodesWatchedProvided = true;
    }

    public String getStatus() {
        return status;
    }

    public Integer getScore() {
        return score;
    }

    public Integer getEpisodesWatched() {
        return episodesWatched;
    }

    public boolean isStatusProvided() {
        return statusProvided;
    }

    public boolean isScoreProvided() {
        return scoreProvided;
    }

    public boolean isEpisodesWatchedProvided() {
        return episodesWatchedProvided;
    }

    @JsonIgnore
    public boolean hasAnyField() {
        return statusProvided || scoreProvided || episodesWatchedProvided;
    }
}
