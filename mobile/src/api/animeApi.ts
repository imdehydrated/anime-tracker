import { api } from './client';
import { AnimeDetail, AnimeSummary, RecommendationRequestFilters, SearchPage } from '../types/anime';

export async function searchAnimePaged(
	query: string,
	filters: Partial<RecommendationRequestFilters> | null = null,
	cursor: string | null = null,
	pageSize = 20
): Promise<SearchPage<AnimeSummary>> {
	const params = new URLSearchParams();
	params.set('q', query);
	params.set('pageSize', String(Math.min(50, Math.max(1, Number.isFinite(pageSize) ? pageSize : 20))));
	if (typeof cursor === 'string' && cursor.length > 0) {
		params.set('cursor', cursor);
	}

	if (filters && typeof filters === 'object') {
		const boolKeys: Array<keyof RecommendationRequestFilters> = [
			'includeExtraSeasons',
			'includeMovies',
			'includeOnasOvasSpecials',
			'includeMusic',
			'includeAdult',
		];
		boolKeys.forEach((key) => {
			if (typeof filters[key] === 'boolean') {
				params.set(key, String(filters[key]));
			}
		});
	}

	const { data } = await api.get(`/api/anime/search/paged?${params.toString()}`);
	return {
		items: Array.isArray(data?.items) ? data.items : [],
		nextCursor: typeof data?.nextCursor === 'string' ? data.nextCursor : null,
		hasMore: Boolean(data?.hasMore),
		diagnostics: data?.diagnostics || null,
	};
}

export async function getAnimeById(id: number | string): Promise<AnimeDetail> {
	const { data } = await api.get(`/api/anime/${id}`);
	return data;
}

export async function getPopularAnime(limit = 20): Promise<AnimeSummary[]> {
	const safeLimit = Math.min(40, Math.max(1, Number.isFinite(limit) ? limit : 20));
	const { data } = await api.get(`/api/anime/popular?limit=${safeLimit}`);
	return Array.isArray(data) ? data : [];
}
