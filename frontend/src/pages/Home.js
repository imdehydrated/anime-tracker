/**
 * Home Page — Landing page for the app.
 *
 * Shows different content based on login state:
 * - Logged out: "Login | Register" links
 * - Logged in: "View My List" link
 */
import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

function Home() {
  // Get login state from AuthContext
  const { isLoggedIn } = useAuth();

  return (
    <div className="page">
      <h1>Welcome to AniRec</h1>
      <p>Your personal anime list and recommendation app.</p>

      {/* Conditional rendering — show different links based on auth state */}
      {isLoggedIn ? (
        <div>
          <Link to="/mylist">View My List</Link>
        </div>
      ) : (
        <div>
          <p>Get started by creating an account or logging in.</p>
          <Link to="/login">Login</Link> | <Link to="/register">Register</Link>
        </div>
      )}
    </div>
  );
}

export default Home;
