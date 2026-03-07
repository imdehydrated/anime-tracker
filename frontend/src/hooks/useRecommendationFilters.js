import { useCallback, useState } from 'react';

export const RECOMMENDATION_FILTER_DEFAULTS = {
	includeExtraSeasons: false,
	includeMovies: false,
	includeOnasOvasSpecials: false,
	includeMusic: false,
	includeAdult: false,
	popularityAttenuation: 'medium',
};

export const SEARCH_FILTER_DEFAULTS = {
	includeExtraSeasons: false,
	includeMovies: false,
	includeOnasOvasSpecials: false,
	includeMusic: false,
	includeAdult: false,
};

const POPULARITY_VALUES = new Set(['low', 'medium', 'high']);

function normalizeFilters(rawFilters, defaults) {
	const normalized = { ...defaults };
	if (!rawFilters || typeof rawFilters !== 'object') {
		return normalized;
	}

	Object.keys(defaults).forEach((key) => {
		if (key === 'popularityAttenuation') {
			const rawValue = typeof rawFilters[key] === 'string' ? rawFilters[key].toLowerCase() : null;
			if (rawValue && POPULARITY_VALUES.has(rawValue)) {
				normalized[key] = rawValue;
			}
			return;
		}
		if (typeof rawFilters[key] === 'boolean') {
			normalized[key] = rawFilters[key];
		}
	});

	return normalized;
}

/**
 * Shared recommendation/search filter state with optional refresh triggering.
 * Keeps filter defaults and normalization consistent across pages.
 */
export function useRecommendationFilters(defaultFilters) {
	const [filters, setFiltersState] = useState(() => normalizeFilters(defaultFilters, defaultFilters));
	const [refreshNonce, setRefreshNonce] = useState(0);

	const setFilters = useCallback((updater) => {
		setFiltersState((prev) => {
			const nextRaw = typeof updater === 'function' ? updater(prev) : updater;
			return normalizeFilters(nextRaw, defaultFilters);
		});
		setRefreshNonce((prev) => prev + 1);
	}, [defaultFilters]);

	const hydrateFilters = useCallback((rawFilters) => {
		setFiltersState((prev) => normalizeFilters({ ...prev, ...(rawFilters || {}) }, defaultFilters));
	}, [defaultFilters]);

	return {
		filters,
		setFilters,
		hydrateFilters,
		refreshNonce,
	};
}
