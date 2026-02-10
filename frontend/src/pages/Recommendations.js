/**
 * Recommendations Page — Shows anime suggestions based on user's list.
 *
 * Features:
 * - Fetches recommendations on mount (GET /api/users/recommendations)
 * - Refresh button to get new suggestions
 * - "Add to List" button on each card
 * - "Not Interested" button to blacklist a show
 * - Manage Blacklist modal to view/remove hidden anime
 * - Loading and error states
 * - Empty state if user has no list entries
 */
import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import axios from 'axios';

function Recommendations() {
    const [recommendations, setRecommendations] = useState([]);
    const [loading, setLoading] = useState(true);
    const [blacklist, setBlacklist] = useState([]);
    const [showBlacklist, setShowBlacklist] = useState(false);
    const [error, setError] = useState('');
    const [message, setMessage] = useState('');
    const { token } = useAuth();

    // Auth header reused across all requests
    const authHeader = { headers: { Authorization: `Bearer ${token}` } };

    // Fetch recommendations from backend
    const fetchRecommendations = async () => {
        setLoading(true);
        setError('');
        setMessage('');

        try {
            const { data } = await axios.get('/api/users/recommendations', authHeader);
            setRecommendations(data);
        } catch (err) {
            setError('Failed to load recommendations.');
        } finally {
            setLoading(false);
        }
    };

    // Fetch on mount
    useEffect(() => {
        fetchRecommendations();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    // Add anime to user's list (same pattern as Search.js)
    const handleAddToList = async (anime) => {
        setMessage('');
        setError('');

        try {
            await axios.post('/api/users/list', {
                anilistId: anime.id,
                status: 'PLAN_TO_WATCH',
                title: anime.title.english || anime.title.romaji,
                coverImage: anime.coverImage?.large,
                genres: anime.genres?.join(',')
            }, authHeader);

            setMessage(`Added "${anime.title.english || anime.title.romaji}" to your list!`);
            // Remove from displayed recommendations
            setRecommendations(prev => prev.filter(a => a.id !== anime.id));
        } catch (err) {
            setError(err.response?.data?.error || 'Failed to add to list');
        }
    };

    // Blacklist anime — hide from future recommendations
    const handleBlacklist = async (anime) => {
        setMessage('');
        setError('');

        try {
            await axios.post('/api/users/recommendations/blacklist',
                { anilistId: anime.id, title: anime.title.english || anime.title.romaji, coverImage: anime.coverImage?.large },
                authHeader
            );
            // Remove from displayed recommendations immediately
            setRecommendations(prev => prev.filter(a => a.id !== anime.id));
        } catch (err) {
            setError('Failed to hide anime.');
        }
    };

    const fetchBlacklist = async () => {
        try {
            const { data } = await axios.get('/api/users/recommendations/blacklist', authHeader);
            setBlacklist(data);
        } catch (err) {
            setError('Failed to load blacklist.');
        }
    };

    const handleRemoveFromBlacklist = async (id) => {
        try {
            await axios.delete(`/api/users/recommendations/blacklist/${id}`, authHeader);
            setBlacklist(prev => prev.filter(item => item.id !== id));
        } catch (err) {
            setError('Failed to remove from blacklist.');
        }
    };

    if (loading) return <div className="loading">Loading recommendations...</div>;

    return (
        <div className="page">
            <h1>Recommended For You</h1>

            {/* Refresh and Manage Blacklist buttons side by side */}
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
                <button onClick={fetchRecommendations} className="refresh-btn">
                    Refresh
                </button>
                <button
                    className="refresh-btn"
                    onClick={() => {
                        setShowBlacklist(!showBlacklist);
                        if (!showBlacklist) fetchBlacklist();
                    }}
                >
                    Manage Blacklist
                </button>
            </div>

            {error && <p className="error-message">{error}</p>}
            {message && <p className="success-message">{message}</p>}

            {recommendations.length === 0 && !error ? (
                <div className="empty-state">
                    <p>No recommendations yet.</p>
                    <p>Add some anime to <a href="/mylist">your list</a> and rate them to get personalized suggestions!</p>
                </div>
            ) : (
                <div className="card-grid">
                    {recommendations.map((anime) => (
                        <div key={anime.id} className="anime-card">
                            {anime.coverImage && (
                                <img src={anime.coverImage.large} alt={anime.title.romaji} />
                            )}
                            <div className="card-body">
                                <h3><Link to={`/anime/${anime.id}`}>{anime.title.english || anime.title.romaji}</Link></h3>
                                <p>{anime.genres && anime.genres.join(', ')}</p>
                                <p>
                                    Ep: {anime.episodes || '?'} | Score: <span className="score">{anime.averageScore || '?'}</span>/100
                                </p>
                                <button onClick={() => handleAddToList(anime)}>
                                    Add to List
                                </button>
                                <button className="blacklist-btn" onClick={() => handleBlacklist(anime)}>
                                    Not Interested
                                </button>
                            </div>
                        </div>
                    ))}
                </div>
            )}

            {/* Blacklist modal overlay */}
            {showBlacklist && (
                <div className="modal-overlay" onClick={() => setShowBlacklist(false)}>
                    <div className="modal" onClick={(e) => e.stopPropagation()}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
                            <h2>Hidden Anime</h2>
                            <button className="delete-btn" onClick={() => setShowBlacklist(false)}>Close</button>
                        </div>
                        {blacklist.length === 0 ? (
                            <p>No hidden anime.</p>
                        ) : (
                            <div className="blacklist-cards">
                                {blacklist.map(item => (
                                    <div key={item.id} className="blacklist-card">
                                        {item.coverImage && (
                                            <img src={item.coverImage} alt={item.title} />
                                        )}
                                        <div className="blacklist-card-info">
                                            <h3><Link to={`/anime/${item.anilistId}`}>{item.title || `AniList #${item.anilistId}`}</Link></h3>
                                            <button className="delete-btn" onClick={() => handleRemoveFromBlacklist(item.id)}>
                                                Remove
                                            </button>
                                        </div>
                                    </div>
                                ))}
                            </div>
                        )}
                    </div>
                </div>
            )}
        </div>
    );
}

export default Recommendations;
