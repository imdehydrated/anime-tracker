import { useState, useEffect, useRef, useCallback } from 'react';
import AnimeRecItem from '../components/AnimeRecItem';
import FeedbackModal from '../components/FeedbackModal';
import { useAuth } from '../context/AuthContext';
import { getApiError } from '../api/client';
import { getUserList } from '../api/listApi';
import { getSemanticRecommendationsPaged } from '../api/recommendationsApi';
import { useAddToList } from '../hooks/useAddToList';
import { useDebounceSearch } from '../hooks/useDebounceSearch';
import { useRecommendationFeedback } from '../hooks/useRecommendationFeedback';
import {
	useRecommendationFilters,
	RECOMMENDATION_FILTER_DEFAULTS,
} from '../hooks/useRecommendationFilters';
import FilterControlPanel from '../components/FilterControlPanel';
import ThumbUpIcon from '../assets/thumb-up.svg?react';
import ThumbDownIcon from '../assets/thumb-down.svg?react';
import { useLocation } from 'react-router-dom';

const MAX_SEEDS = 5;
const PAGE_SIZE = 15;
const MAX_RESULTS = 100;
const SMART_REC_STATE_KEY_BASE = 'smart_rec_page_state_v6';
const SIMILAR_LIST_WEIGHT_WHEN_ENABLED = 0.25;
const SEED_SEARCH_FILTERS = {
	includeExtraSeasons: true,
	includeMovies: true,
	includeOnasOvasSpecials: true,
	includeMusic: true,
	includeAdult: false,
};

/**
 * SmartRec page:
 * - Query-driven semantic search
 * - Optional list influence blending
 * - Shared feedback modal/actions
 */
const MODES = [
	{ key: 'semantic', label: 'Smart Search' },
	{ key: 'similar', label: 'Similar Shows' },
	{ key: 'cf', label: 'For You', requiresLogin: true },
];

function buildSmartRecStateKey(username) {
	const normalized = typeof username === 'string' && username.trim().length > 0
		? username.trim().toLowerCase()
		: 'anon';
	return `${SMART_REC_STATE_KEY_BASE}:${normalized}`;
}

function SmartRec() {
	const location = useLocation();
	const [mode, setMode] = useState('semantic');
	const [seeds, setSeeds] = useState([]);
	const [context, setContext] = useState('');
	const [semanticUseList, setSemanticUseList] = useState(true);
	const [similarUseList, setSimilarUseList] = useState(false);
	const { filters, setFilters, hydrateFilters, refreshNonce } = useRecommendationFilters(RECOMMENDATION_FILTER_DEFAULTS);
	const [results, setResults] = useState([]);
	const [hasRequested, setHasRequested] = useState(false);
	const [searching, setSearching] = useState(false);
	const [loadingMore, setLoadingMore] = useState(false);
	const [searchError, setSearchError] = useState('');
	const [nextCursor, setNextCursor] = useState(null);
	const [hasMore, setHasMore] = useState(false);
	const [addedIds, setAddedIds] = useState(new Set());
	const [hydrated, setHydrated] = useState(false);
	const [hydratedStorageKey, setHydratedStorageKey] = useState(null);
	const requestSeqRef = useRef(0);
	const activeRequestSeqRef = useRef(0);

	const { isLoggedIn, username } = useAuth();
	const storageKey = buildSmartRecStateKey(username);
	const { addToList, message, error, clearMessages, setError } = useAddToList();
	const {
		query,
		setQuery,
		results: suggestions,
		loading: suggestionsLoading,
		clearResults,
	} = useDebounceSearch(220, 2, 12, SEED_SEARCH_FILTERS);

	const [userListIds, setUserListIds] = useState(new Set());
	const feedback = useRecommendationFeedback(setError, isLoggedIn);

	const issueRequestSeq = useCallback(() => {
		const nextSeq = requestSeqRef.current + 1;
		requestSeqRef.current = nextSeq;
		activeRequestSeqRef.current = nextSeq;
		return nextSeq;
	}, []);

	const invalidatePendingRequests = useCallback(() => {
		const nextSeq = requestSeqRef.current + 1;
		requestSeqRef.current = nextSeq;
		activeRequestSeqRef.current = nextSeq;
	}, []);

	useEffect(() => {
		setHydrated(false);
		setHydratedStorageKey(null);
		invalidatePendingRequests();
		setMode('semantic');
		setSeeds([]);
		setContext('');
		setSemanticUseList(true);
		setSimilarUseList(false);
		hydrateFilters(RECOMMENDATION_FILTER_DEFAULTS);
		setResults([]);
		setHasRequested(false);
		setSearching(false);
		setLoadingMore(false);
		setSearchError('');
		setNextCursor(null);
		setHasMore(false);
		setAddedIds(new Set());

		try {
			const cached = sessionStorage.getItem(storageKey);
			if (!cached) return;
			const parsed = JSON.parse(cached);
			if (parsed.mode) setMode(parsed.mode);
			if (Array.isArray(parsed.seeds)) setSeeds(parsed.seeds);
			if (typeof parsed.context === 'string') setContext(parsed.context);
			if (typeof parsed.semanticUseList === 'boolean') setSemanticUseList(parsed.semanticUseList);
			if (typeof parsed.similarUseList === 'boolean') setSimilarUseList(parsed.similarUseList);
			if (parsed.filters && typeof parsed.filters === 'object') {
				hydrateFilters(parsed.filters);
			}
			if (Array.isArray(parsed.results)) setResults(parsed.results);
			if (Array.isArray(parsed.addedIds)) setAddedIds(new Set(parsed.addedIds));
			if (typeof parsed.searchError === 'string') setSearchError(parsed.searchError);
			if (typeof parsed.nextCursor === 'string') setNextCursor(parsed.nextCursor);
			if (typeof parsed.hasMore === 'boolean') setHasMore(parsed.hasMore);
		} catch {
			// Ignore corrupted cache and continue with defaults.
		} finally {
			setHydrated(true);
			setHydratedStorageKey(storageKey);
		}
	}, [hydrateFilters, invalidatePendingRequests, storageKey]);

	useEffect(() => {
		const prefillMode = location.state?.prefillMode;
		const prefillContext = location.state?.prefillContext;
		let modeChanged = false;
		if (typeof prefillMode === 'string' && ['semantic', 'similar', 'cf'].includes(prefillMode)) {
			if (!(prefillMode === 'cf' && !isLoggedIn)) {
				setMode(prefillMode);
				modeChanged = true;
			}
		}
		if (typeof prefillContext === 'string' && prefillContext.trim().length > 0) {
			setContext(prefillContext.trim());
			modeChanged = true;
		}
			if (modeChanged) {
				invalidatePendingRequests();
				setResults([]);
				setHasRequested(false);
				setSearching(false);
				setLoadingMore(false);
				setSearchError('');
				setNextCursor(null);
				setHasMore(false);
			}
		}, [location.state, isLoggedIn, invalidatePendingRequests]);

	useEffect(() => {
		if (!hydrated || hydratedStorageKey !== storageKey) return;
		sessionStorage.setItem(storageKey, JSON.stringify({
			mode,
			seeds,
			context,
			semanticUseList,
			similarUseList,
			filters,
			results,
			nextCursor,
			hasMore,
			addedIds: Array.from(addedIds),
			searchError,
		}));
	}, [
		hydrated,
		mode,
		seeds,
		context,
		semanticUseList,
		similarUseList,
		filters,
		results,
		nextCursor,
		hasMore,
		addedIds,
		searchError,
		hydratedStorageKey,
		storageKey,
	]);

	useEffect(() => {
		if (!isLoggedIn && mode === 'cf') {
			invalidatePendingRequests();
			setMode('semantic');
			setResults([]);
			setSearching(false);
			setLoadingMore(false);
			setNextCursor(null);
			setHasMore(false);
		}
	}, [isLoggedIn, mode, invalidatePendingRequests]);

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
	const readinessHint = isCfMode
		? ''
		: isSimilarMode
			? 'Add at least 1 seed anime to run Similar Shows.'
			: 'Describe what you want to run Smart Search.';

	const buildSearchBody = (cursorValue = null) => {
		const body = { mode, pageSize: PAGE_SIZE, cursor: cursorValue, limit: MAX_RESULTS };
		if (isSimilarMode) {
			body.seedIds = seeds.map((seed) => seed.id);
			if (isLoggedIn && similarUseList) {
				body.listWeight = SIMILAR_LIST_WEIGHT_WHEN_ENABLED;
			}
		} else if (!isCfMode) {
			body.query = context.trim() || null;
			if (isLoggedIn && !semanticUseList) {
				body.listWeight = 0.0;
			}
		}
		if (isCfMode) {
			body.useListOnly = true;
		}
		body.filters = filters;
		return body;
	};

	const handleSearch = async () => {
		if (!canSearch) return;
		const requestSeq = issueRequestSeq();

		setHasRequested(true);
		setSearching(true);
		setLoadingMore(false);
		setSearchError('');
		clearMessages();
		setNextCursor(null);
		setHasMore(false);

		try {
			const page = await getSemanticRecommendationsPaged(buildSearchBody(null));
			if (activeRequestSeqRef.current !== requestSeq) return;
			setResults(Array.isArray(page.items) ? page.items : []);
			setNextCursor(page.nextCursor || null);
			setHasMore(Boolean(page.hasMore));
		} catch (err) {
			if (activeRequestSeqRef.current !== requestSeq) return;
			setSearchError(getApiError(err, 'Search failed. Try again.'));
		} finally {
			if (activeRequestSeqRef.current !== requestSeq) return;
			setSearching(false);
		}
	};

	const handleLoadMore = async () => {
		if (!canSearch || !nextCursor || loadingMore || results.length >= MAX_RESULTS) return;
		const requestSeq = issueRequestSeq();

		setLoadingMore(true);
		setSearchError('');
		try {
			const page = await getSemanticRecommendationsPaged(buildSearchBody(nextCursor));
			if (activeRequestSeqRef.current !== requestSeq) return;
			const newItems = Array.isArray(page.items) ? page.items : [];
			setResults((prev) => {
				const byId = new Map(prev.map((item) => [item.id, item]));
				newItems.forEach((item) => byId.set(item.id, item));
				return Array.from(byId.values());
			});
			setNextCursor(page.nextCursor || null);
			setHasMore(Boolean(page.hasMore));
		} catch (err) {
			if (activeRequestSeqRef.current !== requestSeq) return;
			setSearchError(getApiError(err, 'Failed to load more results.'));
		} finally {
			if (activeRequestSeqRef.current !== requestSeq) return;
			setLoadingMore(false);
		}
	};

	useEffect(() => {
		if (!hydrated) return;
		if (refreshNonce === 0) return;
		if (!canSearch) {
			invalidatePendingRequests();
			setResults([]);
			setSearching(false);
			setLoadingMore(false);
			return;
		}
		handleSearch();
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [refreshNonce]);

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
									invalidatePendingRequests();
									setMode(key);
									setResults([]);
									setHasRequested(false);
									setSearching(false);
									setLoadingMore(false);
									setNextCursor(null);
									setHasMore(false);
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
						{suggestionsLoading && suggestions.length === 0 && (
							<div className="seed-dropdown-loading">Searching...</div>
						)}
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

			<FilterControlPanel
				title="Global Recommendation Filters"
				filters={filters}
				setFilters={setFilters}
				showPopularityAttenuation={true}
				showAdultToggle={true}
				showPersonalizationToggle={Boolean(isLoggedIn && !isCfMode)}
				personalizationEnabled={isSimilarMode ? similarUseList : semanticUseList}
				onPersonalizationChange={isSimilarMode ? setSimilarUseList : setSemanticUseList}
				personalizationLabel="Use List Personalization"
				personalizationHelp={
					isSimilarMode
						? 'When off, Similar Shows is seed-only and does not blend your list taste profile.'
						: 'When off, Smart Search is query-only and does not apply your list-based personalization.'
				}
			/>

			<div className="smart-rec-actions">
				<button
					className="btn-primary smart-rec-btn"
					onClick={handleSearch}
					disabled={searching || loadingMore || !canSearch}
				>
					{searching ? 'Searching...' : isCfMode ? 'Get Predictions' : isSimilarMode ? 'Find Similar' : 'Find Recommendations'}
				</button>
				{isLoggedIn && (
					<button className="refresh-btn" onClick={feedback.openFeedback}>
						Manage Feedback
					</button>
				)}
			</div>
			{!canSearch && (
				<p className="empty-state smart-rec-helper">{readinessHint}</p>
			)}

			{(searchError || error) && <p className="error-message">{searchError || error}</p>}
			{message && <p className="success-message">{message}</p>}
			{hasRequested && canSearch && !searchError && !searching && results.length === 0 && (
				<p className="empty-state smart-rec-helper">No recommendations found. Try relaxing filters or changing your query/seeds.</p>
			)}

			{results.length > 0 && (
				<div className="smart-rec-results">
					<h2>
						Results
						<span className="result-count-chip">{results.length}/{MAX_RESULTS}</span>
					</h2>
					{results.map((anime) => (
						<AnimeRecItem key={anime.id} anime={anime}>
							{isLoggedIn ? (
								<>
									{userListIds.has(anime.id) || addedIds.has(anime.id) ? (
										<span className="on-list-badge">On Your List</span>
									) : (
										<button className="btn-primary" onClick={() => handleAddToList(anime)}>
											Add to List
										</button>
									)}
									<button
										className={`feedback-btn feedback-btn-up${feedback.getFeedbackSignal(anime.id) === 'THUMBS_UP' ? ' is-active-up' : ''}`}
										onClick={() => feedback.handleThumbsUp(anime, mode, context.trim())}
									>
										<ThumbUpIcon className="feedback-btn-icon" aria-hidden="true" />
										<span>Thumbs Up</span>
									</button>
									<button
										className={`feedback-btn feedback-btn-down${feedback.getFeedbackSignal(anime.id) === 'THUMBS_DOWN' ? ' is-active-down' : ''}`}
										onClick={() => feedback.handleThumbsDown(anime, mode, context.trim())}
									>
										<ThumbDownIcon className="feedback-btn-icon" aria-hidden="true" />
										<span>Thumbs Down</span>
									</button>
								</>
							) : (
								<span className="feedback-login-hint">Login or Register to give feedback on recommendations</span>
							)}
						</AnimeRecItem>
					))}
					{hasMore && (
						<div className="smart-rec-actions">
							<button
								className="refresh-btn"
								onClick={handleLoadMore}
								disabled={loadingMore || searching || results.length >= MAX_RESULTS}
							>
								{loadingMore ? 'Loading...' : 'Load More'}
							</button>
						</div>
					)}
					{results.length >= MAX_RESULTS && (
						<p className="empty-state smart-rec-helper">Reached max of {MAX_RESULTS} results for this request.</p>
					)}
				</div>
			)}

			<FeedbackModal
				show={feedback.showFeedbackModal}
				feedbackEntries={feedback.feedbackItems}
				search={feedback.feedbackSearch}
				onSearchChange={feedback.setFeedbackSearch}
				onClose={feedback.closeFeedback}
				onRemove={feedback.handleRemoveFeedback}
				title="Recommendation Feedback"
				emptyText="No feedback entries yet."
				searchPlaceholder="Search feedback..."
			/>
		</div>
	);
}

export default SmartRec;
