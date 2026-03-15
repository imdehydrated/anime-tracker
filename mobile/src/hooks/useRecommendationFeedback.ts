import { useEffect, useMemo, useState } from 'react';
import { getApiError } from '../api/client';
import {
	addRecommendationFeedback,
	getRecommendationFeedback,
	removeRecommendationFeedback,
} from '../api/recommendationsApi';
import { AnimeSummary, RecommendationFeedbackItem } from '../types/anime';

export function useRecommendationFeedback(setError: (message: string) => void, isLoggedIn: boolean) {
	const [feedbackItems, setFeedbackItems] = useState<RecommendationFeedbackItem[]>([]);
	const [showFeedbackModal, setShowFeedbackModal] = useState(false);
	const [feedbackSearch, setFeedbackSearch] = useState('');

	const fetchFeedback = async () => {
		try {
			const data = await getRecommendationFeedback();
			setFeedbackItems(data);
		} catch (err) {
			setError(getApiError(err, 'Failed to load feedback.'));
		}
	};

	const handleThumbsDown = async (
		anime: AnimeSummary,
		sourceMode = 'semantic',
		queryContext: string | null = null
	) => {
		try {
			await addRecommendationFeedback({
				anilistId: anime.id,
				signal: 'thumbs_down',
				sourceMode,
				queryContext,
				title: anime.title?.english || anime.title?.romaji,
				coverImage: typeof anime.coverImage === 'string' ? anime.coverImage : anime.coverImage?.large,
			});
			await fetchFeedback();
		} catch (err) {
			setError(getApiError(err, 'Failed to submit feedback.'));
		}
	};

	const handleThumbsUp = async (
		anime: AnimeSummary,
		sourceMode = 'semantic',
		queryContext: string | null = null
	) => {
		try {
			await addRecommendationFeedback({
				anilistId: anime.id,
				signal: 'thumbs_up',
				sourceMode,
				queryContext,
				title: anime.title?.english || anime.title?.romaji,
				coverImage: typeof anime.coverImage === 'string' ? anime.coverImage : anime.coverImage?.large,
			});
			await fetchFeedback();
		} catch (err) {
			setError(getApiError(err, 'Failed to submit feedback.'));
		}
	};

	const openFeedback = async () => {
		setShowFeedbackModal(true);
		await fetchFeedback();
	};

	const closeFeedback = () => {
		setShowFeedbackModal(false);
		setFeedbackSearch('');
	};

	const handleRemoveFeedback = async (id: number) => {
		try {
			await removeRecommendationFeedback(id);
			setFeedbackItems((prev) => prev.filter((item) => item.id !== id));
		} catch (err) {
			setError(getApiError(err, 'Failed to remove feedback.'));
		}
	};

	useEffect(() => {
		if (!isLoggedIn) {
			setFeedbackItems([]);
			return;
		}
		void fetchFeedback();
	}, [isLoggedIn]);

	const feedbackSignalByAnimeId = useMemo(() => {
		const out = new Map<number, string>();
		for (const item of feedbackItems) {
			if (item?.anilistId != null && item?.signal) {
				out.set(item.anilistId, item.signal);
			}
		}
		return out;
	}, [feedbackItems]);

	const getFeedbackSignal = (animeId: number) => feedbackSignalByAnimeId.get(animeId) || null;

	return {
		feedbackItems,
		showFeedbackModal,
		feedbackSearch,
		setFeedbackSearch,
		openFeedback,
		closeFeedback,
		handleThumbsDown,
		handleThumbsUp,
		handleRemoveFeedback,
		getFeedbackSignal,
	};
}
