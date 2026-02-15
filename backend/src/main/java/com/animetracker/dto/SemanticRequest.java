package com.animetracker.dto;

import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/users/recommendations/semantic.
 * Contains seed anime IDs, an optional text query, and result limit.
 */
public class SemanticRequest {

	@Size(max = 5, message = "Maximum 5 seed anime allowed")
	private List<Integer> seedIds;
	private String query;
	@Min(value = 1, message = "limit must be between 1 and 50")
	@Max(value = 50, message = "limit must be between 1 and 50")
	private Integer limit;
	private Boolean useListOnly;
	@DecimalMin(value = "0.0", message = "listWeight must be between 0 and 1")
	@DecimalMax(value = "1.0", message = "listWeight must be between 0 and 1")
	private Float listWeight;

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
}
