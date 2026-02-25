import { api } from './client';

// Recommendation and recommendation-blacklist API contract.
export async function getSemanticRecommendations(payload) {
	try {
		const { data } = await api.post('/api/users/recommendations/semantic/scored', payload);
		return normalizeRecommendationPayload(data);
	} catch (err) {
		// Compatibility fallback while backend rollout is in progress.
		if (err?.response?.status === 404) {
			const { data } = await api.post('/api/users/recommendations/semantic', payload);
			return normalizeRecommendationPayload(data);
		}
		throw err;
	}
}

function normalizeRecommendationPayload(data) {
	if (!Array.isArray(data)) return data;
	return data.map((item) => {
		// Legacy shape: AnimeInfo
		// Scored shape: RecommendationResponse { anime, reasonCodes }
		if (item?.anime && typeof item.anime === 'object') {
			const anime = { ...item.anime };
			if (item.reasonCodes != null && anime.reasonCodes == null) {
				anime.reasonCodes = item.reasonCodes;
			}
			return anime;
		}
		return item;
	});
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
