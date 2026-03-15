import { api } from './client';
import { UserListEntry } from '../types/anime';

export interface AddToListPayload {
	anilistId: number;
	status: string;
	title?: string | null;
	coverImage?: string | null;
	genres?: string | null;
	totalEpisodes?: number | null;
}

export interface ImportResponse {
	message?: string;
	stats?: {
		discovered?: number;
		imported?: number;
		updated?: number;
		skipped?: number;
		failed?: number;
		failureSamples?: Array<{ detail?: string; reason?: string }>;
	};
}

export async function getUserList(): Promise<UserListEntry[]> {
	const { data } = await api.get('/api/users/list');
	return Array.isArray(data) ? data : [];
}

export async function addAnimeToUserList(payload: AddToListPayload) {
	const { data } = await api.post('/api/users/list', payload);
	return data;
}

export async function updateListEntry(entryId: number, updates: Record<string, unknown>) {
	const { data } = await api.put(`/api/users/list/${entryId}`, updates);
	return data;
}

export async function deleteListEntry(entryId: number) {
	const { data } = await api.delete(`/api/users/list/${entryId}`);
	return data;
}

export async function importAniListByUsername(username: string, dryRun = false): Promise<ImportResponse> {
	const { data } = await api.post('/api/users/list/import/anilist', null, {
		params: { username, dryRun },
	});
	return data;
}

export async function importMalByUsername(username: string, dryRun = false): Promise<ImportResponse> {
	const { data } = await api.post('/api/users/list/import/mal', null, {
		params: { username, dryRun },
	});
	return data;
}
