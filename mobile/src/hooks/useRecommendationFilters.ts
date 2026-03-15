import { useCallback, useState } from 'react';
import { RecommendationRequestFilters } from '../types/anime';

export const RECOMMENDATION_FILTER_DEFAULTS: RecommendationRequestFilters = {
	includeExtraSeasons: false,
	includeMovies: false,
	includeOnasOvasSpecials: false,
	includeMusic: false,
	includeAdult: false,
	popularityAttenuation: 'medium',
};

export const SEARCH_FILTER_DEFAULTS: RecommendationRequestFilters = {
	includeExtraSeasons: false,
	includeMovies: false,
	includeOnasOvasSpecials: false,
	includeMusic: false,
	includeAdult: false,
};

const POPULARITY_VALUES = new Set(['low', 'medium', 'high']);

function normalizeFilters<T extends RecommendationRequestFilters>(rawFilters: Partial<T> | null | undefined, defaults: T): T {
	const normalized = { ...defaults };
	if (!rawFilters || typeof rawFilters !== 'object') {
		return normalized;
	}

	Object.keys(defaults).forEach((key) => {
		const typedKey = key as keyof T;
		if (typedKey === 'popularityAttenuation') {
			const rawValue =
				typeof rawFilters[typedKey] === 'string'
					? String(rawFilters[typedKey]).toLowerCase()
					: null;
			if (rawValue && POPULARITY_VALUES.has(rawValue)) {
				normalized[typedKey] = rawValue as T[keyof T];
			}
			return;
		}
		if (typeof rawFilters[typedKey] === 'boolean') {
			normalized[typedKey] = rawFilters[typedKey] as T[keyof T];
		}
	});

	return normalized;
}

export function useRecommendationFilters<T extends RecommendationRequestFilters>(defaultFilters: T) {
	const [filters, setFiltersState] = useState<T>(() => normalizeFilters(defaultFilters, defaultFilters));
	const [refreshNonce, setRefreshNonce] = useState(0);

	const setFilters = useCallback((updater: T | ((previous: T) => T)) => {
		setFiltersState((prev) => {
			const nextRaw = typeof updater === 'function' ? updater(prev) : updater;
			return normalizeFilters(nextRaw, defaultFilters);
		});
		setRefreshNonce((prev) => prev + 1);
	}, [defaultFilters]);

	const hydrateFilters = useCallback((rawFilters: Partial<T>) => {
		setFiltersState((prev) => normalizeFilters({ ...prev, ...(rawFilters || {}) }, defaultFilters));
	}, [defaultFilters]);

	return {
		filters,
		setFilters,
		hydrateFilters,
		refreshNonce,
	};
}
