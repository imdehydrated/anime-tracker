import { useState, useEffect, useCallback } from 'react';
import axios from 'axios';

/**
 * Shared hook for debounced anime search.
 * Used by Search.js (main search) and SmartSearch.js (seed picker).
 *
 * @param {number} debounceMs - Delay before firing search (default 400ms)
 * @param {number} minChars - Minimum characters before searching (default 3)
 */
export function useDebounceSearch(debounceMs = 400, minChars = 3) {
	const [query, setQuery] = useState('');
	const [results, setResults] = useState([]);
	const [loading, setLoading] = useState(false);
	const [error, setError] = useState('');

	const search = useCallback(async (searchQuery) => {
		setError('');
		setLoading(true);

		try {
			const { data } = await axios.get(`/api/anime/search?q=${encodeURIComponent(searchQuery)}`);
			setResults(data);
		} catch {
			setError('Search failed. Try again.');
		} finally {
			setLoading(false);
		}
	}, []);

	useEffect(() => {
		if (query.length < minChars) {
			if (query.length === 0) setResults([]);
			return;
		}

		const timer = setTimeout(() => search(query), debounceMs);
		return () => clearTimeout(timer);
	}, [query, debounceMs, minChars, search]);

	const clearResults = useCallback(() => {
		setQuery('');
		setResults([]);
		setError('');
	}, []);

	return { query, setQuery, results, loading, error, search, clearResults };
}