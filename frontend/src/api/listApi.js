import { api } from './client';

// Authenticated user list API contract.
export async function getUserList() {
	const { data } = await api.get('/api/users/list');
	return data;
}

export async function addAnimeToUserList(payload) {
	const { data } = await api.post('/api/users/list', payload);
	return data;
}

export async function updateListEntry(entryId, updates) {
	const { data } = await api.put(`/api/users/list/${entryId}`, updates);
	return data;
}

export async function deleteListEntry(entryId) {
	const { data } = await api.delete(`/api/users/list/${entryId}`);
	return data;
}
