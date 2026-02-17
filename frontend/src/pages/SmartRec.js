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
const MODES = [
	{ key: 'semantic', label: 'Smart Search' },
	{ key: 'similar', label: 'Similar Shows' },
	{ key: 'cf', label: 'For You', requiresLogin: true },
];

function SmartRec() {
	const [mode, setMode] = useState('semantic');
	const [seeds, setSeeds] = useState([]);
	const [context, setContext] = useState('');
	const [listWeight, setListWeight] = useState(0.2);
	const [similarUseList, setSimilarUseList] = useState(false);
	const [similarListWeight, setSimilarListWeight] = useState(0.25);
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

	const isCfMode = mode === 'cf';
	const isSimilarMode = mode === 'similar';
	const fullListMode = isLoggedIn && !isCfMode && !isSimilarMode && listWeight >= 1;
	const canSearch = isCfMode
		? true
		: isSimilarMode
			? seeds.length > 0
			: (fullListMode || seeds.length > 0 || context.trim());

	const handleSearch = async () => {
		if (!canSearch) return;

		setSearching(true);
		setSearchError('');
		clearMessages();

		try {
			const body = { limit: 15, mode };

			if (isSimilarMode) {
				body.seedIds = seeds.map((seed) => seed.id);
				if (isLoggedIn && similarUseList && similarListWeight > 0) {
					body.listWeight = similarListWeight;
				}
			} else if (!isCfMode) {
				body.seedIds = seeds.map((seed) => seed.id);
				body.query = context.trim() || null;
			}
			if (fullListMode || isCfMode) {
				body.useListOnly = true;
			}
			if (isLoggedIn && listWeight > 0 && !isCfMode && !isSimilarMode) {
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
			<p className="page-subtitle">
				{isCfMode
					? 'Get predictions based on your rating patterns.'
					: isSimilarMode
							? 'Pick anime you love and find similar shows.'
							: 'Pick anime you love and describe what you\'re looking for.'}
			</p>

			<div className="smart-rec-mode-tabs">
				{MODES.map(({ key, label, requiresLogin }) => {
					if (requiresLogin && !isLoggedIn) return null;
					return (
						<button
							key={key}
							className={`smart-rec-mode-tab${mode === key ? ' active' : ''}`}
							onClick={() => {
								setMode(key);
								setResults([]);
								setSearchError('');
							}}
						>
							{label}
						</button>
					);
				})}
			</div>

			{!isCfMode && (
			<>
			<div className="smart-rec-section">
				<label className="smart-rec-label">
					{isSimilarMode ? 'Seed Anime (pick 1-5)' : `Seed Anime (up to ${MAX_SEEDS})`}
				</label>

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

			{!isSimilarMode && (
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
			)}
			</>
			)}

			{isLoggedIn && !isCfMode && !isSimilarMode && (
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

			{isLoggedIn && isSimilarMode && (
				<div className="smart-rec-section">
					<label className="smart-rec-label">
						<input
							type="checkbox"
							checked={similarUseList}
							onChange={(e) => setSimilarUseList(e.target.checked)}
						/>{' '}
						Use shows on my list to personalize
					</label>
					{similarUseList && (
						<>
							<label className="smart-rec-label">
								List Influence: {Math.round(similarListWeight * 100)}%
							</label>
							<input
								type="range"
								className="smart-rec-slider"
								min="0"
								max="100"
								value={Math.round(similarListWeight * 100)}
								onChange={(e) => setSimilarListWeight(Number(e.target.value) / 100)}
							/>
							<p className="smart-rec-slider-hint">
								Blend seed similarity with your personal taste profile.
							</p>
						</>
					)}
				</div>
			)}

			<div className="smart-rec-actions">
				<button
					className="btn-primary smart-rec-btn"
					onClick={handleSearch}
					disabled={searching || !canSearch}
				>
					{searching ? 'Searching...' : isCfMode ? 'Get Predictions' : isSimilarMode ? 'Find Similar' : 'Find Recommendations'}
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
