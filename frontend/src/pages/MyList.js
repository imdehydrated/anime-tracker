import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { getApiError } from '../api/client';
import {
	deleteListEntry,
	getUserList,
	importAniListByUsername,
	importMalByUsername,
	updateListEntry,
} from '../api/listApi';

const STATUS_LABELS = {
	WATCHING: 'Watching',
	COMPLETED: 'Completed',
	PLAN_TO_WATCH: 'Plan to Watch',
	ON_HOLD: 'On Hold',
	DROPPED: 'Dropped',
};

const FILTER_STATUS_OPTIONS = [
	{ value: 'ALL', label: 'All Status' },
	{ value: 'WATCHING', label: 'Watching' },
	{ value: 'COMPLETED', label: 'Completed' },
	{ value: 'PLAN_TO_WATCH', label: 'Plan to Watch' },
	{ value: 'ON_HOLD', label: 'On Hold' },
	{ value: 'DROPPED', label: 'Dropped' },
];

const SORT_OPTIONS = [
	{ value: 'DATE_DESC', label: 'Newest First' },
	{ value: 'TITLE_ASC', label: 'Title A-Z' },
	{ value: 'SCORE_DESC', label: 'Highest Score' },
	{ value: 'STATUS', label: 'By Status' },
];

const SCORE_OPTIONS = [
	{ value: '', label: '-' },
	{ value: '1', label: '1' },
	{ value: '2', label: '2' },
	{ value: '3', label: '3' },
	{ value: '4', label: '4' },
	{ value: '5', label: '5' },
	{ value: '6', label: '6' },
	{ value: '7', label: '7' },
	{ value: '8', label: '8' },
	{ value: '9', label: '9' },
	{ value: '10', label: '10' },
];

const STATUS_OPTIONS = [
	{ value: 'WATCHING', label: 'Watching' },
	{ value: 'COMPLETED', label: 'Completed' },
	{ value: 'PLAN_TO_WATCH', label: 'Plan to Watch' },
	{ value: 'ON_HOLD', label: 'On Hold' },
	{ value: 'DROPPED', label: 'Dropped' },
];

function getStatusBadgeClass(status) {
	switch (status) {
		case 'WATCHING':
			return 'watching';
		case 'COMPLETED':
			return 'completed';
		case 'ON_HOLD':
			return 'on_hold';
		case 'DROPPED':
			return 'dropped';
		case 'PLAN_TO_WATCH':
			return 'plan_to_watch';
		default:
			return '';
	}
}

function CustomSelect({
	value,
	options,
	onChange,
	className = '',
	triggerClassName = '',
	menuClassName = '',
	ariaLabel,
}) {
	const [open, setOpen] = useState(false);
	const rootRef = useRef(null);
	const selectedOption = options.find((option) => String(option.value) === String(value)) || options[0];

	useEffect(() => {
		if (!open) return undefined;

		const handlePointerDown = (event) => {
			if (rootRef.current && !rootRef.current.contains(event.target)) {
				setOpen(false);
			}
		};

		const handleKeyDown = (event) => {
			if (event.key === 'Escape') {
				setOpen(false);
			}
		};

		document.addEventListener('mousedown', handlePointerDown);
		document.addEventListener('keydown', handleKeyDown);

		return () => {
			document.removeEventListener('mousedown', handlePointerDown);
			document.removeEventListener('keydown', handleKeyDown);
		};
	}, [open, rootRef]);

	return (
		<div
			ref={(node) => {
				rootRef.current = node;
			}}
			className={`custom-select ${className}${open ? ' is-open' : ''}`}
		>
			<button
				type="button"
				className={`custom-select-trigger ${triggerClassName}`}
				onClick={() => setOpen((prev) => !prev)}
				aria-haspopup="listbox"
				aria-expanded={open}
				aria-label={ariaLabel}
			>
				<span className="custom-select-label">{selectedOption?.label || ''}</span>
				<span className="custom-select-chevron" aria-hidden="true">v</span>
			</button>
			{open && (
				<div className={`custom-select-menu ${menuClassName}`} role="listbox">
					{options.map((option) => (
						<button
							key={option.value || 'blank'}
							type="button"
							className={`custom-select-option${String(option.value) === String(value) ? ' is-selected' : ''}`}
							onClick={() => {
								onChange(option.value);
								setOpen(false);
							}}
						>
							{option.label}
						</button>
					))}
				</div>
			)}
		</div>
	);
}

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
	const [importProvider, setImportProvider] = useState('anilist');
	const [importUsername, setImportUsername] = useState('');
	const [importLoading, setImportLoading] = useState(false);
	const [importError, setImportError] = useState('');
	const [importResult, setImportResult] = useState(null);

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

	const handleImport = async (dryRun) => {
		setImportError('');
		setImportResult(null);
		const username = importUsername.trim();
		if (!username) {
			setImportError('Enter a username to import from AniList or MAL.');
			return;
		}

		setImportLoading(true);
		try {
			const response = importProvider === 'mal'
				? await importMalByUsername(username, dryRun)
				: await importAniListByUsername(username, dryRun);

			setImportResult({
				message: response?.message || 'Import completed',
				stats: response?.stats || {},
				dryRun,
			});

			if (!dryRun) {
				await fetchList();
			}
		} catch (err) {
			setImportError(getApiError(err, 'Import failed'));
		} finally {
			setImportLoading(false);
		}
	};

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
	const listSummary = useMemo(() => {
		const scoredEntries = filteredList.filter((entry) => Number.isFinite(entry.score));
		const completedCount = filteredList.filter((entry) => entry.status === 'COMPLETED').length;
		const watchingCount = filteredList.filter((entry) => entry.status === 'WATCHING').length;
		const averageScore = scoredEntries.length > 0
			? (scoredEntries.reduce((sum, entry) => sum + entry.score, 0) / scoredEntries.length).toFixed(1)
			: null;

		return [
			{ label: 'Visible Entries', value: filteredList.length },
			{ label: 'Completed', value: completedCount },
			{ label: 'Watching', value: watchingCount },
			{ label: 'Average Score', value: averageScore ? `${averageScore}/10` : 'No scores yet' },
		];
	}, [filteredList]);

	if (loading) return <div className="page"><p className="loading">Loading...</p></div>;

	return (
		<div className="page mylist-page">
			<div className="list-page-header fade-in-up">
				<h1>My Anime List</h1>
				<button className="btn-primary add-to-list-nav" onClick={() => navigate('/search')}>
					+ Add to Your List
				</button>
			</div>

			{error && <p className="error-message">{error}</p>}
			<div className="list-import-panel">
				<div className="list-import-header">
					<h2>Import From AniList / MAL</h2>
					<span className="list-import-note">Username-based sync for your list</span>
				</div>
				<div className="list-import-controls">
					<div className="list-import-provider-grid" role="group" aria-label="Import provider">
						<button
							type="button"
							className={`list-import-provider ${importProvider === 'anilist' ? 'is-active' : ''}`}
							onClick={() => setImportProvider('anilist')}
							disabled={importLoading}
						>
							AniList
						</button>
						<button
							type="button"
							className={`list-import-provider ${importProvider === 'mal' ? 'is-active' : ''}`}
							onClick={() => setImportProvider('mal')}
							disabled={importLoading}
						>
							MAL
						</button>
					</div>
					<input
						type="text"
						value={importUsername}
						placeholder={`Enter ${importProvider === 'mal' ? 'MAL' : 'AniList'} username`}
						onChange={(e) => setImportUsername(e.target.value)}
						disabled={importLoading}
					/>
					<button
						type="button"
						className="btn-outline"
						onClick={() => handleImport(true)}
						disabled={importLoading}
					>
						{importLoading ? 'Running...' : 'Dry Run'}
					</button>
					<button
						type="button"
						className="btn-primary"
						onClick={() => handleImport(false)}
						disabled={importLoading}
					>
						{importLoading ? 'Running...' : 'Import'}
					</button>
				</div>
				{importError && <p className="error-message">{importError}</p>}
				{importResult && (
					<div className="list-import-result">
						<p className="list-import-result-title">
							{importResult.message}{importResult.dryRun ? ' (dry run)' : ''}
						</p>
						<p>
							Discovered: {importResult.stats.discovered ?? 0} | Imported: {importResult.stats.imported ?? 0} |
							Updated: {importResult.stats.updated ?? 0} | Skipped: {importResult.stats.skipped ?? 0} |
							Failed: {importResult.stats.failed ?? 0}
						</p>
						{Array.isArray(importResult.stats.failureSamples) && importResult.stats.failureSamples.length > 0 && (
							<p className="list-import-failures">
								Failures: {importResult.stats.failureSamples
									.map((sample) => sample?.detail || sample?.reason || 'unknown')
									.join(' | ')}
							</p>
						)}
					</div>
				)}
			</div>

			{entries.length === 0 ? (
				<div className="empty-state empty-state-block">
					<p className="empty-state-kicker">Your list is empty</p>
					<h2>Start tracking shows to unlock cleaner recommendations.</h2>
					<p>Search for a title, add it to your list, and your score and status history will start shaping future results.</p>
					<p><Link to="/search">Search the catalog</Link> to get started.</p>
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
						<CustomSelect
							value={filterStatus}
							options={FILTER_STATUS_OPTIONS}
							onChange={setFilterStatus}
							className="list-filter-select-shell"
							ariaLabel="Filter list by status"
						/>
						<CustomSelect
							value={sortBy}
							options={SORT_OPTIONS}
							onChange={setSortBy}
							className="list-filter-select-shell"
							ariaLabel="Sort list"
						/>
					</div>

					<div className="mylist-stats-bar fade-in-up fade-delay-1">
						{listSummary.map((item) => (
							<div key={item.label} className="mylist-stat-card">
								<span className="mylist-stat-label">{item.label}</span>
								<strong className="mylist-stat-value">{item.value}</strong>
							</div>
						))}
					</div>

					<div className="mylist-table-wrap">
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
											<div className="cell-control-row score-row">
												<CustomSelect
													value={entry.score ?? ''}
													options={SCORE_OPTIONS}
													onChange={(nextValue) => handleUpdate(
														entry.id,
														{ score: nextValue === '' ? null : parseInt(nextValue, 10) },
														entry
													)}
													className="score-select-shell"
													triggerClassName="compact-trigger"
													menuClassName="compact-menu"
													ariaLabel={`Update score for ${entry.title || `AniList ${entry.anilistId}`}`}
												/>
											</div>
										</td>
										<td className="col-status">
											<div className="cell-control-row status-row">
												<CustomSelect
													value={entry.status}
													options={STATUS_OPTIONS}
													onChange={(nextValue) => handleUpdate(entry.id, { status: nextValue }, entry)}
													className={`status-select-shell ${getStatusBadgeClass(entry.status)}`}
													triggerClassName="status-trigger"
													menuClassName="status-menu"
													ariaLabel={`Update status for ${entry.title || `AniList ${entry.anilistId}`}`}
												/>
											</div>
										</td>
										<td className="col-progress">
											<div className="cell-control-row progress-row">
												<div className="progress-input">
												<div className="table-input-shell progress-field-shell">
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
												</div>
												{entry.totalEpisodes != null && <span className="progress-total">/ {entry.totalEpisodes}</span>}
											</div>
											</div>
										</td>
										<td className="col-actions">
											<button
												className="icon-delete-btn"
												onClick={() => handleDelete(entry.id, entry.title)}
												aria-label={`Delete ${entry.title || `AniList ${entry.anilistId}`}`}
												title="Delete"
											>
												x
											</button>
										</td>
									</tr>
								))}
							</tbody>
						</table>
					</div>

					{filteredList.length === 0 && entries.length > 0 && (
						<div className="empty-state empty-state-block">
							<p className="empty-state-kicker">No matching entries</p>
							<h2>Nothing fits the current filters.</h2>
							<p>Adjust the title search, change the status filter, or switch the sort to bring more of your list back into view.</p>
						</div>
					)}
				</>
			)}
		</div>
	);
}

export default MyList;
