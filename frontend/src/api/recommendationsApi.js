import { api } from './client';

// Recommendation and recommendation-feedback API contract.
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

export async function addRecommendationFeedback(payload) {
	const { data } = await api.post('/api/users/recommendations/feedback', payload);
	return data;
}

export async function getRecommendationFeedback() {
	const { data } = await api.get('/api/users/recommendations/feedback');
	return data;
}

export async function removeRecommendationFeedback(id) {
	const { data } = await api.delete(`/api/users/recommendations/feedback/${id}`);
	return data;
}
