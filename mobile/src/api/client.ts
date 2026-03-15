import axios, { AxiosError, InternalAxiosRequestConfig } from 'axios';

const API_BASE_URL = 'https://d2twcwm8eoud49.cloudfront.net';

let authToken: string | null = null;
let unauthorizedHandler: (() => void) | null = null;

export function setAuthToken(token: string | null) {
	authToken = token;
}

export function setUnauthorizedHandler(handler: (() => void) | null) {
	unauthorizedHandler = handler;
}

export const api = axios.create({
	baseURL: API_BASE_URL,
});

api.interceptors.request.use((config: InternalAxiosRequestConfig) => {
	if (authToken) {
		config.headers.Authorization = `Bearer ${authToken}`;
	}
	return config;
});

api.interceptors.response.use(
	(response) => response,
	(error: AxiosError) => {
		if (error.response?.status === 401 && typeof unauthorizedHandler === 'function') {
			unauthorizedHandler();
		}
		return Promise.reject(error);
	}
);

export function getApiError(error: unknown, fallback = 'Request failed') {
	const axiosError = error as AxiosError<{ error?: string }>;
	return axiosError?.response?.data?.error || fallback;
}
