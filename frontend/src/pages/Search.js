/**
 * Search Page — Search for anime and add them to your list.
 *
 * Flow:
 * 1. User types a search query and clicks Search
 * 2. GET /api/anime/search?q={query} fetches results from AniList (via our backend)
 * 3. Results display as cards with title, cover image, score, episodes
 * 4. "Add to List" button sends POST /api/users/list with the anime's AniList ID
 *
 * This page combines two API calls:
 * - Public endpoint (search) — no token needed
 * - Protected endpoint (add to list) — requires JWT token
 */
import { useState } from 'react';
import { useAuth } from '../context/AuthContext';
import axios from 'axios';

function Search() {
    // Search input and results
    const [query, setQuery] = useState('');         // What the user types
    const [results, setResults] = useState([]);      // Array of anime from AniList
    const [loading, setLoading] = useState(false);   // Show "Searching..." during API call
    const [error, setError] = useState('');
    const [message, setMessage] = useState('');       // Success message after adding to list

    // Get JWT token — needed for the "Add to List" POST request
    const { token, isLoggedIn } = useAuth();

    /**
	 * handleSearch — Calls our backend search endpoint.
	 *
	 * This hits the PUBLIC /api/anime/search endpoint (no token needed).
	 * The backend forwards the query to AniList's GraphQL API and returns
	 * a simplified JSON array of anime objects.
	 */
	const handleSearch = async (e) => {
		e.preventDefault();          // Prevent form from reloading the page
		setError('');
		setMessage('');
		setLoading(true);

		try {
			// GET /api/anime/search?q=naruto — same endpoint we tested with curl
			const { data } = await axios.get(`/api/anime/search?q=${encodeURIComponent(query)}`);
			setResults(data);          // Store the array of anime results
		} catch (err) {
			setError('Search failed. Try again.');
		} finally {
			setLoading(false);
		}
	};

	/**
	 * handleAddToList — Adds an anime to the user's list.
	 *
	 * Sends POST /api/users/list with the anime's AniList ID.
	 * This is a PROTECTED endpoint — requires the JWT token in the header.
	 *
	 * @param {number} anilistId — the anime's ID from AniList (e.g., 20 for Naruto)
	 */
	const handleAddToList = async (anilistId) => {
		setMessage('');
		setError('');

		try {
			// POST with JWT — same as: curl -H "Authorization: Bearer $TOKEN"
			await axios.post('/api/users/list',
				{ anilistId, status: 'PLAN_TO_WATCH' },   // Default status when adding
				{ headers: { Authorization: `Bearer ${token}` } }
			);
			setMessage('Added to your list!');
		} catch (err) {
			setError(err.response?.data?.error || 'Failed to add to list');
		}
	};

    return (
		<div className="page">
			<h1>Search Anime</h1>

			{/* Search form — onSubmit triggers handleSearch */}
			<form onSubmit={handleSearch}>
				<input
					type="text"
					placeholder="Search anime... (e.g., Naruto)"
					value={query}
					onChange={(e) => setQuery(e.target.value)}
					required
				/>
				<button type="submit" disabled={loading}>
					{/* Show different text while searching */}
					{loading ? 'Searching...' : 'Search'}
				</button>
			</form>

			{/* Feedback messages */}
			{error && <p className="error-message">{error}</p>}
			{message && <p className="success-message">{message}</p>}

			{/* Search results — .map() loops through each anime */}
			<div className="search-results">
				{results.map((anime) => (
					<div key={anime.id} className="anime-card">
						{/* Cover image from AniList */}
						{anime.coverImage && (
							<img src={anime.coverImage.large} alt={anime.title.romaji} />
						)}

						<div className="anime-info">
							{/* Show English title if available, fall back to Romaji */}
							<h3>{anime.title.english || anime.title.romaji}</h3>

							<p>
								{/* Join genres array with commas: ["Action", "Adventure"] → "Action, Adventure" */}
								{anime.genres && anime.genres.join(', ')}
							</p>
							<p>Episodes: {anime.episodes || '?'} | Score: {anime.averageScore || '?'}/100</p>

							{/* Only show Add button if logged in */}
							{isLoggedIn && (
								<button onClick={() => handleAddToList(anime.id)}>
									Add to List
								</button>
							)}
						</div>
					</div>
				))}
			</div>
		</div>
	);
}

export default Search;
