import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getPopularAnime } from '../api/animeApi';
import { useAuth } from '../context/AuthContext';

const HERO_FALLBACK_CARDS = [
	{ eyebrow: 'Popular now', title: 'Catalog-ranked picks' },
	{ eyebrow: 'Taste-aware', title: 'List-driven discovery' },
	{ eyebrow: 'Query-first', title: 'Describe the vibe' },
	{ eyebrow: 'Track + refine', title: 'Build your anime profile' },
];

function Home() {
	const { isLoggedIn, username } = useAuth();
	const [featured, setFeatured] = useState([]);
	const greeting = isLoggedIn
		? `Welcome back, ${username || 'there'}`
		: 'Find your next anime';
	const heroCards = featured.slice(0, 4);

	useEffect(() => {
		getPopularAnime(16).then(setFeatured).catch(() => { });
	}, []);

	return (
		<div className="home-page">
			<section className="home-hero">
				<div className="container home-hero-inner">
					<div className="home-hero-copy fade-in-up">
						<p className="home-badge">{greeting}</p>
						<h1>Ani<span>Rec</span> makes anime discovery feel curated.</h1>
						<p className="home-subtitle">
							{isLoggedIn
								? 'Use smart search, similar seeds, and your own list signals to move from browsing to cleaner, faster recommendations.'
								: 'Search by title, mood, or franchise trail, then turn the results into a list that sharpens every recommendation.'}
						</p>

						<div className="home-hero-highlights">
							<span className="home-hero-highlight">Semantic search that understands themes</span>
							<span className="home-hero-highlight">Seed-based similar discovery</span>
							<span className="home-hero-highlight">List tracking, import, and feedback loops</span>
						</div>

						<div className="home-links">
							{isLoggedIn ? (
								<>
									<Link to="/mylist" className="btn-primary">View My List</Link>
									<Link to="/smart-rec" className="btn-outline">Open SmartRec</Link>
								</>
							) : (
								<>
									<Link to="/login" className="btn-primary">Login</Link>
									<Link to="/register" className="btn-outline">Register</Link>
								</>
							)}
						</div>
					</div>

					<div className="home-hero-art fade-in-up fade-delay-1" aria-label="Featured popular anime">
						{Array.from({ length: 4 }, (_, index) => {
							const anime = heroCards[index];
							if (!anime) {
								const fallback = HERO_FALLBACK_CARDS[index];
								return (
									<div key={fallback.title} className="home-hero-art-card is-placeholder">
										<span className="home-hero-art-card-eyebrow">{fallback.eyebrow}</span>
										<strong>{fallback.title}</strong>
									</div>
								);
							}

							const title = anime?.title?.english || anime?.title?.romaji || anime?.title?.nativeTitle || 'Unknown title';
							return (
								<Link
									key={anime.id}
									to={`/anime/${anime.id}`}
									state={{ anime }}
									className="home-hero-art-card"
								>
									<img
										src={anime.coverImage?.large}
										alt={title}
										loading="lazy"
									/>
									<div className="home-hero-art-card-overlay">
										<span className="home-hero-art-card-eyebrow">Popular</span>
										<strong>{title}</strong>
									</div>
								</Link>
							);
						})}
					</div>
				</div>
			</section>

			<div className="container home-page-content">
				<section className="home-quick-grid">
					<Link
							to="/smart-rec"
							state={{ prefillMode: 'semantic', prefillContext: 'comedic sports anime with strong team chemistry' }}
							className="home-quick-card fade-in-up fade-delay-1"
						>
							<span className="home-quick-card-accent" aria-hidden="true" />
							<span className="home-quick-card-icon">01</span>
							<span className="home-quick-card-badge">Semantic Query</span>
							<h3>Smart Search</h3>
							<p>Start with a natural-language query and get semantic recommendations.</p>
						</Link>

						<Link
							to="/smart-rec"
							state={{ prefillMode: 'similar' }}
							className="home-quick-card fade-in-up fade-delay-2"
						>
							<span className="home-quick-card-accent" aria-hidden="true" />
							<span className="home-quick-card-icon">02</span>
							<span className="home-quick-card-badge">Seed Match</span>
							<h3>Similar Shows</h3>
							<p>Pick seed anime and find titles with close style and theme overlap.</p>
						</Link>

						{isLoggedIn ? (
							<Link
								to="/smart-rec"
								state={{ prefillMode: 'cf' }}
								className="home-quick-card fade-in-up fade-delay-3"
							>
								<span className="home-quick-card-accent" aria-hidden="true" />
								<span className="home-quick-card-icon">03</span>
								<span className="home-quick-card-badge">Personalized</span>
								<h3>For You</h3>
								<p>Use collaborative filtering from your list history and feedback signals.</p>
							</Link>
						) : (
							<Link to="/search" className="home-quick-card fade-in-up fade-delay-3">
								<span className="home-quick-card-accent" aria-hidden="true" />
								<span className="home-quick-card-icon">03</span>
								<span className="home-quick-card-badge">Browse Catalog</span>
								<h3>Explore Catalog</h3>
								<p>Browse titles and build your list to unlock personalized recommendations.</p>
							</Link>
						)}
					</section>

				{featured.length > 0 && (
					<section className="home-featured">
						<p className="home-section-label">Popular</p>
						<div className="home-featured-strip">
							{featured.map((anime) => {
								const title = anime?.title?.english || anime?.title?.romaji || anime?.title?.nativeTitle || 'Unknown title';
								return (
									<Link
										key={anime.id}
										to={`/anime/${anime.id}`}
										state={{ anime }}
										className="home-featured-card"
									>
										<img
											src={anime.coverImage?.large}
											alt={title}
											loading="lazy"
										/>
										<div className="home-featured-card-overlay">
											<span>{title}</span>
										</div>
									</Link>
								);
							})}
						</div>
					</section>
				)}
			</div>
		</div>
	);
}

export default Home;
