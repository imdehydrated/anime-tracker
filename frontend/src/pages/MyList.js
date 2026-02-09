/**
 * MyList Page — Displays and manages the user's anime list.
 *
 * Features:
 * - Shows anime with title and cover image (stored in DB)
 * - Click anime title to navigate to detail page
 * - Edit status, score, and episodes watched
 * - Delete entries from list
 * - Refetches list after each update/delete for simplicity
 */
import { useState, useEffect } from 'react';
import { useAuth } from '../context/AuthContext';
import { Link } from 'react-router-dom';
import axios from 'axios';

function MyList() {
	const { token } = useAuth();

	const [entries, setEntries] = useState([]);
	const [loading, setLoading] = useState(true);
	const [error, setError] = useState('');

	// Auth header reused by all API calls on this page
	const authHeader = { headers: { Authorization: `Bearer ${token}` } };

	// Fetch the user's list — called on mount and after updates/deletes
	const fetchList = async () => {
		try {
			const { data } = await axios.get('/api/users/list', authHeader);
			setEntries(data);
		} catch (err) {
			setError('Failed to load anime list');
		} finally {
			setLoading(false);
		}
	};

	useEffect(() => {
		fetchList();
	}, [token]);

	// Update an entry's status, score, or episodes
	const handleUpdate = async (entryId, updates) => {
		try {
			await axios.put(`/api/users/list/${entryId}`, updates, authHeader);
			fetchList(); // Refetch to show updated data
		} catch (err) {
			setError('Failed to update entry');
		}
	};

	// Delete an entry from the list
	const handleDelete = async (entryId) => {
		try {
			await axios.delete(`/api/users/list/${entryId}`, authHeader);
			fetchList(); // Refetch to remove deleted entry
		} catch (err) {
			setError('Failed to delete entry');
		}
	};

  	if (loading) return <div className="page"><p>Loading...</p></div>;

	return (
		<div className="page">
			<h1>My Anime List</h1>

			{error && <p className="error-message">{error}</p>}

			{entries.length === 0 ? (
				<p>Your list is empty. <Link to="/search">Search for anime</Link> to add some!</p>
			) : (
				<div className="anime-list">
					{entries.map((entry) => (
						<div key={entry.id} className="anime-card">

							{entry.coverImage && (
								<img src={entry.coverImage} alt={entry.title} />
							)}

							<div className="anime-info">
								{/* Clickable title — navigates to detail page */}
								<h3>
									<Link to={`/anime/${entry.anilistId}`}>
										{entry.title || `AniList #${entry.anilistId}`}
									</Link>
								</h3>

								{/* Status dropdown */}
								<label>Status: </label>
								<select
									value={entry.status}
									onChange={(e) => handleUpdate(entry.id, { status: e.target.value })}
								>
									<option value="WATCHING">Watching</option>
									<option value="COMPLETED">Completed</option>
									<option value="PLAN_TO_WATCH">Plan to Watch</option>
									<option value="DROPPED">Dropped</option>
									<option value="ON_HOLD">On Hold</option>
								</select>

								{/* Score input */}
								<label> Score: </label>
								<input
									type="number"
									min="0"
									max="10"
									value={entry.score || ''}
									placeholder="-"
									onChange={(e) => handleUpdate(entry.id, { score: parseInt(e.target.value) || null })}
								/>

								{/* Episodes input */}
								<label> Episodes: </label>
								<input
									type="number"
									min="0"
									value={entry.episodesWatched || 0}
									onChange={(e) => handleUpdate(entry.id, { episodesWatched: parseInt(e.target.value) || 0 })}
								/>

								<button onClick={() => handleDelete(entry.id)}>Delete</button>
							</div>

						</div>
					))}
				</div>
			)}
		</div>
	);
}

export default MyList;