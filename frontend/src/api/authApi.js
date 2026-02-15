import { api } from './client';

// Authentication API contract.
export async function loginUser(email, password) {
	const { data } = await api.post('/api/users/login', { email, password });
	return data;
}

export async function registerUser(username, email, password) {
	const { data } = await api.post('/api/users/register', { username, email, password });
	return data;
}
