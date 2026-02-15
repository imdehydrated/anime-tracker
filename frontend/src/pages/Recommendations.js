/**
 * Recommendations Page — Shows anime suggestions based on user's list.
 *
 * Features:
 * - Fetches recommendations on mount (POST /api/users/recommendations/semantic)
 * - Refresh button to get new suggestions
 * - "Add to List" button swaps to "On Your List" badge after adding
 * - "Not Interested" button to blacklist a show
 * - Manage Blacklist modal to view/remove hidden anime
 * - Loading and error states
 * - Empty state if user has no list entries
 */
import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useAuthHeader } from '../hooks/useAuthHeader';
import { useAddToList } from '../hooks/useAddToList';
import axios from 'axios';

import AnimeRecItem from '../components/AnimeRecItem';

function Recommendations() {
    const [recommendations, setRecommendations] = useState([]);
    const [loading, setLoading] = useState(false);
    const [blacklist, setBlacklist] = useState([]);
    const [showBlacklist, setShowBlacklist] = useState(false);
    const [blacklistSearch, setBlacklistSearch] = useState('');
    const [fetchError, setFetchError] = useState('');
    const [addedIds, setAddedIds] = useState(new Set());

    const { isLoggedIn } = useAuth();
    const authHeader = useAuthHeader();
    const { addToList, message, error, clearMessages, setError } = useAddToList();

    const fetchRecommendations = async () => {
        setLoading(true);
        setFetchError('');
        clearMessages();

        try {
            const { data } = await axios.post('/api/users/recommendations/semantic',
                { useListOnly: true, limit: 15 }, authHeader);
            setRecommendations(data);
        } catch (err) {
            setFetchError('Failed to load recommendations.');
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        if (!isLoggedIn) return;
        fetchRecommendations();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [isLoggedIn]);

    const handleAddToList = async (anime) => {
        const success = await addToList(anime);
        if (success) {
            setAddedIds(prev => new Set([...prev, anime.id]));
        }
    };

    const handleBlacklist = async (anime) => {
        clearMessages();
        setFetchError('');

        try {
            await axios.post('/api/users/recommendations/blacklist',
                { anilistId: anime.id, title: anime.title.english || anime.title.romaji, coverImage: anime.coverImage?.large },
                authHeader
            );
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
    if (!isLoggedIn) {
        return (
            <div className="page">
                <h1>Recommended For You</h1>
                <p className="empty-state">
                    <Link to="/login">Login</Link> to get personalized recommendations from your list.
                </p>
            </div>
        );
    }

    return (
        <div className="page">
            <h1>Recommended For You</h1>

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

            {(fetchError || error) && <p className="error-message">{fetchError || error}</p>}
            {message && <p className="success-message">{message}</p>}

            {recommendations.length === 0 && !fetchError ? (
                <div className="empty-state">
                    <p>No recommendations yet.</p>
                    <p>Add some anime to <Link to="/mylist">your list</Link> and rate them to get personalized suggestions!</p>
                </div>
            ) : (
                <div className="smart-rec-results">
                    {recommendations.map((anime) => (
                        <AnimeRecItem key={anime.id} anime={anime}>
                            {addedIds.has(anime.id) ? (
                                <span className="on-list-badge">On Your List</span>
                            ) : (
                                <>
                                    <button className="btn-primary" onClick={() => handleAddToList(anime)}>
                                        Add to List
                                    </button>
                                    <button className="blacklist-btn" onClick={() => handleBlacklist(anime)}>
                                        Not Interested
                                    </button>
                                </>
                            )}
                        </AnimeRecItem>
                    ))}
                </div>
            )}

            {showBlacklist && (
                <div className="modal-overlay" onClick={() => { setShowBlacklist(false); setBlacklistSearch(''); }}>
                    <div className="modal" onClick={(e) => e.stopPropagation()}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
                            <h2>Hidden Anime</h2>
                            <button className="btn-danger" onClick={() => { setShowBlacklist(false); setBlacklistSearch(''); }}>Close</button>
                        </div>
                        {blacklist.length === 0 ? (
                            <p>No hidden anime.</p>
                        ) : (
                            <>
                                <input
                                    type="text"
                                    className="blacklist-search"
                                    placeholder="Search hidden anime..."
                                    value={blacklistSearch}
                                    onChange={(e) => setBlacklistSearch(e.target.value)}
                                />
                                <div className="blacklist-cards">
                                    {blacklist
                                        .filter(item => (item.title || '').toLowerCase().includes(blacklistSearch.toLowerCase()))
                                        .map(item => (
                                            <div key={item.id} className="blacklist-card">
                                                {item.coverImage && (
                                                    <img src={item.coverImage} alt={item.title} />
                                                )}
                                                <div className="blacklist-card-info">
                                                    <h3><Link to={`/anime/${item.anilistId}`}>{item.title || `AniList #${item.anilistId}`}</Link></h3>
                                                    <button className="btn-danger" onClick={() => handleRemoveFromBlacklist(item.id)}>
                                                        Remove
                                                    </button>
                                                </div>
                                            </div>
                                        ))}
                                </div>
                            </>
                        )}
                    </div>
                </div>
            )}
        </div>
    );
}

export default Recommendations;
