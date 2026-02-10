/**
 * MyList Page — MAL-style table layout for the user's anime list.
 *
 * Features: text filter, status filter, sort options, table with aligned columns,
 * confirm before delete, clickable thumbnails/titles, "Add to your list" button.
 * All changes (status, score, episodes) are saved immediately via PUT.
 */
import { useState, useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuthHeader } from '../hooks/useAuthHeader';
import axios from 'axios';

function MyList() {
	const [entries, setEntries] = useState([]);
	const [loading, setLoading] = useState(true);
	const [error, setError] = useState('');

	// Filter and sort state
	const [filterText, setFilterText] = useState('');
	const [filterStatus, setFilterStatus] = useState('ALL');
	const [sortBy, setSortBy] = useState('DATE_DESC');

	const authHeader = useAuthHeader();
	const navigate = useNavigate();

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

	// eslint-disable-next-line react-hooks/exhaustive-deps
	useEffect(() => { fetchList(); }, []);

	const handleUpdate = async (entryId, updates) => {
		setError('');
		try {
			await axios.put(`/api/users/list/${entryId}`, updates, authHeader);
			fetchList();
		} catch (err) {
			setError('Failed to update');
		}
	};

	const handleDelete = async (entryId, title) => {
		if (!window.confirm(`Remove "${title}" from your list?`)) return;
		try {
			await axios.delete(`/api/users/list/${entryId}`, authHeader);
			fetchList();
		} catch (err) {
			setError('Failed to delete');
		}
	};

	// Client-side filter and sort
	const filteredList = entries
		.filter(entry => filterStatus === 'ALL' || entry.status === filterStatus)
		.filter(entry => entry.title?.toLowerCase().includes(filterText.toLowerCase()))
		.sort((a, b) => {
			switch (sortBy) {
				case 'TITLE_ASC': return (a.title || '').localeCompare(b.title || '');
				case 'SCORE_DESC': return (b.score || 0) - (a.score || 0);
				case 'STATUS': return (a.status || '').localeCompare(b.status || '');
				case 'DATE_DESC':
				default: return new Date(b.createdAt || 0) - new Date(a.createdAt || 0);
			}
		});

	if (loading) return <div className="page"><p className="loading">Loading...</p></div>;

	return (
		<div className="page">
			<div className="list-page-header">
				<h1>My Anime List</h1>
				<button className="add-to-list-nav" onClick={() => navigate('/search')}>
					+ Add to Your List
				</button>
			</div>

			{error && <p className="error-message">{error}</p>}

			{entries.length === 0 ? (
				<div className="empty-state">
					<p>Your list is empty. <Link to="/search">Search for anime</Link> to get started!</p>
				</div>
			) : (
				<>
					{/* Filter and sort bar */}
					<div className="list-filters">
						<input
							type="text"
							placeholder="Filter by title..."
							value={filterText}
							onChange={(e) => setFilterText(e.target.value)}
						/>
						<select value={filterStatus} onChange={(e) => setFilterStatus(e.target.value)}>
							<option value="ALL">All Status</option>
							<option value="WATCHING">Watching</option>
							<option value="COMPLETED">Completed</option>
							<option value="PLAN_TO_WATCH">Plan to Watch</option>
							<option value="ON_HOLD">On Hold</option>
							<option value="DROPPED">Dropped</option>
						</select>
						<select value={sortBy} onChange={(e) => setSortBy(e.target.value)}>
							<option value="DATE_DESC">Newest First</option>
							<option value="TITLE_ASC">Title A-Z</option>
							<option value="SCORE_DESC">Highest Score</option>
							<option value="STATUS">By Status</option>
						</select>
					</div>

					{/* MAL-style table */}
					<table className="mal-table">
						<thead>
							<tr>
								<th className="col-num">#</th>
								<th className="col-image">Image</th>
								<th className="col-title">Anime Title</th>
								<th className="col-score">Score</th>
								<th className="col-status">Status</th>
								<th className="col-progress">Progress</th>
								<th className="col-actions"></th>
							</tr>
						</thead>
						<tbody>
							{filteredList.map((entry, index) => (
								<tr key={entry.id}>
									<td className="col-num">{index + 1}</td>
									<td className="col-image">
										{entry.coverImage && (
											<Link to={`/anime/${entry.anilistId}`}>
												<img src={entry.coverImage} alt={entry.title} />
											</Link>
										)}
									</td>
									<td className="col-title">
										<Link to={`/anime/${entry.anilistId}`}>
											{entry.title || `AniList #${entry.anilistId}`}
										</Link>
									</td>
									<td className="col-score">
										<select
											value={entry.score || ''}
											onChange={(e) => handleUpdate(entry.id, { score: e.target.value === '' ? null : parseInt(e.target.value) })}
										>
											<option value="">-</option>
											{[1,2,3,4,5,6,7,8,9,10].map(n => (
												<option key={n} value={n}>{n}</option>
											))}
										</select>
									</td>
									<td className="col-status">
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
									</td>
									<td className="col-progress">
										<div className="progress-input">
											<input
												type="number"
												min="0"
												max={entry.totalEpisodes || undefined}
												value={entry.episodesWatched || 0}
												onChange={(e) => {
													let val = parseInt(e.target.value) || 0;
													if (entry.totalEpisodes && val > entry.totalEpisodes) val = entry.totalEpisodes;
													handleUpdate(entry.id, { episodesWatched: val });
												}}
											/>
											<span className="progress-total">/ {entry.totalEpisodes || '?'}</span>
										</div>
									</td>
									<td className="col-actions">
										<button className="delete-btn" onClick={() => handleDelete(entry.id, entry.title)}>
											Delete
										</button>
									</td>
								</tr>
							))}
						</tbody>
					</table>

					{filteredList.length === 0 && entries.length > 0 && (
						<p style={{ textAlign: 'center', color: '#999', padding: '2rem' }}>
							No entries match your filters.
						</p>
					)}
				</>
			)}
		</div>
	);
}

export default MyList;
