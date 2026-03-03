import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import FeedbackModal from '../components/FeedbackModal';
import AnimeRecItem from '../components/AnimeRecItem';
import { useAuth } from '../context/AuthContext';
import { getApiError } from '../api/client';
import { getSemanticRecommendations } from '../api/recommendationsApi';
import { useAddToList } from '../hooks/useAddToList';
import { useRecommendationFeedback } from '../hooks/useRecommendationFeedback';
import FilterControlPanel from '../components/FilterControlPanel';
import { ReactComponent as ThumbUpIcon } from '../assets/thumb-up.svg';
import { ReactComponent as ThumbDownIcon } from '../assets/thumb-down.svg';

const RECOMMENDATIONS_STATE_KEY = 'recommendations_page_state_v1';
const DEFAULT_GLOBAL_FILTERS = {
	includeExtraSeasons: false,
	includeMovies: false,
	includeOnasOvasSpecials: false,
	includeMusic: false,
	includeAdult: false,
	popularityAttenuation: 'medium',
};

/**
 * "For You" recommendations page:
 * - List-only semantic recommendations for logged-in users
 * - Add-to-list actions and feedback management
 */
function Recommendations() {
	const [recommendations, setRecommendations] = useState([]);
	const [loading, setLoading] = useState(false);
	const [fetchError, setFetchError] = useState('');
	const [addedIds, setAddedIds] = useState(new Set());
	const [filters, setFilters] = useState(DEFAULT_GLOBAL_FILTERS);
	const [hydrated, setHydrated] = useState(false);

	const { isLoggedIn } = useAuth();
	const { addToList, message, error, clearMessages, setError } = useAddToList();
	const feedback = useRecommendationFeedback(setError, isLoggedIn);

	const fetchRecommendations = async () => {
		setLoading(true);
		setFetchError('');
		clearMessages();

		try {
			const data = await getSemanticRecommendations({ useListOnly: true, limit: 15, filters });
			setRecommendations(data);
		} catch (err) {
			setFetchError(getApiError(err, 'Failed to load recommendations.'));
		} finally {
			setLoading(false);
		}
	};

	useEffect(() => {
		if (!isLoggedIn) {
			setHydrated(true);
			return;
		}
		try {
			const cached = sessionStorage.getItem(RECOMMENDATIONS_STATE_KEY);
			if (!cached) return;
			const parsed = JSON.parse(cached);
			if (Array.isArray(parsed.recommendations)) {
				setRecommendations(parsed.recommendations);
			}
			if (Array.isArray(parsed.addedIds)) {
				setAddedIds(new Set(parsed.addedIds));
			}
			if (parsed.filters && typeof parsed.filters === 'object') {
				setFilters((prev) => ({ ...prev, ...parsed.filters }));
			}
		} catch {
			// Ignore corrupted cache and continue with fresh fetch.
		} finally {
			setHydrated(true);
		}
	}, [isLoggedIn]);

	useEffect(() => {
		if (!isLoggedIn || !hydrated) return;
		sessionStorage.setItem(RECOMMENDATIONS_STATE_KEY, JSON.stringify({
			recommendations,
			addedIds: Array.from(addedIds),
			filters,
		}));
	}, [isLoggedIn, hydrated, recommendations, addedIds, filters]);

	useEffect(() => {
		if (!isLoggedIn || !hydrated) return;
		if (recommendations.length > 0) return;
		fetchRecommendations();
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [isLoggedIn, hydrated, recommendations.length]);

	const handleAddToList = async (anime) => {
		const success = await addToList(anime);
		if (success) {
			setAddedIds((prev) => new Set([...prev, anime.id]));
		}
	};

	if (loading) return <div className="loading">Loading recommendations...</div>;
	if (!isLoggedIn) {
		return (
			<div className="page">
				<h1>Recommended For You</h1>
				<p className="empty-state">
					<Link to="/login">Login</Link> to get personalized recommendations from your list.
				</p>
			</div>
		);
	}

	return (
		<div className="page">
			<h1>Recommended For You</h1>

			<div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
				<button onClick={fetchRecommendations} className="refresh-btn">
					Refresh
				</button>
				<button className="refresh-btn" onClick={feedback.openFeedback}>
					Manage Feedback
				</button>
			</div>

			<FilterControlPanel
				title="Global Recommendation Filters"
				filters={filters}
				setFilters={setFilters}
				showPopularityAttenuation={true}
				showAdultToggle={true}
			/>

			{(fetchError || error) && <p className="error-message">{fetchError || error}</p>}
			{message && <p className="success-message">{message}</p>}

			{recommendations.length === 0 && !fetchError ? (
				<div className="empty-state">
					<p>No recommendations yet.</p>
					<p>Add some anime to <Link to="/mylist">your list</Link> and rate them to get personalized suggestions!</p>
				</div>
			) : (
				<div className="smart-rec-results">
					{recommendations.map((anime) => (
						<AnimeRecItem key={anime.id} anime={anime}>
							{addedIds.has(anime.id) ? (
								<span className="on-list-badge">On Your List</span>
							) : (
								<>
									<button className="btn-primary" onClick={() => handleAddToList(anime)}>
										Add to List
									</button>
									<button
										className={`feedback-btn feedback-btn-up${feedback.getFeedbackSignal(anime.id) === 'THUMBS_UP' ? ' is-active-up' : ''}`}
										onClick={() => feedback.handleThumbsUp(anime, 'cf', null)}
									>
										<ThumbUpIcon className="feedback-btn-icon" aria-hidden="true" />
										<span>Thumbs Up</span>
									</button>
									<button
										className={`feedback-btn feedback-btn-down${feedback.getFeedbackSignal(anime.id) === 'THUMBS_DOWN' ? ' is-active-down' : ''}`}
										onClick={() => feedback.handleThumbsDown(anime, 'cf', null)}
									>
										<ThumbDownIcon className="feedback-btn-icon" aria-hidden="true" />
										<span>Thumbs Down</span>
									</button>
								</>
							)}
						</AnimeRecItem>
					))}
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

export default Recommendations;
