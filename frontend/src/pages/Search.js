/**
 * Search Page — Search for anime and add them to your list.
 *
 * Uses debounced live search (fires after 400ms, 3+ chars).
 * Shows "On Your List" badge for anime already on user's list.
 * Search is a public endpoint; adding to list requires JWT auth.
 */
import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useAddToList } from '../hooks/useAddToList';
import { getApiError } from '../api/client';
import { searchAnime } from '../api/animeApi';
import { getUserList } from '../api/listApi';
import FilterControlPanel from '../components/FilterControlPanel';

import AnimeCard from '../components/AnimeCard';

const DEFAULT_SEARCH_FILTERS = {
	includeExtraSeasons: false,
	includeMovies: false,
	includeOnasOvasSpecials: false,
	includeMusic: false,
	includeAdult: false,
};

function Search() {
	const [query, setQuery] = useState('');
	const [results, setResults] = useState([]);
	const [loading, setLoading] = useState(false);
	const [searchError, setSearchError] = useState('');
	const [filters, setFilters] = useState(DEFAULT_SEARCH_FILTERS);
	const [userListIds, setUserListIds] = useState(new Set());

	const { isLoggedIn } = useAuth();
	const { addToList, message, error, clearMessages } = useAddToList();

	// Fetch user's list IDs on mount to show "On Your List" badges
	useEffect(() => {
		if (isLoggedIn) {
			getUserList()
				.then((data) => {
					setUserListIds(new Set(data.map(entry => entry.anilistId)));
				})
				.catch(() => { });
		}
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [isLoggedIn]);

	// Debounced live search — fires 400ms after user stops typing
	useEffect(() => {
		if (query.length < 3) {
			if (query.length === 0) setResults([]);
			return;
		}

		const timer = setTimeout(() => {
			performSearch(query, filters);
		}, 400);

		return () => clearTimeout(timer);
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [query, filters]);

	const performSearch = async (searchQuery, activeFilters = filters) => {
		setSearchError('');
		clearMessages();
		setLoading(true);

		try {
			const data = await searchAnime(searchQuery, activeFilters);
			setResults(data);
		} catch (err) {
			setSearchError(getApiError(err, 'Search failed. Try again.'));
		} finally {
			setLoading(false);
		}
	};

	const handleSubmit = (e) => {
		e.preventDefault();
		if (query.length >= 1) performSearch(query, filters);
	};

	const handleAddToList = async (anime) => {
		const success = await addToList(anime);
		if (success) {
			setUserListIds(prev => new Set([...prev, anime.id]));
		}
	};

	return (
		<div className="page">
			<h1>Search Anime</h1>

			<form onSubmit={handleSubmit} className="search-form">
				<input
					type="text"
					placeholder="Search anime... (e.g., Naruto)"
					value={query}
					onChange={(e) => setQuery(e.target.value)}
				/>
				<button type="submit" disabled={loading}>
					{loading ? 'Searching...' : 'Search'}
				</button>
			</form>

			{(searchError || error) && <p className="error-message">{searchError || error}</p>}
			{message && <p className="success-message">{message}</p>}

			<FilterControlPanel
				title="Search Filters"
				filters={filters}
				setFilters={setFilters}
				showPopularityAttenuation={false}
				showAdultToggle={true}
			/>

			<div className="card-grid">
				{results.map((anime) => (
					<AnimeCard key={anime.id} anime={anime}>
						{isLoggedIn ? (
							userListIds.has(anime.id) ? (
								<span className="on-list-badge">On Your List</span>
							) : (
								<button onClick={() => handleAddToList(anime)}>
									Add to List
								</button>
							)
						) : (
							<p className="login-prompt">
								<Link to="/login">Login</Link> or <Link to="/register">register</Link> to add to your list
							</p>
						)}
					</AnimeCard>
				))}
			</div>
		</div>
	);
}

export default Search;
