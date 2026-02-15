import axios from 'axios';

// Shared HTTP client for the entire frontend.
// All API modules should use this instance so auth/error behavior is centralized.
function resolveBaseUrl() {
	const configured = process.env.REACT_APP_API_URL;
	if (!configured) return '';

	try {
		const url = new URL(configured);
		// In docker-compose we use "backend" as container DNS.
		// Browsers on the host machine cannot resolve that hostname,
		// so we keep relative URLs and let CRA proxy handle routing.
		if (url.hostname === 'backend') {
			return '';
		}
		return configured;
	} catch {
		return '';
	}
}

export const api = axios.create({
	baseURL: resolveBaseUrl(),
});

let unauthorizedHandler = null;

export function setUnauthorizedHandler(handler) {
	unauthorizedHandler = handler;
}

api.interceptors.request.use((config) => {
	const token = localStorage.getItem('token');
	if (token) {
		config.headers.Authorization = `Bearer ${token}`;
	}
	return config;
});

api.interceptors.response.use(
	(response) => response,
	(error) => {
		if (error.response?.status === 401 && typeof unauthorizedHandler === 'function') {
			unauthorizedHandler();
		}
		return Promise.reject(error);
	}
);

export function getApiError(error, fallback = 'Request failed') {
	return error?.response?.data?.error || fallback;
}
