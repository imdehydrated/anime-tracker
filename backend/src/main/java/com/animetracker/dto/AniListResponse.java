package com.animetracker.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

// Top-level wrapper: matches { "data": { "Page": { ... } } }
public class AniListResponse {

    private AniListData data;

    public AniListData getData() {
        return data;
    }

    public void setData(AniListData data) {
        this.data = data;
    }

    // Matches { "Page": { "media": [...] } }
    public static class AniListData {

        @JsonProperty("Page")
        private AniListPage page;

        public AniListPage getPage() {
            return page;
        }

        public void setPage(AniListPage page) {
            this.page = page;
        }
    }

    public static class AniListPage {

        private List<AnimeInfo> media;
        private PageInfo pageInfo;

        public List<AnimeInfo> getMedia() {
            return media;
        }

        public void setMedia(List<AnimeInfo> media) {
            this.media = media;
        }

        public PageInfo getPageInfo() {
            return pageInfo;
        }

        public void setPageInfo(PageInfo pageInfo) {
            this.pageInfo = pageInfo;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AnimeInfo {

        private Integer id;
        private Integer idMal;
        private AnimeTitle title;
        private Integer episodes;
        private Integer averageScore;
        private Integer popularity;
        private AnimeCoverImage coverImage;
        private List<String> genres;
        private List<String> synonyms;
        private List<AnimeTag> tags;
        private String description;
        private String status;
        private Boolean isAdult;
        private String format;
        private String season;
        private Integer seasonYear;
        private List<AnimeStudio> studios;
        private List<AnimeRelation> relations;
        private String recommendationReason;
        private List<String> reasonCodes;
        private Double fusionScore;
        private Double queryAdherenceScore;
        private Double queryRelevanceScore;
        private Double userTasteScore;
        private Double popularityPriorScore;
        private Boolean guardrailApplied;
        private Map<String, Object> extraFields;

        public Integer getId() {
            return id;
        }

        public void setId(Integer id) {
            this.id = id;
        }

        @JsonProperty("idMal")
        public Integer getIdMal() {
            return idMal;
        }

        @JsonProperty("idMal")
        public void setIdMal(Integer idMal) {
            this.idMal = idMal;
        }

        public AnimeTitle getTitle() {
            return title;
        }

        public void setTitle(AnimeTitle title) {
            this.title = title;
        }

        public Integer getEpisodes() {
            return episodes;
        }

        public void setEpisodes(Integer episodes) {
            this.episodes = episodes;
        }

        public Integer getAverageScore() {
            return averageScore;
        }

        public void setAverageScore(Integer averageScore) {
            this.averageScore = averageScore;
        }

        public Integer getPopularity() {
            return popularity;
        }

        public void setPopularity(Integer popularity) {
            this.popularity = popularity;
        }

        public AnimeCoverImage getCoverImage() {
            return coverImage;
        }

        public void setCoverImage(AnimeCoverImage coverImage) {
            this.coverImage = coverImage;
        }

        public List<String> getGenres() {
            return genres;
        }

        public void setGenres(List<String> genres) {
            this.genres = genres;
        }

        public List<String> getSynonyms() {
            return synonyms;
        }

        public void setSynonyms(List<String> synonyms) {
            this.synonyms = synonyms;
        }

        public List<AnimeTag> getTags() {
            return tags;
        }

        public void setTags(List<AnimeTag> tags) {
            this.tags = tags;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Boolean getIsAdult() {
            return isAdult;
        }

        public void setIsAdult(Boolean isAdult) {
            this.isAdult = isAdult;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String format) {
            this.format = format;
        }

        public String getSeason() {
            return season;
        }

        public void setSeason(String season) {
            this.season = season;
        }

        public Integer getSeasonYear() {
            return seasonYear;
        }

        public void setSeasonYear(Integer seasonYear) {
            this.seasonYear = seasonYear;
        }

        public List<AnimeStudio> getStudios() {
            return studios;
        }

        public void setStudios(List<AnimeStudio> studios) {
            this.studios = studios;
        }

        @JsonProperty("studios")
        public void setStudiosConnection(StudioConnection studiosConnection) {
            if (studiosConnection == null || studiosConnection.getNodes() == null) {
                this.studios = null;
                return;
            }
            this.studios = studiosConnection.getNodes();
        }

        public List<AnimeRelation> getRelations() {
            return relations;
        }

        public void setRelations(List<AnimeRelation> relations) {
            this.relations = relations;
        }

        @JsonProperty("relations")
        public void setRelationsConnection(RelationConnection relationsConnection) {
            if (relationsConnection == null || relationsConnection.getEdges() == null) {
                this.relations = null;
                return;
            }
            List<AnimeRelation> mapped = new java.util.ArrayList<>();
            for (RelationEdge edge : relationsConnection.getEdges()) {
                if (edge == null || edge.getNode() == null || edge.getNode().getId() == null) {
                    continue;
                }
                AnimeRelation relation = new AnimeRelation();
                relation.setId(edge.getNode().getId());
                relation.setTitle(edge.getNode().getTitle());
                relation.setRelationType(edge.getRelationType());
                mapped.add(relation);
            }
            this.relations = mapped.isEmpty() ? null : mapped;
        }

        public String getRecommendationReason() {
            return recommendationReason;
        }

        public void setRecommendationReason(String recommendationReason) {
            this.recommendationReason = recommendationReason;
        }

        public List<String> getReasonCodes() {
            return reasonCodes;
        }

        public void setReasonCodes(List<String> reasonCodes) {
            this.reasonCodes = reasonCodes;
        }

        public Double getFusionScore() {
            return fusionScore;
        }

        public void setFusionScore(Double fusionScore) {
            this.fusionScore = fusionScore;
        }

        @JsonProperty("query_adherence_score")
        public Double getQueryAdherenceScore() {
            return queryAdherenceScore;
        }

        @JsonProperty("query_adherence_score")
        public void setQueryAdherenceScore(Double queryAdherenceScore) {
            this.queryAdherenceScore = queryAdherenceScore;
        }

        @JsonProperty("query_relevance_score")
        public Double getQueryRelevanceScore() {
            return queryRelevanceScore;
        }

        @JsonProperty("query_relevance_score")
        public void setQueryRelevanceScore(Double queryRelevanceScore) {
            this.queryRelevanceScore = queryRelevanceScore;
        }

        @JsonProperty("user_taste_score")
        public Double getUserTasteScore() {
            return userTasteScore;
        }

        @JsonProperty("user_taste_score")
        public void setUserTasteScore(Double userTasteScore) {
            this.userTasteScore = userTasteScore;
        }

        @JsonProperty("popularity_prior_score")
        public Double getPopularityPriorScore() {
            return popularityPriorScore;
        }

        @JsonProperty("popularity_prior_score")
        public void setPopularityPriorScore(Double popularityPriorScore) {
            this.popularityPriorScore = popularityPriorScore;
        }

        @JsonProperty("guardrail_applied")
        public Boolean getGuardrailApplied() {
            return guardrailApplied;
        }

        @JsonProperty("guardrail_applied")
        public void setGuardrailApplied(Boolean guardrailApplied) {
            this.guardrailApplied = guardrailApplied;
        }

        @JsonAnySetter
        public void putExtraField(String key, Object value) {
            if (key == null || key.isBlank()) {
                return;
            }
            if (this.extraFields == null) {
                this.extraFields = new LinkedHashMap<>();
            }
            this.extraFields.put(key, value);
        }

        @JsonAnyGetter
        public Map<String, Object> getExtraFields() {
            return extraFields;
        }

        public void setExtraFields(Map<String, Object> extraFields) {
            this.extraFields = extraFields;
        }
    }

    public static class AnimeTitle {

        private String romaji;
        private String english;
        private String nativeTitle;

        public String getRomaji() {
            return romaji;
        }

        public void setRomaji(String romaji) {
            this.romaji = romaji;
        }

        public String getEnglish() {
            return english;
        }

        public void setEnglish(String english) {
            this.english = english;
        }

        @JsonProperty("native")
        public String getNativeTitle() {
            return nativeTitle;
        }

        @JsonProperty("native")
        public void setNativeTitle(String nativeTitle) {
            this.nativeTitle = nativeTitle;
        }
    }

    public static class AnimeCoverImage {

        private String large;

        public String getLarge() {
            return large;
        }

        public void setLarge(String large) {
            this.large = large;
        }
    }

    public static class AnimeTag {

        private String name;
        private Integer rank;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getRank() {
            return rank;
        }

        public void setRank(Integer rank) {
            this.rank = rank;
        }
    }

    public static class AnimeStudio {
        private String name;
        private Boolean isAnimationStudio;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Boolean getIsAnimationStudio() {
            return isAnimationStudio;
        }

        public void setIsAnimationStudio(Boolean isAnimationStudio) {
            this.isAnimationStudio = isAnimationStudio;
        }
    }

    public static class AnimeRelation {
        private Integer id;
        private String relationType;
        private AnimeTitle title;

        public Integer getId() {
            return id;
        }

        public void setId(Integer id) {
            this.id = id;
        }

        public String getRelationType() {
            return relationType;
        }

        public void setRelationType(String relationType) {
            this.relationType = relationType;
        }

        public AnimeTitle getTitle() {
            return title;
        }

        public void setTitle(AnimeTitle title) {
            this.title = title;
        }
    }

    public static class PageInfo {
        private Boolean hasNextPage;
        private Integer currentPage;
        private Integer lastPage;

        public Boolean getHasNextPage() {
            return hasNextPage;
        }

        public void setHasNextPage(Boolean hasNextPage) {
            this.hasNextPage = hasNextPage;
        }

        public Integer getCurrentPage() {
            return currentPage;
        }

        public void setCurrentPage(Integer currentPage) {
            this.currentPage = currentPage;
        }

        public Integer getLastPage() {
            return lastPage;
        }

        public void setLastPage(Integer lastPage) {
            this.lastPage = lastPage;
        }
    }

    public static class StudioConnection {
        private List<AnimeStudio> nodes;

        public List<AnimeStudio> getNodes() {
            return nodes;
        }

        public void setNodes(List<AnimeStudio> nodes) {
            this.nodes = nodes;
        }
    }

    public static class RelationConnection {
        private List<RelationEdge> edges;

        public List<RelationEdge> getEdges() {
            return edges;
        }

        public void setEdges(List<RelationEdge> edges) {
            this.edges = edges;
        }
    }

    public static class RelationEdge {
        private String relationType;
        private RelationNode node;

        public String getRelationType() {
            return relationType;
        }

        public void setRelationType(String relationType) {
            this.relationType = relationType;
        }

        public RelationNode getNode() {
            return node;
        }

        public void setNode(RelationNode node) {
            this.node = node;
        }
    }

    public static class RelationNode {
        private Integer id;
        private AnimeTitle title;

        public Integer getId() {
            return id;
        }

        public void setId(Integer id) {
            this.id = id;
        }

        public AnimeTitle getTitle() {
            return title;
        }

        public void setTitle(AnimeTitle title) {
            this.title = title;
        }
    }
}
