import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
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
	const [listWeight, setListWeight] = useState(0.2);
	const [results, setResults] = useState([]);
	const [searching, setSearching] = useState(false);
	const [searchError, setSearchError] = useState('');
	const [addedIds, setAddedIds] = useState(new Set());

	// Blacklist state
	const [blacklist, setBlacklist] = useState([]);
	const [showBlacklist, setShowBlacklist] = useState(false);
	const [blacklistSearch, setBlacklistSearch] = useState('');

	const { isLoggedIn } = useAuth();
	const authHeader = useAuthHeader();
	const { addToList, message, error, clearMessages, setError } = useAddToList();
	const { query, setQuery, results: suggestions, loading: suggestionsLoading, clearResults } = useDebounceSearch(300, 2);
	const fullListMode = isLoggedIn && listWeight >= 1;

	// Fetch user's list IDs to show "On Your List" badges
	const [userListIds, setUserListIds] = useState(new Set());
	useEffect(() => {
		if (isLoggedIn) {
			axios.get('/api/users/list', authHeader)
				.then(({ data }) => setUserListIds(new Set(data.map(entry => entry.anilistId))))
				.catch(() => { });
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
		if (!fullListMode && seeds.length === 0 && !context.trim()) return;

		setSearching(true);
		setSearchError('');
		clearMessages();

		try {
			const body = {
				seedIds: seeds.map(s => s.id),
				query: context.trim() || null,
				limit: 15,
			};
			if (fullListMode) {
				body.useListOnly = true;
			}
			if (isLoggedIn && listWeight > 0) {
				body.listWeight = listWeight;
			}
			const { data } = await axios.post('/api/users/recommendations/semantic',
				body, isLoggedIn ? authHeader : {});
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

	const handleBlacklist = async (anime) => {
		clearMessages();
		setSearchError('');

		try {
			await axios.post('/api/users/recommendations/blacklist',
				{ anilistId: anime.id, title: anime.title.english || anime.title.romaji, coverImage: anime.coverImage?.large },
				authHeader
			);
			setResults(prev => prev.filter(a => a.id !== anime.id));
		} catch (err) {
			setError('Failed to hide anime.');
		}
	};

	const fetchBlacklist = async () => {
		try {
			const { data } = await axios.get('/api/users/recommendations/blacklist', authHeader);
			setBlacklist(data);
		} catch (err) {
			setError('Failed to load blacklist.');
		}
	};

	const handleRemoveFromBlacklist = async (id) => {
		try {
			await axios.delete(`/api/users/recommendations/blacklist/${id}`, authHeader);
			setBlacklist(prev => prev.filter(item => item.id !== id));
		} catch (err) {
			setError('Failed to remove from blacklist.');
		}
	};

	return (
		<div className="page smart-rec">
			<h1>Smart Recommendations</h1>
			<p className="page-subtitle">Pick anime you love and describe what you're looking for.</p>

			{/* Seed Picker */}
			<div className="smart-rec-section">
				<label className="smart-rec-label">Seed Anime (up to {MAX_SEEDS})</label>

				{seeds.length > 0 && !fullListMode && (
					<div className="seed-chips">
						{seeds.map(seed => (
							<span key={seed.id} className="seed-chip">
								{seed.title.english || seed.title.romaji}
								<button onClick={() => handleRemoveSeed(seed.id)} aria-label="Remove">&times;</button>
							</span>
						))}
					</div>
				)}

				{seeds.length < MAX_SEEDS && !fullListMode && (
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
					placeholder={fullListMode ? "Disabled at 100% list influence — results are based entirely on your list." : "e.g. dark psychological thriller with plot twists, or something lighthearted and funny..."}
					value={fullListMode ? '' : context}
					onChange={(e) => setContext(e.target.value)}
					rows={3}
					disabled={fullListMode}
				/>
			</div>

			{/* List Influence Slider — logged-in only */}
			{isLoggedIn && (
				<div className="smart-rec-section">
					<label className="smart-rec-label">
						List Influence: {Math.round(listWeight * 100)}%
					</label>
					<input
						type="range"
						className="smart-rec-slider"
						min="0"
						max="100"
						value={Math.round(listWeight * 100)}
						onChange={(e) => {
							const val = Number(e.target.value) / 100;
							setListWeight(val);
							if (val >= 1) {
								setSeeds([]);
								setContext('');
								clearResults();
							}
						}}
					/>
					<p className="smart-rec-slider-hint">
						How much your rated anime should shape results. 0% = no influence, 100% = fully guided by your list.
					</p>
				</div>
			)}

			{/* Search Button + Manage Blacklist */}
			<div className="smart-rec-actions">
				<button
					className="btn-primary smart-rec-btn"
					onClick={handleSearch}
					disabled={searching || (!fullListMode && seeds.length === 0 && !context.trim())}
				>
					{searching ? 'Searching...' : 'Find Recommendations'}
				</button>
				{isLoggedIn && (
					<button
						className="refresh-btn"
						onClick={() => {
							setShowBlacklist(!showBlacklist);
							if (!showBlacklist) fetchBlacklist();
						}}
					>
						Manage Blacklist
					</button>
				)}
			</div>

			{/* Messages */}
			{(searchError || error) && <p className="error-message">{searchError || error}</p>}
			{message && <p className="success-message">{message}</p>}

			{/* Results */}
			{results.length > 0 && (
				<div className="smart-rec-results">
					<h2>Results</h2>
					{results.map(anime => (
						<AnimeRecItem key={anime.id} anime={anime}>
							{isLoggedIn && (
								userListIds.has(anime.id) || addedIds.has(anime.id) ? (
									<span className="on-list-badge">On Your List</span>
								) : (
									<>
										<button className="btn-primary" onClick={() => handleAddToList(anime)}>
											Add to List
										</button>
										<button className="blacklist-btn" onClick={() => handleBlacklist(anime)}>
											Not Interested
										</button>
									</>
								)
							)}
						</AnimeRecItem>
					))}
				</div>
			)}

			{/* Blacklist Modal */}
			{showBlacklist && (
				<div className="modal-overlay" onClick={() => { setShowBlacklist(false); setBlacklistSearch(''); }}>
					<div className="modal" onClick={(e) => e.stopPropagation()}>
						<div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
							<h2>Hidden Anime</h2>
							<button className="btn-danger" onClick={() => { setShowBlacklist(false); setBlacklistSearch(''); }}>Close</button>
						</div>
						{blacklist.length === 0 ? (
							<p>No hidden anime.</p>
						) : (
							<>
								<input
									type="text"
									className="blacklist-search"
									placeholder="Search hidden anime..."
									value={blacklistSearch}
									onChange={(e) => setBlacklistSearch(e.target.value)}
								/>
								<div className="blacklist-cards">
									{blacklist
										.filter(item => (item.title || '').toLowerCase().includes(blacklistSearch.toLowerCase()))
										.map(item => (
											<div key={item.id} className="blacklist-card">
												{item.coverImage && (
													<img src={item.coverImage} alt={item.title} />
												)}
												<div className="blacklist-card-info">
													<h3><Link to={`/anime/${item.anilistId}`}>{item.title || `AniList #${item.anilistId}`}</Link></h3>
													<button className="btn-danger" onClick={() => handleRemoveFromBlacklist(item.id)}>
														Remove
													</button>
												</div>
											</div>
										))}
								</div>
							</>
						)}
					</div>
				</div>
			)}
		</div>
	);
}

export default SmartRec;
