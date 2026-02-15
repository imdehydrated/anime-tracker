import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

// Route guard for pages that require an authenticated user.
function RequireAuth({ children }) {
	const { isLoggedIn } = useAuth();
	const location = useLocation();

	if (!isLoggedIn) {
		return <Navigate to="/login" state={{ from: location }} replace />;
	}

	return children;
}

export default RequireAuth;
