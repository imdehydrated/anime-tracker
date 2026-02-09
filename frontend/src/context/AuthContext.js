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
 */
import { createContext, useContext, useState } from "react";

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
 * AuthProvider — wraps the app and provides auth state to all children.
 *
 * @param {object} children — all child components (the entire app)
 */
export function AuthProvider({ children }) {
  // Initialize token from localStorage (survives page refresh)
  // If no token saved, this will be null (logged out state)
  const [token, setToken] = useState(localStorage.getItem('token'));

  // Login: save token to both React state AND localStorage
  // React state triggers re-renders, localStorage persists across refreshes
  const login = (newToken) => {
    localStorage.setItem('token', newToken);
    setToken(newToken);
  };

  // Logout: clear token from both React state AND localStorage
  const logout = () => {
    localStorage.removeItem('token');
    setToken(null);
  };

  // Convert token to boolean: null → false, "eyJ..." → true
  const isLoggedIn = !!token;

  // Provider makes these values available to any child component via useAuth()
  return (
    <AuthContext.Provider value={{ token, login, logout, isLoggedIn }}>
      {children}
    </AuthContext.Provider>
  );
}
