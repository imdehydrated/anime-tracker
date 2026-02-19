import { Link } from 'react-router-dom';

function AnimeRecItem({ anime, children }) {
	const title = anime.title.english || anime.title.romaji;
	const detailState = { anime };
	const coverUrl = typeof anime.coverImage === 'string'
		? anime.coverImage
		: anime.coverImage?.large || anime.coverImage?.medium || '';
	const description = anime.description
		? anime.description.slice(0, 200) + (anime.description.length > 200 ? '...' : '')
		: 'No description available.';
	const recommendationReason = anime.recommendationReason;

	return (
		<div className="anime-rec-item">
			{coverUrl && (
				<Link to={`/anime/${anime.id}`} state={detailState} className="anime-rec-item-image">
					<img
						src={coverUrl}
						alt={anime.title.romaji}
						onError={(e) => {
							e.currentTarget.style.display = 'none';
						}}
					/>
				</Link>
			)}

			<div className="anime-rec-item-content">
				<h3><Link to={`/anime/${anime.id}`} state={detailState}>{title}</Link></h3>

				<div className="anime-rec-item-meta">
					{anime.genres && <span>{anime.genres.join(', ')}</span>}
					<span>Ep: {anime.episodes || '?'} | Score: <span className="score">{anime.averageScore || '?'}</span>/100</span>
				</div>

				{recommendationReason && (
					<p className="anime-rec-item-reason">{recommendationReason}</p>
				)}

				<p className="anime-rec-item-desc">{description}</p>

				<div className="anime-rec-item-actions">
					{children}
				</div>
			</div>
		</div>
	);
}

export default AnimeRecItem;
