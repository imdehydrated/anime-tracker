import { useState, useEffect } from 'react';
import { useAuth } from '../context/AuthContext';
import { useAuthHeader } from '../hooks/useAuthHeader';
import { useAddToList } from '../hooks/useAddToList';
import { useDebounceSearch } from '../hooks/useDebounceSearch';
import AnimeRecItem from '../components/AnimeRecItem';
import axios from 'axios';

const MAX_SEEDS = 5;

function SmartRec() {
	const [seeds, setSeeds] = useState([]);
	const [context, setContext] = useState('');
	const [results, setResults] = useState([]);
	const [searching, setSearching] = useState(false);
	const [searchError, setSearchError] = useState('');
	const [addedIds, setAddedIds] = useState(new Set());

	const { isLoggedIn } = useAuth();
	const authHeader = useAuthHeader();
	const { addToList, message, error, clearMessages } = useAddToList();
	const { query, setQuery, results: suggestions, loading: suggestionsLoading, clearResults } = useDebounceSearch(300, 2);

	// Fetch user's list IDs to show "On Your List" badges
	const [userListIds, setUserListIds] = useState(new Set());
	useEffect(() => {
		if (isLoggedIn) {
			axios.get('/api/users/list', authHeader)
				.then(({ data }) => setUserListIds(new Set(data.map(entry => entry.anilistId))))
				.catch(() => {});
		}
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [isLoggedIn]);

	const handleSelectSeed = (anime) => {
		if (seeds.length >= MAX_SEEDS) return;
		if (seeds.some(s => s.id === anime.id)) return;
		setSeeds(prev => [...prev, anime]);
		clearResults();
	};

	const handleRemoveSeed = (id) => {
		setSeeds(prev => prev.filter(s => s.id !== id));
	};

	const handleSearch = async () => {
		if (seeds.length === 0 && !context.trim()) return;

		setSearching(true);
		setSearchError('');
		clearMessages();

		try {
			const { data } = await axios.post('/api/users/recommendations/semantic', {
				seedIds: seeds.map(s => s.id),
				query: context.trim() || null,
				limit: 15,
			}, authHeader);
			setResults(data);
		} catch (err) {
			setSearchError(err.response?.data?.error || 'Search failed. Try again.');
		} finally {
			setSearching(false);
		}
	};

	const handleAddToList = async (anime) => {
		const success = await addToList(anime);
		if (success) {
			setAddedIds(prev => new Set([...prev, anime.id]));
		}
	};

	if (!isLoggedIn) {
		return (
			<div className="page">
				<h1>Smart Search</h1>
				<div className="empty-state">
					<p><a href="/login">Login</a> to use AI-powered recommendations.</p>
				</div>
			</div>
		);
	}

	return (
		<div className="page smart-rec">
			<h1>Smart Recommendations</h1>
			<p className="page-subtitle">Pick anime you love and describe what you're looking for.</p>

			{/* Seed Picker */}
			<div className="smart-rec-section">
				<label className="smart-rec-label">Seed Anime (up to {MAX_SEEDS})</label>

				{seeds.length > 0 && (
					<div className="seed-chips">
						{seeds.map(seed => (
							<span key={seed.id} className="seed-chip">
								{seed.title.english || seed.title.romaji}
								<button onClick={() => handleRemoveSeed(seed.id)} aria-label="Remove">&times;</button>
							</span>
						))}
					</div>
				)}

				{seeds.length < MAX_SEEDS && (
					<div className="seed-picker">
						<input
							type="text"
							placeholder="Search to add a seed anime..."
							value={query}
							onChange={(e) => setQuery(e.target.value)}
						/>
						{suggestionsLoading && <div className="seed-dropdown-loading">Searching...</div>}
						{suggestions.length > 0 && (
							<div className="seed-dropdown">
								{suggestions.map(anime => (
									<button
										key={anime.id}
										className="seed-dropdown-item"
										onClick={() => handleSelectSeed(anime)}
										disabled={seeds.some(s => s.id === anime.id)}
									>
										{anime.coverImage && (
											<img src={anime.coverImage.large} alt="" />
										)}
										<span>{anime.title.english || anime.title.romaji}</span>
									</button>
								))}
							</div>
						)}
					</div>
				)}
			</div>

			{/* Context Input */}
			<div className="smart-rec-section">
				<label className="smart-rec-label">What are you in the mood for? (optional)</label>
				<textarea
					className="smart-rec-context"
					placeholder="e.g. dark psychological thriller with plot twists, or something lighthearted and funny..."
					value={context}
					onChange={(e) => setContext(e.target.value)}
					rows={3}
				/>
			</div>

			{/* Search Button */}
			<button
				className="btn-primary smart-rec-btn"
				onClick={handleSearch}
				disabled={searching || (seeds.length === 0 && !context.trim())}
			>
				{searching ? 'Searching...' : 'Find Recommendations'}
			</button>

			{/* Messages */}
			{(searchError || error) && <p className="error-message">{searchError || error}</p>}
			{message && <p className="success-message">{message}</p>}

			{/* Results */}
			{results.length > 0 && (
				<div className="smart-rec-results">
					<h2>Results</h2>
					{results.map(anime => (
						<AnimeRecItem key={anime.id} anime={anime}>
							{userListIds.has(anime.id) || addedIds.has(anime.id) ? (
								<span className="on-list-badge">On Your List</span>
							) : (
								<button className="btn-primary" onClick={() => handleAddToList(anime)}>
									Add to List
								</button>
							)}
						</AnimeRecItem>
					))}
				</div>
			)}
		</div>
	);
}

export default SmartRec;