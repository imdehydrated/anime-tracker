import { api } from './client';

// Public anime search/detail API contract.
export async function searchAnimePaged(query, filters = null, cursor = null, pageSize = 20) {
	const params = new URLSearchParams();
	params.set('q', query);
	params.set('pageSize', String(Math.min(50, Math.max(1, Number.isFinite(pageSize) ? pageSize : 20))));
	if (typeof cursor === 'string' && cursor.length > 0) {
		params.set('cursor', cursor);
	}

	if (filters && typeof filters === 'object') {
		const boolKeys = [
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

export async function getAnimeById(id) {
	const { data } = await api.get(`/api/anime/${id}`);
	return data;
}

export async function getPopularAnime(limit = 20) {
	const safeLimit = Math.min(40, Math.max(1, Number.isFinite(limit) ? limit : 20));
	const { data } = await api.get(`/api/anime/popular?limit=${safeLimit}`);
	return Array.isArray(data) ? data : [];
}
