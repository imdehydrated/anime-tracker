import { useState } from 'react';
import { getApiError } from '../api/client';
import { addAnimeToUserList } from '../api/listApi';
import { AnimeSummary } from '../types/anime';

export function useAddToList() {
	const [message, setMessage] = useState('');
	const [error, setError] = useState('');

	const addToList = async (anime: AnimeSummary) => {
		setMessage('');
		setError('');

		try {
			await addAnimeToUserList({
				anilistId: anime.id,
				status: 'PLAN_TO_WATCH',
				title: anime.title.english || anime.title.romaji,
				coverImage:
					typeof anime.coverImage === 'string'
						? anime.coverImage
						: anime.coverImage?.large,
				genres: anime.genres?.join(','),
				totalEpisodes: anime.episodes || null,
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
