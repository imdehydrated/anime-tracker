/**
 * Search Page — Search for anime and add them to your list.
 *
 * Uses the card grid layout for results (cover image cards in a responsive grid).
 * Search is a public endpoint; adding to list requires JWT auth.
 */
import { useState } from 'react';
import { useAuth } from '../context/AuthContext';
import axios from 'axios';

function Search() {
	// Search input and results state
	const [query, setQuery] = useState('');
	const [results, setResults] = useState([]);
	const [loading, setLoading] = useState(false);
	const [error, setError] = useState('');
	const [message, setMessage] = useState('');

	// Auth context — token needed for "Add to List" POST request
	const { token, isLoggedIn } = useAuth();

	/**
	 * handleSearch — Fetches anime from AniList via our backend.
	 * Hits the public GET /api/anime/search endpoint (no token needed).
	 */
	const handleSearch = async (e) => {
		e.preventDefault(); // Prevent page reload on form submit
		setError('');
		setMessage('');
		setLoading(true);

		try {
			const { data } = await axios.get(`/api/anime/search?q=${encodeURIComponent(query)}`);
			setResults(data);
		} catch (err) {
			setError('Search failed. Try again.');
		} finally {
			setLoading(false); // Always stop loading spinner
		}
	};

	/**
	 * handleAddToList — Adds an anime to the user's list.
	 * Sends POST /api/users/list with anime details (protected endpoint).
	 */
	const handleAddToList = async (anime) => {
		setMessage('');
		setError('');

		try {
			await axios.post('/api/users/list',
				{
					anilistId: anime.id,
					status: 'PLAN_TO_WATCH',
					title: anime.title.english || anime.title.romaji,
					coverImage: anime.coverImage?.large
				},
				{ headers: { Authorization: `Bearer ${token}` } }
			);
			setMessage(`Added "${anime.title.english || anime.title.romaji}" to your list!`);
		} catch (err) {
			setError(err.response?.data?.error || 'Failed to add to list');
		}
	};

	return (
		<div className="page">
			<h1>Search Anime</h1>

			{/* Search bar — uses search-form class for flex layout */}
			<form onSubmit={handleSearch} className="search-form">
				<input
					type="text"
					placeholder="Search anime... (e.g., Naruto)"
					value={query}
					onChange={(e) => setQuery(e.target.value)}
					required
				/>
				<button type="submit" disabled={loading}>
					{loading ? 'Searching...' : 'Search'}
				</button>
			</form>

			{/* Status messages with styled backgrounds */}
			{error && <p className="error-message">{error}</p>}
			{message && <p className="success-message">{message}</p>}

			{/* Results displayed in a responsive card grid */}
			<div className="card-grid">
				{results.map((anime) => (
					<div key={anime.id} className="anime-card">
						{/* Cover image — fills card width, fixed height */}
						{anime.coverImage && (
							<img src={anime.coverImage.large} alt={anime.title.romaji} />
						)}

						{/* Card body — title, metadata, and add button */}
						<div className="card-body">
							<h3>{anime.title.english || anime.title.romaji}</h3>
							<p>{anime.genres && anime.genres.join(', ')}</p>
							<p>
								Ep: {anime.episodes || '?'} | Score: <span className="score">{anime.averageScore || '?'}</span>/100
							</p>

							{/* Show "Add to List" if logged in, otherwise prompt to login */}
							{isLoggedIn ? (
								<button onClick={() => handleAddToList(anime)}>
									Add to List
								</button>
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