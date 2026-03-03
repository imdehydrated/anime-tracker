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
import FilterControlPanel from '../components/FilterControlPanel';

const MAX_SEEDS = 5;
const SMART_REC_STATE_KEY = 'smart_rec_page_state_v2';
const SIMILAR_LIST_WEIGHT_WHEN_ENABLED = 0.25;
const DEFAULT_GLOBAL_FILTERS = {
	includeExtraSeasons: false,
	includeMovies: false,
	includeOnasOvasSpecials: false,
	includeMusic: false,
	includeAdult: false,
	popularityAttenuation: 'medium',
};

/**
 * SmartRec page:
 * - Query-driven semantic search
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
	const [similarUseList, setSimilarUseList] = useState(false);
	const [filters, setFilters] = useState(DEFAULT_GLOBAL_FILTERS);
	const [results, setResults] = useState([]);
	const [searching, setSearching] = useState(false);
	const [searchError, setSearchError] = useState('');
	const [addedIds, setAddedIds] = useState(new Set());
	const [hydrated, setHydrated] = useState(false);

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
		try {
			const cached = sessionStorage.getItem(SMART_REC_STATE_KEY);
			if (!cached) return;
			const parsed = JSON.parse(cached);
			if (parsed.mode) setMode(parsed.mode);
			if (Array.isArray(parsed.seeds)) setSeeds(parsed.seeds);
			if (typeof parsed.context === 'string') setContext(parsed.context);
			if (typeof parsed.similarUseList === 'boolean') setSimilarUseList(parsed.similarUseList);
			if (parsed.filters && typeof parsed.filters === 'object') {
				setFilters((prev) => ({ ...prev, ...parsed.filters }));
			}
			if (Array.isArray(parsed.results)) setResults(parsed.results);
			if (Array.isArray(parsed.addedIds)) setAddedIds(new Set(parsed.addedIds));
			if (typeof parsed.searchError === 'string') setSearchError(parsed.searchError);
		} catch {
			// Ignore corrupted cache and continue with defaults.
		} finally {
			setHydrated(true);
		}
	}, []);

	useEffect(() => {
		if (!hydrated) return;
		sessionStorage.setItem(SMART_REC_STATE_KEY, JSON.stringify({
			mode,
			seeds,
			context,
			similarUseList,
			filters,
			results,
			addedIds: Array.from(addedIds),
			searchError,
		}));
	}, [
		hydrated,
		mode,
		seeds,
		context,
		similarUseList,
		filters,
		results,
		addedIds,
		searchError
	]);

	useEffect(() => {
		if (!isLoggedIn && mode === 'cf') {
			setMode('semantic');
			setResults([]);
		}
	}, [isLoggedIn, mode]);

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
	const canSearch = isCfMode
		? true
		: isSimilarMode
			? seeds.length > 0
			: context.trim().length > 0;

	const handleSearch = async () => {
		if (!canSearch) return;

		setSearching(true);
		setSearchError('');
		clearMessages();

		try {
			const body = { limit: 15, mode };

			if (isSimilarMode) {
				body.seedIds = seeds.map((seed) => seed.id);
				if (isLoggedIn && similarUseList) {
					body.listWeight = SIMILAR_LIST_WEIGHT_WHEN_ENABLED;
				}
			} else if (!isCfMode) {
				body.query = context.trim() || null;
			}
			if (isCfMode) {
				body.useListOnly = true;
			}
			body.filters = filters;

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
							: 'Describe what you\'re looking for and get text-driven recommendations.'}
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

			{isSimilarMode && (
			<div className="smart-rec-section">
				<label className="smart-rec-label">Seed Anime (pick 1-5)</label>

				{seeds.length > 0 && (
					<div className="seed-chips">
						{seeds.map((seed) => (
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
								{suggestions.map((anime) => {
									const coverUrl = typeof anime.coverImage === 'string'
										? anime.coverImage
										: anime.coverImage?.large || anime.coverImage?.medium || '';
									return (
										<button
											key={anime.id}
											className="seed-dropdown-item"
											onClick={() => handleSelectSeed(anime)}
											disabled={seeds.some((seed) => seed.id === anime.id)}
										>
											{coverUrl && (
												<img
													src={coverUrl}
													alt=""
													onError={(e) => {
														e.currentTarget.style.display = 'none';
													}}
												/>
											)}
											<span>{anime.title.english || anime.title.romaji}</span>
										</button>
									);
								})}
							</div>
						)}
					</div>
				)}
			</div>
			)}

			{!isCfMode && !isSimilarMode && (
			<div className="smart-rec-section">
				<label className="smart-rec-label">What are you in the mood for?</label>
				<textarea
					className="smart-rec-context"
					placeholder="e.g. dark psychological thriller with plot twists, or something lighthearted and funny..."
					value={context}
					onChange={(e) => setContext(e.target.value)}
					rows={3}
				/>
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
					<p className="smart-rec-slider-hint">
						When enabled, Similar Shows blends your seed picks with your list profile at a fixed personalization strength.
					</p>
				</div>
			)}

			<FilterControlPanel
				title="Global Recommendation Filters"
				filters={filters}
				setFilters={setFilters}
				showPopularityAttenuation={true}
				showAdultToggle={true}
			/>

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
