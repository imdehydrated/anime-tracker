import { useState, useEffect, useCallback, useRef } from 'react';
import { getApiError } from '../api/client';
import { searchAnimePaged } from '../api/animeApi';

/**
 * Shared hook for debounced anime search.
 * Used by Search.js (main search) and SmartRec.js (seed picker).
 *
 * @param {number} debounceMs - Delay before firing search (default 400ms)
 * @param {number} minChars - Minimum characters before searching (default 3)
 */
const DEFAULT_PAGE_SIZE = 12;
const LOADING_INDICATOR_DELAY_MS = 120;

export function useDebounceSearch(
	debounceMs = 250,
	minChars = 3,
	pageSize = DEFAULT_PAGE_SIZE,
	searchFilters = null
) {
	const [query, setQuery] = useState('');
	const [results, setResults] = useState([]);
	const [loading, setLoading] = useState(false);
	const [error, setError] = useState('');
	const requestSeqRef = useRef(0);
	const activeRequestSeqRef = useRef(0);
	const loadingTimerRef = useRef(null);

	const clearLoadingTimer = useCallback(() => {
		if (loadingTimerRef.current) {
			clearTimeout(loadingTimerRef.current);
			loadingTimerRef.current = null;
		}
	}, []);

	const search = useCallback(async (searchQuery) => {
		const normalized = (searchQuery || '').trim();
		if (normalized.length < minChars) {
			clearLoadingTimer();
			setLoading(false);
			if (normalized.length === 0) {
				setResults([]);
				setError('');
			}
			return;
		}

		const requestSeq = requestSeqRef.current + 1;
		requestSeqRef.current = requestSeq;
		activeRequestSeqRef.current = requestSeq;
		setError('');
		clearLoadingTimer();
		loadingTimerRef.current = setTimeout(() => {
			if (activeRequestSeqRef.current === requestSeq) {
				setLoading(true);
			}
		}, LOADING_INDICATOR_DELAY_MS);

		try {
			const page = await searchAnimePaged(normalized, searchFilters, null, pageSize);
			if (activeRequestSeqRef.current !== requestSeq) return;
			setResults(Array.isArray(page?.items) ? page.items : []);
		} catch (err) {
			if (activeRequestSeqRef.current !== requestSeq) return;
			setError(getApiError(err, 'Search failed. Try again.'));
		} finally {
			if (activeRequestSeqRef.current === requestSeq) {
				clearLoadingTimer();
				setLoading(false);
			}
		}
	}, [clearLoadingTimer, minChars, pageSize, searchFilters]);

	useEffect(() => {
		if (query.length < minChars) {
			clearLoadingTimer();
			setLoading(false);
			if (query.length === 0) {
				setResults([]);
				setError('');
			}
			return;
		}

		const timer = setTimeout(() => search(query), debounceMs);
		return () => {
			clearTimeout(timer);
		};
	}, [query, debounceMs, minChars, search, clearLoadingTimer]);

	useEffect(() => () => clearLoadingTimer(), [clearLoadingTimer]);

	const clearResults = useCallback(() => {
		setQuery('');
		setResults([]);
		setError('');
		clearLoadingTimer();
		setLoading(false);
		activeRequestSeqRef.current = requestSeqRef.current;
	}, [clearLoadingTimer]);

	return { query, setQuery, results, loading, error, search, clearResults };
}
