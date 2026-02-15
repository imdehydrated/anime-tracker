import { useState } from 'react';
import { getApiError } from '../api/client';
import {
	addRecommendationBlacklist,
	getRecommendationBlacklist,
	removeRecommendationBlacklist,
} from '../api/recommendationsApi';

// Shared blacklist workflow for recommendation pages.
// Keeps API calls and modal state logic in one reusable place.
export function useRecommendationBlacklist(setError, removeFromResults) {
	const [blacklist, setBlacklist] = useState([]);
	const [showBlacklist, setShowBlacklist] = useState(false);
	const [blacklistSearch, setBlacklistSearch] = useState('');

	const handleBlacklist = async (anime) => {
		try {
			await addRecommendationBlacklist({
				anilistId: anime.id,
				title: anime.title.english || anime.title.romaji,
				coverImage: anime.coverImage?.large,
			});
			removeFromResults(anime.id);
		} catch (err) {
			setError(getApiError(err, 'Failed to hide anime.'));
		}
	};

	const fetchBlacklist = async () => {
		try {
			const data = await getRecommendationBlacklist();
			setBlacklist(data);
		} catch (err) {
			setError(getApiError(err, 'Failed to load blacklist.'));
		}
	};

	const openBlacklist = async () => {
		setShowBlacklist(true);
		await fetchBlacklist();
	};

	const closeBlacklist = () => {
		setShowBlacklist(false);
		setBlacklistSearch('');
	};

	const handleRemoveFromBlacklist = async (id) => {
		try {
			await removeRecommendationBlacklist(id);
			setBlacklist((prev) => prev.filter((item) => item.id !== id));
		} catch (err) {
			setError(getApiError(err, 'Failed to remove from blacklist.'));
		}
	};

	return {
		blacklist,
		showBlacklist,
		blacklistSearch,
		setBlacklistSearch,
		openBlacklist,
		closeBlacklist,
		handleBlacklist,
		handleRemoveFromBlacklist,
	};
}
