import { api } from './client';

// Public anime search/detail API contract.
export async function searchAnime(query) {
	const { data } = await api.get(`/api/anime/search?q=${encodeURIComponent(query)}`);
	return data;
}

export async function getAnimeById(id) {
	const { data } = await api.get(`/api/anime/${id}`);
	return data;
}
