import { jwtDecode } from 'jwt-decode';

interface JwtPayload {
	sub?: string;
	exp?: number;
}

function decodeToken(token: string | null): JwtPayload | null {
	if (!token) return null;
	try {
		return jwtDecode<JwtPayload>(token);
	} catch {
		return null;
	}
}

export function isTokenExpired(token: string | null): boolean {
	const payload = decodeToken(token);
	if (!payload?.exp) return true;
	return payload.exp * 1000 < Date.now();
}

export function getUsernameFromToken(token: string | null): string | null {
	const payload = decodeToken(token);
	if (typeof payload?.sub === 'string' && payload.sub.trim().length > 0) {
		return payload.sub.trim();
	}
	return null;
}

export function getTokenExpiryMs(token: string | null): number | null {
	const payload = decodeToken(token);
	if (!payload?.exp) return null;
	return payload.exp * 1000;
}
