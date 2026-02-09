/**
 * MyList Page — Displays the logged-in user's anime list.
 *
 * Flow:
 * 1. Component mounts → useEffect fires
 * 2. Fetches GET /api/users/list with JWT token in Authorization header
 * 3. Displays entries in a table, or "list is empty" message
 *
 * The JWT token is sent as: Authorization: Bearer <token>
 * This is the same format we used with curl testing.
 */
import { useState, useEffect } from 'react';
import { useAuth } from '../context/AuthContext';
import axios from 'axios';

function MyList() {
  // Get the JWT token from AuthContext — needed for authenticated API calls
  const { token } = useAuth();

  // State for the list data, loading indicator, and error messages
  const [entries, setEntries] = useState([]);     // Array of anime list entries
  const [loading, setLoading] = useState(true);   // Show "Loading..." initially
  const [error, setError] = useState('');

  // useEffect with [token] dependency — fetches list on mount and when token changes
  useEffect(() => {
    async function fetchList() {
      try {
        // GET request with JWT token — same as: curl -H "Authorization: Bearer $TOKEN"
        const { data } = await axios.get('/api/users/list', {
          headers: { Authorization: `Bearer ${token}` }
        });
        setEntries(data);  // Store the response array in state
      } catch (err) {
        setError('Failed to load anime list');
      } finally {
        setLoading(false);  // Runs after both success and error — stops "Loading..." text
      }
    }

    fetchList();
  }, [token]);  // Re-fetch if token changes (e.g., logout then login as different user)

  // Early return — show loading state before data arrives
  if (loading) return <div className="page"><p>Loading...</p></div>;

  return (
    <div className="page">
      <h1>My Anime List</h1>

      {error && <p className="error-message">{error}</p>}

      {/* Conditional rendering — empty list message OR table of entries */}
      {entries.length === 0 ? (
        <p>Your list is empty. Search for anime to add some!</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>AniList ID</th>
              <th>Status</th>
              <th>Score</th>
              <th>Episodes</th>
            </tr>
          </thead>
          <tbody>
            {/* .map() loops through entries — key={entry.id} helps React track changes */}
            {entries.map((entry) => (
              <tr key={entry.id}>
                <td>{entry.anilistId}</td>
                <td>{entry.status}</td>
                <td>{entry.score || '-'}</td>
                <td>{entry.episodesWatched || 0}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

export default MyList;
