/**
 * MyList Page — Displays the user's anime list in compact rows.
 *
 * Each row shows: thumbnail, clickable title, status dropdown,
 * score input, episodes input, and a delete button.
 * All changes (status, score, episodes) are saved immediately via PUT.
 */
import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import axios from 'axios';

function MyList() {
	// List entries fetched from the backend
	const [entries, setEntries] = useState([]);
	const [loading, setLoading] = useState(true);
	const [error, setError] = useState('');

	// Auth header — reused for all protected API calls
	const { token } = useAuth();
	const authHeader = { headers: { Authorization: `Bearer ${token}` } };

	/**
	 * fetchList — Loads the user's anime list from GET /api/users/list.
	 * Called on mount and after any update/delete to keep UI in sync.
	 */
	const fetchList = async () => {
		try {
			const { data } = await axios.get('/api/users/list', authHeader);
			setEntries(data);
		} catch (err) {
			setError('Failed to load list');
		} finally {
			setLoading(false);
		}
	};

	// Fetch list on component mount
	// eslint-disable-next-line react-hooks/exhaustive-deps
	useEffect(() => { fetchList(); }, []);

	/**
	 * handleUpdate — Sends a PUT request to update a single field.
	 * After the update completes, refetches the full list.
	 * @param {number} entryId — the database ID of the entry
	 * @param {object} updates — fields to change (e.g., { status: 'WATCHING' })
	 */
	const handleUpdate = async (entryId, updates) => {
		setError('');
		try {
			await axios.put(`/api/users/list/${entryId}`, updates, authHeader);
			fetchList(); // Refresh list to show updated values
		} catch (err) {
			setError('Failed to update');
		}
	};

	/**
	 * handleDelete — Removes an entry from the user's list.
	 * Sends DELETE /api/users/list/{id}, then refreshes the list.
	 */
	const handleDelete = async (entryId) => {
		try {
			await axios.delete(`/api/users/list/${entryId}`, authHeader);
			fetchList(); // Refresh list after removal
		} catch (err) {
			setError('Failed to delete');
		}
	};
	// Loading and error states
	if (loading) return <div className="page"><p className="loading">Loading...</p></div>;

	return (
		<div className="page">
			<h1>My Anime List</h1>

			{error && <p className="error-message">{error}</p>}

			{/* Empty state — show when user has no entries */}
			{entries.length === 0 ? (
				<div className="empty-state">
					<p>Your list is empty. <Link to="/search">Search for anime</Link> to get started!</p>
				</div>
			) : (
				/* Compact row list — each entry is a horizontal row */
				<div className="list-table">
					{entries.map((entry) => (
						<div key={entry.id} className="list-row">

							{/* Small thumbnail image */}
							{entry.coverImage && (
								<img src={entry.coverImage} alt={entry.title} />
							)}

							{/* Clickable title — links to anime detail page */}
							<div className="list-title">
								<Link to={`/anime/${entry.anilistId}`}>
									{entry.title || `AniList #${entry.anilistId}`}
								</Link>
							</div>

							{/* Status dropdown — saves on change */}
							<select
								value={entry.status}
								onChange={(e) => handleUpdate(entry.id, { status: e.target.value })}
							>
								<option value="WATCHING">Watching</option>
								<option value="COMPLETED">Completed</option>
								<option value="PLAN_TO_WATCH">Plan to Watch</option>
								<option value="ON_HOLD">On Hold</option>
								<option value="DROPPED">Dropped</option>
							</select>

							{/* Score dropdown — 1 to 10, saves on change */}
							<select
								value={entry.score || ''}
								onChange={(e) => handleUpdate(entry.id, { score: e.target.value === '' ? null : parseInt(e.target.value) })}
							>
								<option value="">—</option>
								{[1,2,3,4,5,6,7,8,9,10].map(n => (
									<option key={n} value={n}>{n}</option>
								))}
							</select>

							{/* Episodes watched input — saves on change */}
							<input
								type="number"
								min="0"
								value={entry.episodesWatched || 0}
								onChange={(e) => handleUpdate(entry.id, { episodesWatched: parseInt(e.target.value) })}
							/>

							{/* Delete button — red outline, fills red on hover */}
							<button className="delete-btn" onClick={() => handleDelete(entry.id)}>
								Delete
							</button>
						</div>
					))}
				</div>
			)}
		</div>
	);
}

export default MyList;