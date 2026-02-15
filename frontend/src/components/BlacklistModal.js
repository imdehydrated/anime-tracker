import { Link } from 'react-router-dom';

// Reusable modal for browsing/removing recommendation blacklist entries.
function BlacklistModal({
	show,
	blacklist,
	search,
	onSearchChange,
	onClose,
	onRemove,
}) {
	if (!show) return null;

	return (
		<div className="modal-overlay" onClick={onClose}>
			<div className="modal" onClick={(e) => e.stopPropagation()}>
				<div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
					<h2>Hidden Anime</h2>
					<button className="btn-danger" onClick={onClose}>Close</button>
				</div>
				{blacklist.length === 0 ? (
					<p>No hidden anime.</p>
				) : (
					<>
						<input
							type="text"
							className="blacklist-search"
							placeholder="Search hidden anime..."
							value={search}
							onChange={(e) => onSearchChange(e.target.value)}
						/>
						<div className="blacklist-cards">
							{blacklist
								.filter((item) => (item.title || '').toLowerCase().includes(search.toLowerCase()))
								.map((item) => (
									<div key={item.id} className="blacklist-card">
										{item.coverImage && <img src={item.coverImage} alt={item.title} />}
										<div className="blacklist-card-info">
											<h3><Link to={`/anime/${item.anilistId}`}>{item.title || `AniList #${item.anilistId}`}</Link></h3>
											<button className="btn-danger" onClick={() => onRemove(item.id)}>
												Remove
											</button>
										</div>
									</div>
								))}
						</div>
					</>
				)}
			</div>
		</div>
	);
}

export default BlacklistModal;
