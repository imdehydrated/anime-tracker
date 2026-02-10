/**
 * AnimeDetail Page — Shows full info about an anime from AniList.
 *
 * Reads the anime ID from the URL via useParams() (e.g., /anime/20).
 * Fetches fresh data from AniList via our backend's GET /api/anime/{id}.
 * Layout: cover image on the left, info on the right (flex row).
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

	// Fetch anime details on mount (or when ID changes)
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

	// Loading and error states with styled classes
	if (loading) return <div className="page"><p className="loading">Loading...</p></div>;
	if (error) return <div className="page"><p className="error-message">{error}</p></div>;
	if (!anime) return <div className="page"><p className="error-message">Anime not found</p></div>;

	return (
		<div className="page">
			{/* Flex layout: cover image left, info right */}
			<div className="anime-detail">

				{/* Cover image — fixed width, rounded corners */}
				{anime.coverImage && (
					<img src={anime.coverImage.large} alt={anime.title.romaji} />
				)}

				{/* Info section — title, metadata, description, external link */}
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

					{/* Description from AniList contains HTML — render it safely */}
					{anime.description && (
						<div dangerouslySetInnerHTML={{ __html: anime.description }} />
					)}

					{/* External link to AniList — opens in new tab */}
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
	);
}

export default AnimeDetail;