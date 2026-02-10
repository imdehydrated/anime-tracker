import { useState } from 'react';
import { useAuth } from '../context/AuthContext';
import axios from 'axios';

// Shared hook for adding anime to the user's list
// Used by Search.js, Recommendations.js, and AnimeDetail.js
export function useAddToList() {
	const [message, setMessage] = useState('');
	const [error, setError] = useState('');
	const { token } = useAuth();

	const addToList = async (anime) => {
		setMessage('');
		setError('');

		try {
			await axios.post('/api/users/list',
				{
					anilistId: anime.id,
					status: 'PLAN_TO_WATCH',
					title: anime.title.english || anime.title.romaji,
					coverImage: anime.coverImage?.large,
					genres: anime.genres?.join(','),
					totalEpisodes: anime.episodes || null
				},
				{ headers: { Authorization: `Bearer ${token}` } }
			);
			setMessage(`Added "${anime.title.english || anime.title.romaji}" to your list!`);
			return true;
		} catch (err) {
			setError(err.response?.data?.error || 'Failed to add to list');
			return false;
		}
	};

	const clearMessages = () => {
		setMessage('');
		setError('');
	};

	return { addToList, message, error, clearMessages, setError };
}
