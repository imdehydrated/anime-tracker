/**
 * Home Page — Landing page with call-to-action buttons.
 *
 * Shows different buttons based on auth state:
 * - Logged out: "Login" (solid) and "Register" (outline) buttons
 * - Logged in: "View My List" (solid) and "Search Anime" (outline) buttons
 */
import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

function Home() {
	const { isLoggedIn } = useAuth();

	return (
		<div className="page">
			<div className="home">
				<h1>AniRec</h1>
				<p>Track your anime, discover new favorites, and never lose your place.</p>

				{/* Action buttons — styled as solid + outline pair */}
				<div className="home-links">
					{isLoggedIn ? (
						<>
							<Link to="/mylist">View My List</Link>
							<Link to="/search">Search Anime</Link>
						</>
					) : (
						<>
							<Link to="/login">Login</Link>
							<Link to="/register">Register</Link>
						</>
					)}
				</div>
			</div>
		</div>
	);
}

export default Home;
