package com.animetracker.dto;

import java.util.List;

/**
 * Request body for POST /api/users/recommendations/semantic.
 * Contains seed anime IDs, an optional text query, and result limit.
 */
public class SemanticRequest {

	private List<Integer> seedIds;
	private String query;
	private Integer limit;

	public List<Integer> getSeedIds() { return seedIds; }
	public void setSeedIds(List<Integer> seedIds) { this.seedIds = seedIds; }

	public String getQuery() { return query; }
	public void setQuery(String query) { this.query = query; }

	public Integer getLimit() { return limit; }
	public void setLimit(Integer limit) { this.limit = limit; }
}
