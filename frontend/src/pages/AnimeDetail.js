/**
 * AnimeDetail Page — Shows full info about an anime from AniList.
 *
 * Reads the anime ID from the URL via useParams() (e.g., /anime/20).
 * Fetches fresh data from AniList via our backend's GET /api/anime/{id}.
 * Layout: cover image on the left, info on the right (flex row).
 * Includes "Add to List" button and back navigation.
 */
import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useAuthHeader } from '../hooks/useAuthHeader';
import { useAddToList } from '../hooks/useAddToList';
import axios from 'axios';

function AnimeDetail() {
	const { id } = useParams();
	const navigate = useNavigate();
	const { isLoggedIn } = useAuth();
	const authHeader = useAuthHeader();
	const { addToList, message, error } = useAddToList();

	const [anime, setAnime] = useState(null);
	const [loading, setLoading] = useState(true);
	const [fetchError, setFetchError] = useState('');
	const [onList, setOnList] = useState(false);

	useEffect(() => {
		async function fetchAnime() {
			try {
				const { data } = await axios.get(`/api/anime/${id}`);
				setAnime(data);
			} catch (err) {
				setFetchError('Anime not found');
			} finally {
				setLoading(false);
			}
		}
		fetchAnime();

		// Check if anime is already on user's list
		if (isLoggedIn) {
			axios.get('/api/users/list', authHeader)
				.then(({ data }) => {
					if (data.some(entry => entry.anilistId === parseInt(id))) {
						setOnList(true);
					}
				})
				.catch(() => {});
		}
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [id, isLoggedIn]);

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
								<button className="add-btn" onClick={handleAddToList}>Add to List</button>
							)
						) : (
							<p className="login-prompt">
								<a href="/login">Login</a> to add to your list
							</p>
						)}

						<a
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
