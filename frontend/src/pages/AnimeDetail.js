/**
 * AnimeDetail Page — Shows full info about an anime from AniList.
 *
 * Uses React Router's useParams() to read the anime ID from the URL.
 * Example: /anime/20 → useParams() returns { id: "20" }
 *
 * Fetches fresh data from AniList via our backend's GET /api/anime/{id}
 * so the info is always up-to-date (not stored in our DB).
 */
import { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import axios from 'axios';

function AnimeDetail() {
	// useParams reads :id from the route path "/anime/:id"
	const { id } = useParams();

	const [anime, setAnime] = useState(null);
	const [loading, setLoading] = useState(true);
	const [error, setError] = useState('');

	useEffect(() => {
		async function fetchAnime() {
			try {
				const { data } = await axios.get(`/api/anime/${id}`);
				setAnime(data);
			} catch (err) {
				setError('Anime not found');
			} finally {
				setLoading(false);
			}
		}

		fetchAnime();
	}, [id]);

	if (loading) return <div className="page"><p>Loading...</p></div>;
	if (error) return <div className="page"><p className="error-message">{error}</p></div>;
	if (!anime) return <div className="page"><p>Anime not found</p></div>;

	return (
		<div className="page">
			<div className="anime-detail">

				{anime.coverImage && (
					<img src={anime.coverImage.large} alt={anime.title.romaji} />
				)}

				<div className="anime-info">
					<h1>{anime.title.english || anime.title.romaji}</h1>

					{/* Show romaji subtitle if English title exists */}
					{anime.title.english && anime.title.romaji && (
						<p><em>{anime.title.romaji}</em></p>
					)}

					<p><strong>Score:</strong> {anime.averageScore || '?'}/100</p>
					<p><strong>Episodes:</strong> {anime.episodes || '?'}</p>
					<p><strong>Status:</strong> {anime.status}</p>
					<p><strong>Genres:</strong> {anime.genres?.join(', ')}</p>

					{/* Description from AniList contains HTML tags — dangerouslySetInnerHTML renders them */}
					{anime.description && (
						<div dangerouslySetInnerHTML={{ __html: anime.description }} />
					)}

					{/* Link to the anime's AniList page */}
					<p>
						<a
							href={`https://anilist.co/anime/${anime.id}`}
							target="_blank"
							rel="noopener noreferrer"
						>
							View on AniList
						</a>
					</p>
				</div>

			</div>
		</div>
	);
}

export default AnimeDetail;