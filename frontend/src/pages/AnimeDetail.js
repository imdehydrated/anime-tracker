import { useMemo, useState, useEffect } from 'react';
import { Link, useParams, useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useAddToList } from '../hooks/useAddToList';
import { getApiError } from '../api/client';
import { getAnimeById } from '../api/animeApi';
import { getUserList } from '../api/listApi';

const DESCRIPTION_PREVIEW_CHARS = 900;
const RELATION_TYPE_LABELS = {
	PREQUEL: 'Prequel',
	SEQUEL: 'Sequel',
	PARENT: 'Parent',
	PARENT_STORY: 'Parent Story',
	SIDE_STORY: 'Side Story',
	SPIN_OFF: 'Spin-Off',
	ALTERNATIVE: 'Alternative',
	SUMMARY: 'Summary',
	ADAPTATION: 'Adaptation',
	CHARACTER: 'Character',
	OTHER: 'Related',
};

function formatTitle(anime) {
	return anime?.title?.english || anime?.title?.romaji || anime?.title?.nativeTitle || 'Unknown title';
}

function normalizeDescription(rawDescription) {
	if (!rawDescription || typeof rawDescription !== 'string') return '';
	const withBreaks = rawDescription
		.replace(/<br\s*\/?>/gi, '\n')
		.replace(/<\/(p|div|h1|h2|h3|h4|h5|h6)>/gi, '\n')
		.replace(/<li[^>]*>/gi, '- ')
		.replace(/<\/li>/gi, '\n');
	let normalized = withBreaks;
	if (typeof document !== 'undefined') {
		const decodeContainer = document.createElement('div');
		decodeContainer.innerHTML = withBreaks;
		normalized = decodeContainer.textContent || '';
	}
	return normalized
		.replace(/\r\n/g, '\n')
		.replace(/\n{3,}/g, '\n\n')
		.replace(/[ \t]{2,}/g, ' ')
		.trim();
}

function normalizeRelationTypeLabel(rawType) {
	if (!rawType || typeof rawType !== 'string') return 'Related';
	const normalized = rawType.trim().toUpperCase().replace(/\s+/g, '_');
	return RELATION_TYPE_LABELS[normalized] || normalized.replace(/_/g, ' ');
}

function relationSortKey(rawType) {
	const normalized = (rawType || '').toUpperCase().replace(/\s+/g, '_');
	const order = {
		PARENT_STORY: 0,
		PARENT: 1,
		PREQUEL: 2,
		SEQUEL: 3,
		SIDE_STORY: 4,
		SPIN_OFF: 5,
		ALTERNATIVE: 6,
		SUMMARY: 7,
		ADAPTATION: 8,
		CHARACTER: 9,
		OTHER: 10,
	};
	return order[normalized] ?? 99;
}

function buildRelationItems(anime) {
	const relations = Array.isArray(anime?.relations) ? anime.relations : [];
	const uniqueById = new Map();
	relations.forEach((relation) => {
		const relationId = Number(relation?.id);
		if (!Number.isFinite(relationId) || relationId <= 0 || relationId === Number(anime?.id)) {
			return;
		}
		if (uniqueById.has(relationId)) return;
		const title = relation?.title?.english || relation?.title?.romaji || relation?.title?.nativeTitle || `Anime #${relationId}`;
		uniqueById.set(relationId, {
			id: relationId,
			title,
			relationType: relation?.relationType || 'OTHER',
		});
	});
	return Array.from(uniqueById.values()).sort((a, b) => {
		const relationOrder = relationSortKey(a.relationType) - relationSortKey(b.relationType);
		if (relationOrder !== 0) return relationOrder;
		return a.title.localeCompare(b.title);
	});
}

function clusterBaseTitle(value) {
	if (!value || typeof value !== 'string') return '';
	return value
		.replace(/\(.*?\)|\[.*?\]/g, ' ')
		.replace(/\b(season|part|cour|movie|ova|ona|special|tv|final)\b/gi, ' ')
		.replace(/\b\d+(st|nd|rd|th)?\b/gi, ' ')
		.replace(/\b(ii|iii|iv|v|vi|vii|viii|ix|x)\b/gi, ' ')
		.replace(/[^\p{L}\p{N}\s]/gu, ' ')
		.replace(/\s{2,}/g, ' ')
		.trim();
}

function buildTitleClusterFallback(anime) {
	const titleCandidates = [
		anime?.title?.english,
		anime?.title?.romaji,
		anime?.title?.nativeTitle,
		...(Array.isArray(anime?.synonyms) ? anime.synonyms : []),
	].filter((value) => typeof value === 'string' && value.trim().length > 0);
	const deduped = [];
	const seen = new Set();
	titleCandidates.forEach((title) => {
		const base = clusterBaseTitle(title);
		const key = base.toLowerCase();
		if (base.length < 3 || seen.has(key)) return;
		seen.add(key);
		deduped.push(base);
	});
	return deduped.slice(0, 3);
}

function AnimeDetail() {
	const { id } = useParams();
	const navigate = useNavigate();
	const location = useLocation();
	const { isLoggedIn } = useAuth();
	const { addToList, message, error } = useAddToList();
	const animeFromRoute = location.state?.anime && String(location.state.anime.id) === String(id)
		? location.state.anime
		: null;

	const [anime, setAnime] = useState(animeFromRoute);
	const [loading, setLoading] = useState(true);
	const [fetchError, setFetchError] = useState('');
	const [onList, setOnList] = useState(false);
	const [descriptionExpanded, setDescriptionExpanded] = useState(false);

	const normalizedDescription = useMemo(
		() => normalizeDescription(anime?.description),
		[anime?.description],
	);
	const descriptionNeedsCollapse = normalizedDescription.length > DESCRIPTION_PREVIEW_CHARS;
	const renderedDescription = useMemo(() => {
		if (!normalizedDescription) return '';
		if (descriptionExpanded || !descriptionNeedsCollapse) return normalizedDescription;
		return `${normalizedDescription.slice(0, DESCRIPTION_PREVIEW_CHARS).trim()}...`;
	}, [descriptionExpanded, descriptionNeedsCollapse, normalizedDescription]);

	const relationItems = useMemo(() => buildRelationItems(anime), [anime]);
	const titleClusterFallback = useMemo(() => {
		if (relationItems.length > 0) return [];
		return buildTitleClusterFallback(anime);
	}, [anime, relationItems.length]);

	useEffect(() => {
		let isCancelled = false;

		async function fetchAnime() {
			try {
				const data = await getAnimeById(id);
				if (!isCancelled) {
					setAnime(data);
					setFetchError('');
				}
			} catch (err) {
				if (!isCancelled) {
					setFetchError(getApiError(err, 'Anime not found'));
				}
			} finally {
				if (!isCancelled) {
					setLoading(false);
				}
			}
		}

		setDescriptionExpanded(false);
		if (animeFromRoute) setAnime(animeFromRoute);
		setLoading(true);
		fetchAnime();

		setOnList(false);
		if (isLoggedIn) {
			getUserList()
				.then((data) => {
					if (!isCancelled) {
						setOnList(data.some((entry) => entry.anilistId === parseInt(id, 10)));
					}
				})
				.catch(() => { });
		}

		return () => {
			isCancelled = true;
		};
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [id, isLoggedIn, animeFromRoute]);

	const handleAddToList = async () => {
		const success = await addToList(anime);
		if (success) setOnList(true);
	};

	if (loading && !anime) return <div className="page"><p className="loading">Loading...</p></div>;
	if (fetchError && !anime) return <div className="page"><p className="error-message">{fetchError}</p></div>;
	if (!anime) return <div className="page"><p className="error-message">Anime not found</p></div>;

	return (
		<div className="page">
			<button className="back-btn" onClick={() => navigate(-1)}>Back</button>

			<div className="anime-detail">
				{anime.coverImage?.large && (
					<img src={anime.coverImage.large} alt={formatTitle(anime)} />
				)}

				<div className="anime-info">
					<h1>{formatTitle(anime)}</h1>

					{anime.title?.english && anime.title?.romaji && (
						<p><em>{anime.title.romaji}</em></p>
					)}

					<p><strong>Score:</strong> {anime.averageScore || '?'}/100</p>
					<p><strong>Episodes:</strong> {anime.episodes || '?'}</p>
					<p><strong>Status:</strong> {anime.status}</p>
					<p><strong>Genres:</strong> {anime.genres?.join(', ')}</p>

					{normalizedDescription && (
						<div className="anime-description-block">
							<div className="anime-description">
								{renderedDescription.split('\n').map((line, idx) => (
									<p key={`${idx}-${line.slice(0, 16)}`}>{line}</p>
								))}
							</div>
							{descriptionNeedsCollapse && (
								<button
									type="button"
									className="detail-inline-link"
									onClick={() => setDescriptionExpanded((prev) => !prev)}
								>
									{descriptionExpanded ? 'Show less' : 'Show more'}
								</button>
							)}
						</div>
					)}

					<div className="series-panel">
						<h3>Series Navigation</h3>
						{relationItems.length > 0 ? (
							<div className="series-chip-list">
								{relationItems.map((relation) => (
									<Link
										key={relation.id}
										className="series-chip"
										to={`/anime/${relation.id}`}
									>
										<span className="series-chip-type">{normalizeRelationTypeLabel(relation.relationType)}</span>
										<span className="series-chip-title">{relation.title}</span>
									</Link>
								))}
							</div>
						) : titleClusterFallback.length > 0 ? (
							<div className="series-fallback-list">
								<p className="series-note">
									No explicit relation graph links are available for this entry. Try title-cluster lookup:
								</p>
								<div className="series-chip-list">
									{titleClusterFallback.map((clusterTitle) => (
										<button
											type="button"
											className="series-chip series-chip-button"
											key={clusterTitle}
											onClick={() => navigate('/search', { state: { prefillQuery: clusterTitle } })}
										>
											<span className="series-chip-type">Search</span>
											<span className="series-chip-title">{clusterTitle}</span>
										</button>
									))}
								</div>
							</div>
						) : (
							<p className="series-note">No known related entries for this anime yet.</p>
						)}
					</div>

					{error && <p className="error-message">{error}</p>}
					{message && <p className="success-message">{message}</p>}

					<div className="detail-actions">
						{isLoggedIn ? (
							onList ? (
								<span className="on-list-badge">On Your List</span>
							) : (
								<button className="btn-primary" onClick={handleAddToList}>Add to List</button>
							)
						) : (
							<Link className="btn-primary" to="/login">Login to Add to List</Link>
						)}

						<a
							className="btn-outline"
							href={`https://anilist.co/anime/${anime.id}`}
							target="_blank"
							rel="noopener noreferrer"
						>
							View on AniList
						</a>
					</div>
				</div>
			</div>
		</div>
	);
}

export default AnimeDetail;
