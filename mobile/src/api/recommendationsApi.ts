import { api } from './client';
import { AnimeSummary, RecommendationFeedbackItem, SearchPage } from '../types/anime';

export interface RecommendationRequestBody {
	mode: 'semantic' | 'similar' | 'cf';
	query?: string | null;
	seedIds?: number[];
	cursor?: string | null;
	pageSize?: number;
	limit?: number;
	useListOnly?: boolean;
	listWeight?: number;
	filters?: Record<string, unknown>;
}

export interface RecommendationFeedbackPayload {
	anilistId: number;
	signal: 'thumbs_up' | 'thumbs_down';
	sourceMode?: string;
	queryContext?: string | null;
	title?: string | null;
	coverImage?: string | null;
}

export async function getSemanticRecommendations(payload: RecommendationRequestBody): Promise<AnimeSummary[]> {
	const { data } = await api.post('/api/users/recommendations/semantic/scored', payload);
	return normalizeRecommendationPayload(data);
}

export async function getSemanticRecommendationsPaged(
	payload: RecommendationRequestBody
): Promise<SearchPage<AnimeSummary>> {
	try {
		const { data } = await api.post('/api/users/recommendations/semantic/scored/paged', payload);
		return normalizeRecommendationPagePayload(data);
	} catch (err: any) {
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

function normalizeRecommendationPayload(data: unknown): AnimeSummary[] {
	if (!Array.isArray(data)) return [];
	return data.map((item: any) => {
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

function normalizeRecommendationPagePayload(data: any): SearchPage<AnimeSummary> {
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

export async function addRecommendationFeedback(payload: RecommendationFeedbackPayload) {
	const { data } = await api.post('/api/users/recommendations/feedback', payload);
	return data;
}

export async function getRecommendationFeedback(): Promise<RecommendationFeedbackItem[]> {
	const { data } = await api.get('/api/users/recommendations/feedback');
	return Array.isArray(data) ? data : [];
}

export async function removeRecommendationFeedback(id: number) {
	const { data } = await api.delete(`/api/users/recommendations/feedback/${id}`);
	return data;
}
