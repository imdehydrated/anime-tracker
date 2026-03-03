import { useState } from 'react';
import { getApiError } from '../api/client';
import {
	addRecommendationFeedback,
	getRecommendationFeedback,
	removeRecommendationFeedback,
} from '../api/recommendationsApi';

// Shared thumbs feedback workflow for recommendation pages.
export function useRecommendationFeedback(setError, removeFromResults) {
	const [feedbackItems, setFeedbackItems] = useState([]);
	const [showFeedbackModal, setShowFeedbackModal] = useState(false);
	const [feedbackSearch, setFeedbackSearch] = useState('');

	const handleThumbsDown = async (anime, sourceMode = 'semantic', queryContext = null) => {
		try {
			await addRecommendationFeedback({
				anilistId: anime.id,
				signal: 'thumbs_down',
				sourceMode,
				queryContext,
				title: anime.title?.english || anime.title?.romaji,
				coverImage: anime.coverImage?.large,
			});
			removeFromResults(anime.id);
		} catch (err) {
			setError(getApiError(err, 'Failed to submit feedback.'));
		}
	};

	const handleThumbsUp = async (anime, sourceMode = 'semantic', queryContext = null) => {
		try {
			await addRecommendationFeedback({
				anilistId: anime.id,
				signal: 'thumbs_up',
				sourceMode,
				queryContext,
				title: anime.title?.english || anime.title?.romaji,
				coverImage: anime.coverImage?.large,
			});
		} catch (err) {
			setError(getApiError(err, 'Failed to submit feedback.'));
		}
	};

	const fetchFeedback = async () => {
		try {
			const data = await getRecommendationFeedback();
			setFeedbackItems(data);
		} catch (err) {
			setError(getApiError(err, 'Failed to load feedback.'));
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

	const handleRemoveFeedback = async (id) => {
		try {
			await removeRecommendationFeedback(id);
			setFeedbackItems((prev) => prev.filter((item) => item.id !== id));
		} catch (err) {
			setError(getApiError(err, 'Failed to remove feedback.'));
		}
	};

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
	};
}
