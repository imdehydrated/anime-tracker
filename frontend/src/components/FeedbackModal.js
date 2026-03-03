import { Link } from 'react-router-dom';

// Reusable modal for browsing/removing recommendation feedback entries.
function FeedbackModal({
	show,
	feedbackEntries,
	search,
	onSearchChange,
	onClose,
	onRemove,
	title = 'Recommendation Feedback',
	emptyText = 'No feedback entries.',
	searchPlaceholder = 'Search feedback...',
}) {
	if (!show) return null;

	return (
		<div className="modal-overlay" onClick={onClose}>
			<div className="modal" onClick={(e) => e.stopPropagation()}>
				<div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
					<h2>{title}</h2>
					<button className="btn-danger" onClick={onClose}>Close</button>
				</div>
				{feedbackEntries.length === 0 ? (
					<p>{emptyText}</p>
				) : (
					<>
						<input
							type="text"
							className="feedback-modal-search"
							placeholder={searchPlaceholder}
							value={search}
							onChange={(e) => onSearchChange(e.target.value)}
						/>
						<div className="feedback-modal-cards">
							{feedbackEntries
								.filter((item) => (item.title || '').toLowerCase().includes(search.toLowerCase()))
								.map((item) => (
									<div key={item.id} className="feedback-modal-card">
										{item.coverImage && <img src={item.coverImage} alt={item.title} />}
										<div className="feedback-modal-card-info">
											<h3>
												<Link to={`/anime/${item.anilistId}`}>
													{item.title || `AniList #${item.anilistId}`}
												</Link>
												{item.signal ? ` (${item.signal === 'THUMBS_UP' ? 'Thumbs Up' : 'Thumbs Down'})` : ''}
											</h3>
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

export default FeedbackModal;
