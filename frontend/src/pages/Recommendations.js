import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import BlacklistModal from '../components/BlacklistModal';
import AnimeRecItem from '../components/AnimeRecItem';
import { useAuth } from '../context/AuthContext';
import { getApiError } from '../api/client';
import { getSemanticRecommendations } from '../api/recommendationsApi';
import { useAddToList } from '../hooks/useAddToList';
import { useRecommendationBlacklist } from '../hooks/useRecommendationBlacklist';

/**
 * "For You" recommendations page:
 * - List-only semantic recommendations for logged-in users
 * - Add-to-list actions and blacklist management
 */
function Recommendations() {
	const [recommendations, setRecommendations] = useState([]);
	const [loading, setLoading] = useState(false);
	const [fetchError, setFetchError] = useState('');
	const [addedIds, setAddedIds] = useState(new Set());

	const { isLoggedIn } = useAuth();
	const { addToList, message, error, clearMessages, setError } = useAddToList();
	const blacklist = useRecommendationBlacklist(
		setError,
		(animeId) => setRecommendations((prev) => prev.filter((item) => item.id !== animeId))
	);

	const fetchRecommendations = async () => {
		setLoading(true);
		setFetchError('');
		clearMessages();

		try {
			const data = await getSemanticRecommendations({ useListOnly: true, limit: 15 });
			setRecommendations(data);
		} catch (err) {
			setFetchError(getApiError(err, 'Failed to load recommendations.'));
		} finally {
			setLoading(false);
		}
	};

	useEffect(() => {
		if (!isLoggedIn) return;
		fetchRecommendations();
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [isLoggedIn]);

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
				<button className="refresh-btn" onClick={blacklist.openBlacklist}>
					Manage Blacklist
				</button>
			</div>

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
									<button className="blacklist-btn" onClick={() => blacklist.handleBlacklist(anime)}>
										Not Interested
									</button>
								</>
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

export default Recommendations;
