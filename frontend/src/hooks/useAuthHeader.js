import { useAuth } from '../context/AuthContext';

// Shared hook for building the JWT auth header used across protected API calls
export function useAuthHeader() {
	const { token } = useAuth();
	if (!token) {
		return {};
	}
	return { headers: { Authorization: `Bearer ${token}` } };
}
