/**
 * NavBar — Navigation bar shown on every page.
 *
 * Shows different links based on login state.
 * Highlights the currently active nav link.
 */
import { Link, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

function NavBar() {
	const { isLoggedIn, logout, username } = useAuth();
	const location = useLocation();

	const linkClass = (path) => location.pathname === path ? 'active' : '';

	return (
		<nav className="navbar">
			<Link to="/" className="navbar-brand">AniRec</Link>

			<div className="navbar-links">
				<Link to="/search" className={linkClass('/search')}>Search</Link>
				<Link to="/smart-rec" className={linkClass('/smart-rec')}>Smart Recs</Link>

				{isLoggedIn ? (
					<>
						<Link to="/mylist" className={linkClass('/mylist')}>My List</Link>
						<span className="navbar-user">Hi, {username || 'User'}</span>
						<button onClick={logout}>Logout</button>
					</>
				) : (
					<>
						<Link to="/login" className={linkClass('/login')}>Login</Link>
						<Link to="/register" className={linkClass('/register')}>Register</Link>
					</>
				)}
			</div>
		</nav>
	);
}

export default NavBar;
