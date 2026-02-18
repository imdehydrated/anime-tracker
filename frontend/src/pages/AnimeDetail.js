/**
 * AnimeDetail Page — Shows full info about an anime from AniList.
 *
 * Reads the anime ID from the URL via useParams() (e.g., /anime/20).
 * Fetches fresh data from AniList via our backend's GET /api/anime/{id}.
 * Layout: cover image on the left, info on the right (flex row).
 * Includes "Add to List" button and back navigation.
 */
import { useState, useEffect } from 'react';
import { Link, useParams, useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useAddToList } from '../hooks/useAddToList';
import { getApiError } from '../api/client';
import { getAnimeById } from '../api/animeApi';
import { getUserList } from '../api/listApi';

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
	const [loading, setLoading] = useState(!animeFromRoute);
	const [fetchError, setFetchError] = useState('');
	const [onList, setOnList] = useState(false);

	useEffect(() => {
		let isCancelled = false;

		async function fetchAnime() {
			try {
				const data = await getAnimeById(id);
				if (!isCancelled) {
					setAnime(data);
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

		if (animeFromRoute) {
			setAnime(animeFromRoute);
			setFetchError('');
			setLoading(false);
		} else {
			setAnime(null);
			setLoading(true);
			fetchAnime();
		}

		// Check if anime is already on user's list
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

	if (loading) return <div className="page"><p className="loading">Loading...</p></div>;
	if (fetchError && !anime) return <div className="page"><p className="error-message">{fetchError}</p></div>;
	if (!anime) return <div className="page"><p className="error-message">Anime not found</p></div>;

	return (
		<div className="page">
			<button className="back-btn" onClick={() => navigate(-1)}>Back</button>

			<div className="anime-detail">
				{anime.coverImage && (
					<img src={anime.coverImage.large} alt={anime.title.romaji} />
				)}

				<div className="anime-info">
					<h1>{anime.title.english || anime.title.romaji}</h1>

					{anime.title.english && anime.title.romaji && (
						<p><em>{anime.title.romaji}</em></p>
					)}

					<p><strong>Score:</strong> {anime.averageScore || '?'}/100</p>
					<p><strong>Episodes:</strong> {anime.episodes || '?'}</p>
					<p><strong>Status:</strong> {anime.status}</p>
					<p><strong>Genres:</strong> {anime.genres?.join(', ')}</p>

					{anime.description && (
						<div className="anime-description" dangerouslySetInnerHTML={{ __html: anime.description }} />
					)}

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
