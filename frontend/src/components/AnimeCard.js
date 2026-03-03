import { useState } from 'react';
import { Link } from 'react-router-dom';

function AnimeCard({ anime, children }) {
	const [coverFailed, setCoverFailed] = useState(false);
	const title = anime.title?.english || anime.title?.romaji || 'Unknown Title';
	const detailState = { anime };
	const coverUrl = typeof anime.coverImage === 'string'
		? anime.coverImage
		: anime.coverImage?.large || anime.coverImage?.medium || '';
	const showCover = !!coverUrl && !coverFailed;

	return (
		<div className="anime-card">
			{showCover ? (
				<Link to={`/anime/${anime.id}`} state={detailState}>
					<img
						src={coverUrl}
						alt={anime.title?.romaji || title}
						onError={(e) => {
							e.currentTarget.style.display = 'none';
							setCoverFailed(true);
						}}
					/>
				</Link>
			) : (
				<Link to={`/anime/${anime.id}`} state={detailState} className="anime-card-cover-fallback">
					<span>No Cover</span>
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
