import { api } from './client';

// Recommendation and recommendation-feedback API contract.
export async function getSemanticRecommendations(payload) {
	const { data } = await api.post('/api/users/recommendations/semantic/scored', payload);
	return normalizeRecommendationPayload(data);
}

export async function getSemanticRecommendationsPaged(payload) {
	try {
		const { data } = await api.post('/api/users/recommendations/semantic/scored/paged', payload);
		return normalizeRecommendationPagePayload(data);
	} catch (err) {
		// Backward-compatible fallback for environments without paged endpoint.
		if (err?.response?.status === 404) {
			const items = await getSemanticRecommendations(payload);
			return {
				items,
				nextCursor: null,
				hasMore: false,
				diagnostics: { fallback: true },
			};
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

function normalizeRecommendationPagePayload(data) {
	if (!data || typeof data !== 'object') {
		return { items: [], nextCursor: null, hasMore: false, diagnostics: null };
	}
	const items = normalizeRecommendationPayload(Array.isArray(data.items) ? data.items : []);
	return {
		items,
		nextCursor: typeof data.nextCursor === 'string' ? data.nextCursor : null,
		hasMore: Boolean(data.hasMore),
		diagnostics: data.diagnostics ?? null,
	};
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
