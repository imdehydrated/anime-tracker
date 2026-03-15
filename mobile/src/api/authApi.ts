import { api } from './client';

export interface LoginResponse {
	token: string;
}

export async function loginUser(email: string, password: string): Promise<LoginResponse> {
	const { data } = await api.post('/api/users/login', { email, password });
	return data;
}

export async function registerUser(username: string, email: string, password: string) {
	const { data } = await api.post('/api/users/register', { username, email, password });
	return data;
}
