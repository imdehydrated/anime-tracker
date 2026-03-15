export interface AnimeTitle {
	english?: string | null;
	romaji?: string | null;
	nativeTitle?: string | null;
}

export interface AnimeCoverImage {
	large?: string | null;
	medium?: string | null;
}

export interface AnimeRelation {
	id: number;
	title?: AnimeTitle | null;
	relationType?: string | null;
}

export interface AnimeSummary {
	id: number;
	title: AnimeTitle;
	coverImage?: AnimeCoverImage | string | null;
	averageScore?: number | null;
	genres?: string[] | null;
	episodes?: number | null;
	format?: string | null;
	season?: string | null;
	seasonYear?: number | null;
	startDate?: {
		year?: number | null;
	} | null;
	popularity?: number | null;
	description?: string | null;
	recommendationReason?: string | null;
	reasonCodes?: string[] | null;
}

export interface AnimeDetail extends AnimeSummary {
	bannerImage?: string | null;
	status?: string | null;
	studios?: Array<string | { name?: string | null }> | null;
	relations?: AnimeRelation[] | null;
	synonyms?: string[] | null;
}

export interface SearchPage<T> {
	items: T[];
	nextCursor: string | null;
	hasMore: boolean;
	diagnostics?: unknown;
}

export interface UserListEntry {
	id: number;
	anilistId: number;
	title?: string | null;
	coverImage?: string | null;
	status?: string | null;
	score?: number | null;
	episodesWatched?: number | null;
	totalEpisodes?: number | null;
	genres?: string | null;
	createdAt?: string | null;
}

export interface RecommendationFeedbackItem {
	id: number;
	anilistId: number;
	signal?: string | null;
	title?: string | null;
	coverImage?: string | null;
}

export interface RecommendationRequestFilters {
	includeExtraSeasons: boolean;
	includeMovies: boolean;
	includeOnasOvasSpecials: boolean;
	includeMusic: boolean;
	includeAdult: boolean;
	popularityAttenuation?: 'low' | 'medium' | 'high';
}
