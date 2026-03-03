import { api } from './client';

// Public anime search/detail API contract.
export async function searchAnime(query, filters = null) {
	const params = new URLSearchParams();
	params.set('q', query);

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

	const { data } = await api.get(`/api/anime/search?${params.toString()}`);
	return data;
}

export async function getAnimeById(id) {
	const { data } = await api.get(`/api/anime/${id}`);
	return data;
}
