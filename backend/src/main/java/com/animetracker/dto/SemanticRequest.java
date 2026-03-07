package com.animetracker.dto;

import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Shared request body for recommendation endpoints:
 * - POST /api/users/recommendations/semantic/scored
 * - POST /api/users/recommendations/semantic/scored/paged
 */
public class SemanticRequest {

	@Size(max = 5, message = "Maximum 5 seed anime allowed")
	private List<Integer> seedIds;
	private String query;
	@Min(value = 1, message = "limit must be between 1 and 100")
	@Max(value = 100, message = "limit must be between 1 and 100")
	private Integer limit;
	private Boolean useListOnly;
	@DecimalMin(value = "0.0", message = "listWeight must be between 0 and 1")
	@DecimalMax(value = "1.0", message = "listWeight must be between 0 and 1")
	private Float listWeight;
	private String mode; // "semantic" (default), "similar", or "cf"
	private String cursor;
	@Min(value = 1, message = "pageSize must be between 1 and 100")
	@Max(value = 100, message = "pageSize must be between 1 and 100")
	private Integer pageSize;
	private Boolean excludeSeen;
	private Filters filters;

	public List<Integer> getSeedIds() { return seedIds; }
	public void setSeedIds(List<Integer> seedIds) { this.seedIds = seedIds; }

	public String getQuery() { return query; }
	public void setQuery(String query) { this.query = query; }

	public Integer getLimit() { return limit; }
	public void setLimit(Integer limit) { this.limit = limit; }

	public Boolean getUseListOnly() { return useListOnly; }
	public void setUseListOnly(Boolean useListOnly) { this.useListOnly = useListOnly; }

	public Float getListWeight() { return listWeight; }
	public void setListWeight(Float listWeight) { this.listWeight = listWeight; }

	public String getMode() { return mode; }
	public void setMode(String mode) { this.mode = mode; }

	public String getCursor() { return cursor; }
	public void setCursor(String cursor) { this.cursor = cursor; }

	public Integer getPageSize() { return pageSize; }
	public void setPageSize(Integer pageSize) { this.pageSize = pageSize; }

	public Boolean getExcludeSeen() { return excludeSeen; }
	public void setExcludeSeen(Boolean excludeSeen) { this.excludeSeen = excludeSeen; }

	public Filters getFilters() { return filters; }
	public void setFilters(Filters filters) { this.filters = filters; }

	public static class Filters {
		private Boolean includeExtraSeasons;
		private Boolean includeMovies;
		private Boolean includeOnasOvasSpecials;
		private Boolean includeMusic;
		private Boolean includeAdult;
		private String popularityAttenuation;

		public Boolean getIncludeExtraSeasons() { return includeExtraSeasons; }
		public void setIncludeExtraSeasons(Boolean includeExtraSeasons) { this.includeExtraSeasons = includeExtraSeasons; }

		public Boolean getIncludeMovies() { return includeMovies; }
		public void setIncludeMovies(Boolean includeMovies) { this.includeMovies = includeMovies; }

		public Boolean getIncludeOnasOvasSpecials() { return includeOnasOvasSpecials; }
		public void setIncludeOnasOvasSpecials(Boolean includeOnasOvasSpecials) { this.includeOnasOvasSpecials = includeOnasOvasSpecials; }

		public Boolean getIncludeMusic() { return includeMusic; }
		public void setIncludeMusic(Boolean includeMusic) { this.includeMusic = includeMusic; }

		public Boolean getIncludeAdult() { return includeAdult; }
		public void setIncludeAdult(Boolean includeAdult) { this.includeAdult = includeAdult; }

		public String getPopularityAttenuation() { return popularityAttenuation; }
		public void setPopularityAttenuation(String popularityAttenuation) { this.popularityAttenuation = popularityAttenuation; }
	}
}
