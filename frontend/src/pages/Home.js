import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getPopularAnime } from '../api/animeApi';
import { useAuth } from '../context/AuthContext';

function Home() {
	const { isLoggedIn, username } = useAuth();
	const [featured, setFeatured] = useState([]);
	const greeting = isLoggedIn
		? `Welcome back, ${username || 'there'}`
		: 'Find your next anime';

	useEffect(() => {
		getPopularAnime(16).then(setFeatured).catch(() => { });
	}, []);

	return (
		<div className="home-page">
			<section className="home-hero">
				<div className="container home-hero-inner">
					<p className="home-badge">{greeting}</p>
					<h1>Ani<span>Rec</span></h1>
					<p className="home-subtitle">
						{isLoggedIn
							? 'Use smart search, similar seeds, and your list taste profile to get better recommendations.'
							: 'Search and discover recommendations with semantic and similar-show modes.'}
					</p>

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
			</section>

			<div className="container home-page-content">
				<section className="home-quick-grid">
					<Link
						to="/smart-rec"
						state={{ prefillMode: 'semantic', prefillContext: 'comedic sports anime with strong team chemistry' }}
						className="home-quick-card"
					>
						<span className="home-quick-card-icon">01</span>
						<h3>Smart Search</h3>
						<p>Start with a natural-language query and get semantic recommendations.</p>
					</Link>

					<Link
						to="/smart-rec"
						state={{ prefillMode: 'similar' }}
						className="home-quick-card"
					>
						<span className="home-quick-card-icon">02</span>
						<h3>Similar Shows</h3>
						<p>Pick seed anime and find titles with close style and theme overlap.</p>
					</Link>

					{isLoggedIn ? (
						<Link
							to="/smart-rec"
							state={{ prefillMode: 'cf' }}
							className="home-quick-card"
						>
							<span className="home-quick-card-icon">03</span>
							<h3>For You</h3>
							<p>Use collaborative filtering from your list history and feedback signals.</p>
						</Link>
					) : (
						<Link to="/search" className="home-quick-card">
							<span className="home-quick-card-icon">03</span>
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
