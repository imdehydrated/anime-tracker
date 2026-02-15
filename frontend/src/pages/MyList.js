import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { getApiError } from '../api/client';
import { deleteListEntry, getUserList, updateListEntry } from '../api/listApi';

/**
 * MyList page:
 * - Loads authenticated user's list
 * - Supports optimistic status/score/progress edits
 * - Supports optimistic delete with rollback on API failure
 * - Applies client-side filtering/sorting for table rendering
 */
function MyList() {
	const [entries, setEntries] = useState([]);
	const [loading, setLoading] = useState(true);
	const [error, setError] = useState('');
	const [episodesDrafts, setEpisodesDrafts] = useState({});

	const [filterText, setFilterText] = useState('');
	const [filterStatus, setFilterStatus] = useState('ALL');
	const [sortBy, setSortBy] = useState('DATE_DESC');

	const navigate = useNavigate();

	const syncEpisodeDrafts = (list) => {
		const next = {};
		for (const entry of list) {
			next[entry.id] = entry.episodesWatched || 0;
		}
		setEpisodesDrafts(next);
	};

	const fetchList = async () => {
		try {
			const data = await getUserList();
			setEntries(data);
			syncEpisodeDrafts(data);
		} catch (err) {
			setError(getApiError(err, 'Failed to load list'));
		} finally {
			setLoading(false);
		}
	};

	useEffect(() => {
		fetchList();
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, []);

	const handleUpdate = async (entryId, updates, entry) => {
		setError('');

		const payload = { ...updates };
		if (payload.status === 'COMPLETED' && entry?.totalEpisodes != null) {
			payload.episodesWatched = entry.totalEpisodes;
		}

		const previousEntries = entries;
		const optimisticEntries = entries.map((item) => {
			if (item.id !== entryId) return item;
			return {
				...item,
				...payload,
				episodesWatched: payload.episodesWatched ?? item.episodesWatched,
				score: Object.prototype.hasOwnProperty.call(payload, 'score') ? payload.score : item.score,
			};
		});

		setEntries(optimisticEntries);
		if (Object.prototype.hasOwnProperty.call(payload, 'episodesWatched')) {
			setEpisodesDrafts((prev) => ({ ...prev, [entryId]: payload.episodesWatched ?? 0 }));
		}

		try {
			await updateListEntry(entryId, payload);
		} catch (err) {
			setEntries(previousEntries);
			syncEpisodeDrafts(previousEntries);
			setError(getApiError(err, 'Failed to update'));
		}
	};

	const handleDelete = async (entryId, title) => {
		if (!window.confirm(`Remove "${title}" from your list?`)) return;

		const previousEntries = entries;
		const nextEntries = entries.filter((entry) => entry.id !== entryId);
		setEntries(nextEntries);
		setEpisodesDrafts((prev) => {
			const next = { ...prev };
			delete next[entryId];
			return next;
		});

		try {
			await deleteListEntry(entryId);
		} catch (err) {
			setEntries(previousEntries);
			syncEpisodeDrafts(previousEntries);
			setError(getApiError(err, 'Failed to delete'));
		}
	};

	const filteredList = useMemo(() => {
		return entries
			.filter((entry) => filterStatus === 'ALL' || entry.status === filterStatus)
			.filter((entry) => entry.title?.toLowerCase().includes(filterText.toLowerCase()))
			.sort((a, b) => {
				switch (sortBy) {
					case 'TITLE_ASC':
						return (a.title || '').localeCompare(b.title || '');
					case 'SCORE_DESC':
						return (b.score || 0) - (a.score || 0);
					case 'STATUS':
						return (a.status || '').localeCompare(b.status || '');
					case 'DATE_DESC':
					default:
						return new Date(b.createdAt || 0) - new Date(a.createdAt || 0);
				}
			});
	}, [entries, filterStatus, filterText, sortBy]);

	if (loading) return <div className="page"><p className="loading">Loading...</p></div>;

	return (
		<div className="page">
			<div className="list-page-header">
				<h1>My Anime List</h1>
				<button className="btn-primary add-to-list-nav" onClick={() => navigate('/search')}>
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
											onChange={(e) => handleUpdate(
												entry.id,
												{ score: e.target.value === '' ? null : parseInt(e.target.value, 10) },
												entry
											)}
										>
											<option value="">-</option>
											{[1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map((n) => (
												<option key={n} value={n}>{n}</option>
											))}
										</select>
									</td>
									<td className="col-status">
										<select
											value={entry.status}
											onChange={(e) => handleUpdate(entry.id, { status: e.target.value }, entry)}
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
												value={episodesDrafts[entry.id] ?? 0}
												onChange={(e) => {
													const value = e.target.value === '' ? '' : parseInt(e.target.value, 10);
													setEpisodesDrafts((prev) => ({ ...prev, [entry.id]: value }));
												}}
												onBlur={() => {
													let val = parseInt(episodesDrafts[entry.id], 10);
													if (Number.isNaN(val)) val = 0;
													if (val < 0) val = 0;
													if (entry.totalEpisodes && val > entry.totalEpisodes) val = entry.totalEpisodes;
													setEpisodesDrafts((prev) => ({ ...prev, [entry.id]: val }));
													if (val !== (entry.episodesWatched || 0)) {
														handleUpdate(entry.id, { episodesWatched: val }, entry);
													}
												}}
												onKeyDown={(e) => {
													if (e.key === 'Enter') e.currentTarget.blur();
												}}
											/>
											{entry.totalEpisodes != null && <span className="progress-total">/ {entry.totalEpisodes}</span>}
										</div>
									</td>
									<td className="col-actions">
										<button className="btn-danger" onClick={() => handleDelete(entry.id, entry.title)}>
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
