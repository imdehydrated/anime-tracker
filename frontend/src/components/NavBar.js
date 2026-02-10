/**
 * NavBar — Navigation bar shown on every page.
 *
 * Shows different links based on login state:
 * - Logged out: Home, Login, Register
 * - Logged in: Home, My List, Logout button
 *
 * Uses <Link> from React Router instead of <a> tags
 * so navigation happens without a full page reload (SPA behavior).
 */
import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

function NavBar() {
  // Get auth state — isLoggedIn controls which links to show,
  // logout is called when the Logout button is clicked
  const { isLoggedIn, logout } = useAuth();

  return (
    <nav className='navbar'>
      {/* Home link always visible */}
      <Link to="/" className="navbar-brand">AniRec</Link>

      <div className='navbar-links'>
        <Link to="/search">Search</Link>

        {/* Conditional links based on auth state */}
        {isLoggedIn ? (
          <>
            {/* <> is a Fragment — groups elements without adding extra DOM nodes */}
            <Link to="/mylist">My List</Link>
            <Link to="/recommendations">For You</Link>
            <button onClick={logout}>Logout</button>
          </>
        ) : (
          <>
            <Link to="/login">Login</Link>
            <Link to="/register">Register</Link>
          </>
        )}
      </div>
    </nav>
  );
}

export default NavBar;
