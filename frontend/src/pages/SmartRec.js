import { useState, useEffect } from 'react';
import AnimeRecItem from '../components/AnimeRecItem';
import BlacklistModal from '../components/BlacklistModal';
import { useAuth } from '../context/AuthContext';
import { getApiError } from '../api/client';
import { getUserList } from '../api/listApi';
import { getSemanticRecommendations } from '../api/recommendationsApi';
import { useAddToList } from '../hooks/useAddToList';
import { useDebounceSearch } from '../hooks/useDebounceSearch';
import { useRecommendationBlacklist } from '../hooks/useRecommendationBlacklist';

const MAX_SEEDS = 5;

/**
 * SmartRec page:
 * - Seed + text-query semantic search
 * - Optional list influence blending
 * - Shared blacklist modal/actions
 */
function SmartRec() {
	const [seeds, setSeeds] = useState([]);
	const [context, setContext] = useState('');
	const [listWeight, setListWeight] = useState(0.2);
	const [results, setResults] = useState([]);
	const [searching, setSearching] = useState(false);
	const [searchError, setSearchError] = useState('');
	const [addedIds, setAddedIds] = useState(new Set());

	const { isLoggedIn } = useAuth();
	const { addToList, message, error, clearMessages, setError } = useAddToList();
	const {
		query,
		setQuery,
		results: suggestions,
		loading: suggestionsLoading,
		clearResults,
	} = useDebounceSearch(300, 2);

	const fullListMode = isLoggedIn && listWeight >= 1;

	const [userListIds, setUserListIds] = useState(new Set());
	const blacklist = useRecommendationBlacklist(
		setError,
		(animeId) => setResults((prev) => prev.filter((item) => item.id !== animeId))
	);

	useEffect(() => {
		if (isLoggedIn) {
			getUserList()
				.then((data) => setUserListIds(new Set(data.map((entry) => entry.anilistId))))
				.catch(() => { });
		}
	}, [isLoggedIn]);

	const handleSelectSeed = (anime) => {
		if (seeds.length >= MAX_SEEDS) return;
		if (seeds.some((seed) => seed.id === anime.id)) return;
		setSeeds((prev) => [...prev, anime]);
		clearResults();
	};

	const handleRemoveSeed = (id) => {
		setSeeds((prev) => prev.filter((seed) => seed.id !== id));
	};

	const handleSearch = async () => {
		if (!fullListMode && seeds.length === 0 && !context.trim()) return;

		setSearching(true);
		setSearchError('');
		clearMessages();

		try {
			const body = {
				seedIds: seeds.map((seed) => seed.id),
				query: context.trim() || null,
				limit: 15,
			};
			if (fullListMode) {
				body.useListOnly = true;
			}
			if (isLoggedIn && listWeight > 0) {
				body.listWeight = listWeight;
			}

			const data = await getSemanticRecommendations(body);
			setResults(data);
		} catch (err) {
			setSearchError(getApiError(err, 'Search failed. Try again.'));
		} finally {
			setSearching(false);
		}
	};

	const handleAddToList = async (anime) => {
		const success = await addToList(anime);
		if (success) {
			setAddedIds((prev) => new Set([...prev, anime.id]));
		}
	};

	return (
		<div className="page smart-rec">
			<h1>Smart Recommendations</h1>
			<p className="page-subtitle">Pick anime you love and describe what you're looking for.</p>

			<div className="smart-rec-section">
				<label className="smart-rec-label">Seed Anime (up to {MAX_SEEDS})</label>

				{seeds.length > 0 && !fullListMode && (
					<div className="seed-chips">
						{seeds.map((seed) => (
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
								{suggestions.map((anime) => (
									<button
										key={anime.id}
										className="seed-dropdown-item"
										onClick={() => handleSelectSeed(anime)}
										disabled={seeds.some((seed) => seed.id === anime.id)}
									>
										{anime.coverImage && <img src={anime.coverImage.large} alt="" />}
										<span>{anime.title.english || anime.title.romaji}</span>
									</button>
								))}
							</div>
						)}
					</div>
				)}
			</div>

			<div className="smart-rec-section">
				<label className="smart-rec-label">What are you in the mood for? (optional)</label>
				<textarea
					className="smart-rec-context"
					placeholder={fullListMode
						? "Disabled at 100% list influence - results are based entirely on your list."
						: "e.g. dark psychological thriller with plot twists, or something lighthearted and funny..."}
					value={fullListMode ? '' : context}
					onChange={(e) => setContext(e.target.value)}
					rows={3}
					disabled={fullListMode}
				/>
			</div>

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
							const value = Number(e.target.value) / 100;
							setListWeight(value);
							if (value >= 1) {
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

			<div className="smart-rec-actions">
				<button
					className="btn-primary smart-rec-btn"
					onClick={handleSearch}
					disabled={searching || (!fullListMode && seeds.length === 0 && !context.trim())}
				>
					{searching ? 'Searching...' : 'Find Recommendations'}
				</button>
				{isLoggedIn && (
					<button className="refresh-btn" onClick={blacklist.openBlacklist}>
						Manage Blacklist
					</button>
				)}
			</div>

			{(searchError || error) && <p className="error-message">{searchError || error}</p>}
			{message && <p className="success-message">{message}</p>}

			{results.length > 0 && (
				<div className="smart-rec-results">
					<h2>Results</h2>
					{results.map((anime) => (
						<AnimeRecItem key={anime.id} anime={anime}>
							{isLoggedIn && (
								userListIds.has(anime.id) || addedIds.has(anime.id) ? (
									<span className="on-list-badge">On Your List</span>
								) : (
									<>
										<button className="btn-primary" onClick={() => handleAddToList(anime)}>
											Add to List
										</button>
										<button className="blacklist-btn" onClick={() => blacklist.handleBlacklist(anime)}>
											Not Interested
										</button>
									</>
								)
							)}
						</AnimeRecItem>
					))}
				</div>
			)}

			<BlacklistModal
				show={blacklist.showBlacklist}
				blacklist={blacklist.blacklist}
				search={blacklist.blacklistSearch}
				onSearchChange={blacklist.setBlacklistSearch}
				onClose={blacklist.closeBlacklist}
				onRemove={blacklist.handleRemoveFromBlacklist}
			/>
		</div>
	);
}

export default SmartRec;
