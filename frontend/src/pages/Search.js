import { useEffect, useRef, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useAddToList } from '../hooks/useAddToList';
import { getApiError } from '../api/client';
import { searchAnimePaged } from '../api/animeApi';
import { getUserList } from '../api/listApi';
import {
	useRecommendationFilters,
	SEARCH_FILTER_DEFAULTS,
} from '../hooks/useRecommendationFilters';
import FilterControlPanel from '../components/FilterControlPanel';
import AnimeCard from '../components/AnimeCard';

const SEARCH_PAGE_SIZE = 20;
const SEARCH_MAX_RESULTS = 100;
const SEARCH_MIN_CHARS = 2;
const SEARCH_DEBOUNCE_MS = 250;

function Search() {
	const location = useLocation();
	const [query, setQuery] = useState('');
	const [results, setResults] = useState([]);
	const [loading, setLoading] = useState(false);
	const [loadingMore, setLoadingMore] = useState(false);
	const [nextCursor, setNextCursor] = useState(null);
	const [hasMore, setHasMore] = useState(false);
	const [searchError, setSearchError] = useState('');
	const { filters, setFilters } = useRecommendationFilters(SEARCH_FILTER_DEFAULTS);
	const [userListIds, setUserListIds] = useState(new Set());
	const requestSeqRef = useRef(0);
	const activeRequestSeqRef = useRef(0);

	const { isLoggedIn } = useAuth();
	const { addToList, message, error, clearMessages } = useAddToList();

	useEffect(() => {
		if (isLoggedIn) {
			getUserList()
				.then((data) => {
					setUserListIds(new Set(data.map((entry) => entry.anilistId)));
				})
				.catch(() => { });
		}
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [isLoggedIn]);

	useEffect(() => {
		if (query.length < SEARCH_MIN_CHARS) {
			if (query.length === 0) {
				setResults([]);
				setNextCursor(null);
				setHasMore(false);
			}
			return;
		}

		const timer = setTimeout(() => {
			performSearch(query, filters, false);
		}, SEARCH_DEBOUNCE_MS);

		return () => clearTimeout(timer);
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [query, filters]);

	useEffect(() => {
		const prefill = location.state?.prefillQuery;
		if (typeof prefill === 'string' && prefill.trim().length > 0) {
			setQuery(prefill.trim());
		}
	}, [location.state]);

	const performSearch = async (searchQuery, activeFilters = filters, append = false) => {
		const requestSeq = requestSeqRef.current + 1;
		requestSeqRef.current = requestSeq;
		activeRequestSeqRef.current = requestSeq;

		setSearchError('');
		clearMessages();
		if (append) {
			setLoadingMore(true);
		} else {
			setLoading(true);
			setNextCursor(null);
			setHasMore(false);
		}

		try {
			const cursor = append ? nextCursor : null;
			const page = await searchAnimePaged(searchQuery, activeFilters, cursor, SEARCH_PAGE_SIZE);
			if (activeRequestSeqRef.current !== requestSeq) return;
			const items = Array.isArray(page.items) ? page.items : [];
			if (append) {
				setResults((prev) => {
					const byId = new Map(prev.map((item) => [item.id, item]));
					items.forEach((item) => byId.set(item.id, item));
					const merged = Array.from(byId.values());
					return merged.slice(0, SEARCH_MAX_RESULTS);
				});
			} else {
				setResults(items.slice(0, SEARCH_MAX_RESULTS));
			}
			setNextCursor(page.nextCursor || null);
			setHasMore(Boolean(page.hasMore));
		} catch (err) {
			if (activeRequestSeqRef.current !== requestSeq) return;
			setSearchError(getApiError(err, 'Search failed. Try again.'));
		} finally {
			if (activeRequestSeqRef.current !== requestSeq) return;
			if (append) {
				setLoadingMore(false);
			} else {
				setLoading(false);
			}
		}
	};

	const handleSubmit = (e) => {
		e.preventDefault();
		if (query.trim().length >= SEARCH_MIN_CHARS) {
			performSearch(query, filters, false);
		}
	};

	const handleLoadMore = () => {
		if (loading
			|| loadingMore
			|| !hasMore
			|| !nextCursor
			|| query.trim().length < SEARCH_MIN_CHARS
			|| results.length >= SEARCH_MAX_RESULTS) {
			return;
		}
		performSearch(query, filters, true);
	};

	const handleAddToList = async (anime) => {
		const success = await addToList(anime);
		if (success) {
			setUserListIds((prev) => new Set([...prev, anime.id]));
		}
	};

	return (
		<div className="page search-page">
			<div className="search-toolbar fade-in-up">
				<div className="search-toolbar-header">
					<div className="search-toolbar-copy">
						<p className="search-toolbar-eyebrow">Catalog Search</p>
						<h1>Find titles by franchise or clean title fragments.</h1>
						<p className="page-subtitle">
							Use the catalog search when you know the show, want to inspect detail pages, or want a faster route into your list.
						</p>
					</div>
					{results.length > 0 && (
						<p className="search-result-count search-toolbar-count">
							Showing {results.length}
							{hasMore && results.length < SEARCH_MAX_RESULTS ? '+' : ''} results
						</p>
					)}
				</div>

				<form onSubmit={handleSubmit} className="search-form">
					<input
						type="text"
						placeholder="Search anime... (e.g., Naruto)"
						value={query}
						onChange={(e) => setQuery(e.target.value)}
					/>
					<button type="submit" className="btn-primary" disabled={loading}>
						{loading ? 'Searching...' : 'Search'}
					</button>
				</form>

				<FilterControlPanel
					title="Search Filters"
					filters={filters}
					setFilters={setFilters}
					showPopularityAttenuation={false}
					showAdultToggle={true}
				/>
			</div>

			{(searchError || error) && <p className="error-message">{searchError || error}</p>}
			{message && <p className="success-message">{message}</p>}

			<div className="card-grid anime-grid">
				{results.map((anime) => (
					<AnimeCard
						key={anime.id}
						anime={anime}
						action={isLoggedIn && !userListIds.has(anime.id) ? (
							<button className="btn-primary" onClick={() => handleAddToList(anime)}>
								Add to List
							</button>
						) : null}
					>
						{isLoggedIn ? (
							userListIds.has(anime.id) ? (
								<span className="on-list-badge">On Your List</span>
							) : null
						) : (
							<p className="login-prompt">
								<Link to="/login">Login</Link> or <Link to="/register">register</Link> to save titles to your list
							</p>
						)}
					</AnimeCard>
				))}
			</div>

			{!loading && !searchError && query.trim().length >= SEARCH_MIN_CHARS && results.length === 0 && (
				<div className="empty-state empty-state-block">
					<p className="empty-state-kicker">No results found</p>
					<h2>Try a broader title or fewer constraints.</h2>
					<p>Catalog search works best with recognizable title fragments, franchise names, and clean theme keywords.</p>
				</div>
			)}
			{query.trim().length > 0 && query.trim().length < SEARCH_MIN_CHARS && (
				<div className="empty-state empty-state-block">
					<p className="empty-state-kicker">Search hint</p>
					<h2>Type at least {SEARCH_MIN_CHARS} characters.</h2>
					<p>Start with a title fragment like “naru”, a franchise keyword, or a short anime name.</p>
				</div>
			)}
			{results.length >= SEARCH_MAX_RESULTS && (
				<p className="empty-state">Reached max of {SEARCH_MAX_RESULTS} results for this query.</p>
			)}

			{hasMore && (
				<div className="smart-rec-actions">
					<button
						type="button"
						className="refresh-btn"
						onClick={handleLoadMore}
						disabled={loading || loadingMore || !nextCursor || results.length >= SEARCH_MAX_RESULTS}
					>
						{loadingMore ? 'Loading...' : 'Load More'}
					</button>
				</div>
			)}
		</div>
	);
}

export default Search;
