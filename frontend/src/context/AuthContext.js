/**
 * AuthContext — Global authentication state for the app.
 *
 * Think of this like Spring's SecurityContextHolder — it makes the current
 * user's auth info (JWT token) available to any component without passing
 * it through props.
 *
 * How it works:
 * 1. AuthProvider wraps the entire app (in App.js)
 * 2. Any component can call useAuth() to access { token, login, logout, isLoggedIn }
 * 3. Token is persisted in localStorage so it survives page refreshes
 * 4. Expired tokens are auto-cleared on app load
 * 5. 401 API responses trigger automatic logout
 */
import { createContext, useContext, useState, useEffect, useCallback } from "react";
import axios from "axios";

// Create the context — a "container" for auth data
const AuthContext = createContext();

/**
 * Custom hook — shortcut so components can write:
 *   const { token, isLoggedIn } = useAuth();
 * instead of:
 *   const { token, isLoggedIn } = useContext(AuthContext);
 */
export function useAuth() {
  return useContext(AuthContext);
}

/**
 * Decode a JWT and check if it's expired.
 * JWTs are base64url-encoded JSON — we only need the payload (middle segment).
 * Returns true if the token is expired or unparseable.
 */
function isTokenExpired(token) {
  try {
    const payload = JSON.parse(atob(token.split('.')[1]));
    // exp is in seconds, Date.now() is in milliseconds
    return payload.exp * 1000 < Date.now();
  } catch {
    return true;
  }
}

/**
 * AuthProvider — wraps the app and provides auth state to all children.
 *
 * @param {object} children — all child components (the entire app)
 */
export function AuthProvider({ children }) {
  // Initialize token from localStorage (survives page refresh)
  // If no token saved, this will be null (logged out state)
  const [token, setToken] = useState(() => {
    const stored = localStorage.getItem('token');
    // Clear expired tokens on app load instead of staying in a broken state
    if (stored && isTokenExpired(stored)) {
      localStorage.removeItem('token');
      return null;
    }
    return stored;
  });

  // Login: save token to both React state AND localStorage
  // React state triggers re-renders, localStorage persists across refreshes
  const login = (newToken) => {
    localStorage.setItem('token', newToken);
    setToken(newToken);
  };

  // Logout: clear token from both React state AND localStorage
  const logout = useCallback(() => {
    localStorage.removeItem('token');
    setToken(null);
  }, []);

  // Axios 401 interceptor — auto-logout when the backend rejects a stale token
  useEffect(() => {
    const interceptor = axios.interceptors.response.use(
      (response) => response,
      (error) => {
        if (error.response?.status === 401) {
          logout();
        }
        return Promise.reject(error);
      }
    );
    // Cleanup: remove interceptor when AuthProvider unmounts
    return () => axios.interceptors.response.eject(interceptor);
  }, [logout]);

  // Auto-refresh when token expires while the user is on the page
  useEffect(() => {
    if (!token) return;

    try {
      const payload = JSON.parse(atob(token.split('.')[1]));
      const msUntilExpiry = payload.exp * 1000 - Date.now();

      if (msUntilExpiry <= 0) {
        // Already expired — logout and refresh immediately
        logout();
        window.location.reload();
        return;
      }

      const timer = setTimeout(() => {
        logout();
        window.location.reload();
      }, msUntilExpiry);

      return () => clearTimeout(timer);
    } catch {
      // Unparseable token — clear it
      logout();
    }
  }, [token, logout]);

  // Convert token to boolean: null → false, "eyJ..." → true
  const isLoggedIn = !!token;

  // Provider makes these values available to any child component via useAuth()
  return (
    <AuthContext.Provider value={{ token, login, logout, isLoggedIn }}>
      {children}
    </AuthContext.Provider>
  );
}