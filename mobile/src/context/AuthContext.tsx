import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { setAuthToken, setUnauthorizedHandler } from '../api/client';
import { getTokenExpiryMs, getUsernameFromToken, isTokenExpired } from '../utils/jwt';
import { deleteStoredToken, getStoredToken, setStoredToken } from '../utils/tokenStorage';

const TOKEN_KEY = 'anirec_token';

interface AuthContextValue {
	token: string | null;
	isLoggedIn: boolean;
	isLoading: boolean;
	username: string | null;
	login: (newToken: string) => Promise<void>;
	logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function useAuth() {
	const value = useContext(AuthContext);
	if (!value) {
		throw new Error('useAuth must be used within an AuthProvider');
	}
	return value;
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
	const [token, setToken] = useState<string | null>(null);
	const [isLoading, setIsLoading] = useState(true);

	const logout = useCallback(async () => {
		await deleteStoredToken(TOKEN_KEY);
		setToken(null);
		setAuthToken(null);
	}, []);

	const login = useCallback(async (newToken: string) => {
		await setStoredToken(TOKEN_KEY, newToken);
		setToken(newToken);
		setAuthToken(newToken);
	}, []);

	useEffect(() => {
		let isMounted = true;

		async function hydrateAuth() {
			try {
				const stored = await getStoredToken(TOKEN_KEY);
				if (!isMounted) return;
				if (stored && !isTokenExpired(stored)) {
					setToken(stored);
					setAuthToken(stored);
					return;
				}
				if (stored) {
					await deleteStoredToken(TOKEN_KEY);
				}
				setToken(null);
				setAuthToken(null);
			} finally {
				if (isMounted) {
					setIsLoading(false);
				}
			}
		}

		void hydrateAuth();
		return () => {
			isMounted = false;
		};
	}, []);

	useEffect(() => {
		setUnauthorizedHandler(() => {
			void logout();
		});
		return () => setUnauthorizedHandler(null);
	}, [logout]);

	useEffect(() => {
		if (!token) return;
		const expiryMs = getTokenExpiryMs(token);
		if (!expiryMs) {
			void logout();
			return;
		}

		const msUntilExpiry = expiryMs - Date.now();
		if (msUntilExpiry <= 0) {
			void logout();
			return;
		}

		const timer = setTimeout(() => {
			void logout();
		}, msUntilExpiry);

		return () => clearTimeout(timer);
	}, [token, logout]);

	const value = useMemo<AuthContextValue>(() => ({
		token,
		isLoggedIn: Boolean(token),
		isLoading,
		username: getUsernameFromToken(token),
		login,
		logout,
	}), [token, isLoading, login, logout]);

	return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
