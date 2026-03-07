import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

function Home() {
	const { isLoggedIn, username } = useAuth();
	const greeting = isLoggedIn
		? `Welcome back, ${username || 'there'}`
		: 'Find your next anime';

	return (
		<div className="page">
			<div className="home">
				<section className="home-hero">
					<p className="home-badge">{greeting}</p>
					<h1>AniRec</h1>
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
				</section>

				<section className="home-quick-grid">
					<Link
						to="/smart-rec"
						state={{ prefillMode: 'semantic', prefillContext: 'comedic sports anime with strong team chemistry' }}
						className="home-quick-card"
					>
						<h3>Smart Search</h3>
						<p>Start with a natural-language query and get semantic recommendations.</p>
					</Link>

					<Link
						to="/smart-rec"
						state={{ prefillMode: 'similar' }}
						className="home-quick-card"
					>
						<h3>Similar Shows</h3>
						<p>Pick seed anime and find titles with close style and theme overlap.</p>
					</Link>

					{isLoggedIn ? (
						<Link
							to="/smart-rec"
							state={{ prefillMode: 'cf' }}
							className="home-quick-card"
						>
							<h3>For You</h3>
							<p>Use collaborative filtering from your list history and feedback signals.</p>
						</Link>
					) : (
						<Link to="/search" className="home-quick-card">
							<h3>Explore Catalog</h3>
							<p>Browse titles and build your list to unlock personalized recommendations.</p>
						</Link>
					)}
				</section>

			</div>
		</div>
	);
}

export default Home;
