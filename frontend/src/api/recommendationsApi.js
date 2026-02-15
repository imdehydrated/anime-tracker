import { api } from './client';

// Recommendation and recommendation-blacklist API contract.
export async function getSemanticRecommendations(payload) {
	const { data } = await api.post('/api/users/recommendations/semantic', payload);
	return data;
}

export async function addRecommendationBlacklist(payload) {
	const { data } = await api.post('/api/users/recommendations/blacklist', payload);
	return data;
}

export async function getRecommendationBlacklist() {
	const { data } = await api.get('/api/users/recommendations/blacklist');
	return data;
}

export async function removeRecommendationBlacklist(id) {
	const { data } = await api.delete(`/api/users/recommendations/blacklist/${id}`);
	return data;
}
