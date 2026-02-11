/**
 * NavBar — Navigation bar shown on every page.
 *
 * Shows different links based on login state.
 * Highlights the currently active nav link.
 */
import { Link, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

function NavBar() {
  const { isLoggedIn, logout } = useAuth();
  const location = useLocation();

  // Helper to add "active" class to current page link
  const linkClass = (path) => location.pathname === path ? 'active' : '';

  return (
    <nav className='navbar'>
      <Link to="/" className="navbar-brand">AniRec</Link>

      <div className='navbar-links'>
        <Link to="/search" className={linkClass('/search')}>Search</Link>

        {isLoggedIn ? (
          <>
            <Link to="/mylist" className={linkClass('/mylist')}>My List</Link>
            <Link to="/recommendations" className={linkClass('/recommendations')}>For You</Link>
            <Link to="/smart-rec" className={linkClass('/smart-rec')}>Advanced Recommendations</Link>
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
