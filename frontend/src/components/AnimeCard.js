import { Link } from 'react-router-dom';

function AnimeCard({ anime, children }) {
	const title = anime.title.english || anime.title.romaji;

	return (
		<div className="anime-card">
			{anime.coverImage && (
				<Link to={`/anime/${anime.id}`}>
					<img src={anime.coverImage.large} alt={anime.title.romaji} />
				</Link>
			)}

			<div className="card-body">
				<h3><Link to={`/anime/${anime.id}`}>{title}</Link></h3>
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