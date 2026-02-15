import { useState } from 'react';
import { getApiError } from '../api/client';
import { addAnimeToUserList } from '../api/listApi';

// Shared hook for adding anime to the user's list
// Used by Search.js, Recommendations.js, and AnimeDetail.js
export function useAddToList() {
	const [message, setMessage] = useState('');
	const [error, setError] = useState('');

	const addToList = async (anime) => {
		setMessage('');
		setError('');

		try {
			await addAnimeToUserList({
				anilistId: anime.id,
				status: 'PLAN_TO_WATCH',
				title: anime.title.english || anime.title.romaji,
				coverImage: anime.coverImage?.large,
				genres: anime.genres?.join(','),
				totalEpisodes: anime.episodes || null
			});
			setMessage(`Added "${anime.title.english || anime.title.romaji}" to your list!`);
			return true;
		} catch (err) {
			setError(getApiError(err, 'Failed to add to list'));
			return false;
		}
	};

	const clearMessages = () => {
		setMessage('');
		setError('');
	};

	return { addToList, message, error, clearMessages, setError };
}
