import { Link } from 'react-router-dom';

function AnimeCard({ anime, children }) {
	const title = anime.title.english || anime.title.romaji;
	const detailState = { anime };
	const coverUrl = typeof anime.coverImage === 'string'
		? anime.coverImage
		: anime.coverImage?.large || anime.coverImage?.medium || '';

	return (
		<div className="anime-card">
			{coverUrl && (
				<Link to={`/anime/${anime.id}`} state={detailState}>
					<img
						src={coverUrl}
						alt={anime.title.romaji}
						onError={(e) => {
							e.currentTarget.style.display = 'none';
						}}
					/>
				</Link>
			)}

			<div className="card-body">
				<h3><Link to={`/anime/${anime.id}`} state={detailState}>{title}</Link></h3>
				<p>{anime.genres && anime.genres.join(', ')}</p>
				<p>
					Ep: {anime.episodes || '?'} | Score: <span className="score">{anime.averageScore || '?'}</span>/100
				</p>
				{children}
			</div>
		</div>
	);
}

export default AnimeCard;
