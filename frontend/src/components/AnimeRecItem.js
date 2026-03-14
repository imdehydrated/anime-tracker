import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';

const RECOMMENDATION_DESCRIPTION_PREVIEW_CHARS = 220;

function normalizeDescription(rawDescription) {
	if (!rawDescription || typeof rawDescription !== 'string') return '';
	const withBreaks = rawDescription
		.replace(/<br\s*\/?>/gi, '\n')
		.replace(/<\/(p|div|h1|h2|h3|h4|h5|h6)>/gi, '\n')
		.replace(/<li[^>]*>/gi, '- ')
		.replace(/<\/li>/gi, '\n');
	let normalized = withBreaks;
	if (typeof document !== 'undefined') {
		const container = document.createElement('div');
		container.innerHTML = withBreaks;
		normalized = container.textContent || '';
	}
	return normalized
		.replace(/\r\n/g, '\n')
		.replace(/\n{3,}/g, '\n\n')
		.replace(/[ \t]{2,}/g, ' ')
		.trim();
}

function AnimeRecItem({ anime, children }) {
	const [coverFailed, setCoverFailed] = useState(false);
	const [descriptionExpanded, setDescriptionExpanded] = useState(false);
	const title = anime.title?.english || anime.title?.romaji || 'Unknown Title';
	const detailState = { anime };
	const coverUrl = typeof anime.coverImage === 'string'
		? anime.coverImage
		: anime.coverImage?.large || anime.coverImage?.medium || '';
	const showCover = !!coverUrl && !coverFailed;
	const normalizedDescription = useMemo(
		() => normalizeDescription(anime.description),
		[anime.description],
	);
	const descriptionNeedsCollapse = normalizedDescription.length > RECOMMENDATION_DESCRIPTION_PREVIEW_CHARS;
	const description = useMemo(() => {
		if (!normalizedDescription) return 'No description available.';
		if (descriptionExpanded || !descriptionNeedsCollapse) return normalizedDescription;
		return `${normalizedDescription.slice(0, RECOMMENDATION_DESCRIPTION_PREVIEW_CHARS).trim()}...`;
	}, [descriptionExpanded, descriptionNeedsCollapse, normalizedDescription]);
	const recommendationReason = anime.recommendationReason;

	return (
		<div className="anime-rec-item">
			{showCover ? (
				<Link to={`/anime/${anime.id}`} state={detailState} className="anime-rec-item-image">
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
				<Link to={`/anime/${anime.id}`} state={detailState} className="anime-rec-item-image-fallback">
					<span>No Cover</span>
				</Link>
			)}

			<div className="anime-rec-item-content">
				<h3><Link to={`/anime/${anime.id}`} state={detailState}>{title}</Link></h3>

				<div className="anime-rec-item-meta">
					{anime.genres && <span>{anime.genres.join(', ')}</span>}
					<span>Ep: {anime.episodes || '?'} | Score: <span className="score">{anime.averageScore || '?'}</span>/100</span>
				</div>

				{recommendationReason && (
					<p className="anime-rec-item-reason rec-reason">{recommendationReason}</p>
				)}

				<p className="anime-rec-item-desc">{description}</p>
				{descriptionNeedsCollapse && (
					<button
						type="button"
						className="anime-rec-item-desc-toggle"
						onClick={() => setDescriptionExpanded((prev) => !prev)}
					>
						{descriptionExpanded ? 'Show less' : 'Show more'}
					</button>
				)}

				<div className="anime-rec-item-actions">
					{children}
				</div>
			</div>
		</div>
	);
}

export default AnimeRecItem;
