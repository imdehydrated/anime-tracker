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
import { useAuthHeader } from '../hooks/useAuthHeader';
import { useAddToList } from '../hooks/useAddToList';
import axios from 'axios';

function Search() {
	const [query, setQuery] = useState('');
	const [results, setResults] = useState([]);
	const [loading, setLoading] = useState(false);
	const [searchError, setSearchError] = useState('');
	const [userListIds, setUserListIds] = useState(new Set());

	const { isLoggedIn } = useAuth();
	const authHeader = useAuthHeader();
	const { addToList, message, error, clearMessages } = useAddToList();

	// Fetch user's list IDs on mount to show "On Your List" badges
	useEffect(() => {
		if (isLoggedIn) {
			axios.get('/api/users/list', authHeader)
				.then(({ data }) => {
					setUserListIds(new Set(data.map(entry => entry.anilistId)));
				})
				.catch(() => {});
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
			performSearch(query);
		}, 400);

		return () => clearTimeout(timer);
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [query]);

	const performSearch = async (searchQuery) => {
		setSearchError('');
		clearMessages();
		setLoading(true);

		try {
			const { data } = await axios.get(`/api/anime/search?q=${encodeURIComponent(searchQuery)}`);
			setResults(data);
		} catch (err) {
			setSearchError('Search failed. Try again.');
		} finally {
			setLoading(false);
		}
	};

	const handleSubmit = (e) => {
		e.preventDefault();
		if (query.length >= 1) performSearch(query);
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

			<div className="card-grid">
				{results.map((anime) => (
					<div key={anime.id} className="anime-card">
						{anime.coverImage && (
							<Link to={`/anime/${anime.id}`}>
								<img src={anime.coverImage.large} alt={anime.title.romaji} />
							</Link>
						)}

						<div className="card-body">
							<h3><Link to={`/anime/${anime.id}`}>{anime.title.english || anime.title.romaji}</Link></h3>
							<p>{anime.genres && anime.genres.join(', ')}</p>
							<p>
								Ep: {anime.episodes || '?'} | Score: <span className="score">{anime.averageScore || '?'}</span>/100
							</p>

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
									<a href="/login">Login</a> or <a href="/register">register</a> to add to your list
								</p>
							)}
						</div>
					</div>
				))}
			</div>
		</div>
	);
}

export default Search;
