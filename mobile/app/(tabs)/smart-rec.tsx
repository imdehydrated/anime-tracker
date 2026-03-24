import { useCallback, useEffect, useMemo, useState } from 'react';
import { router } from 'expo-router';
import { Image } from 'expo-image';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import {
  ActivityIndicator,
  Pressable,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';
import { getApiError } from '../../src/api/client';
import { useAuth } from '../../src/context/AuthContext';
import { useAddToList } from '../../src/hooks/useAddToList';
import { useAdultContentConsent } from '../../src/hooks/useAdultContentConsent';
import { useDebounceSearch } from '../../src/hooks/useDebounceSearch';
import { useRecommendationFeedback } from '../../src/hooks/useRecommendationFeedback';
import {
  RECOMMENDATION_FILTER_DEFAULTS,
  useRecommendationFilters,
} from '../../src/hooks/useRecommendationFilters';
import { useUserListIndex } from '../../src/hooks/useUserListIndex';
import {
  getSemanticRecommendationsPaged,
  RecommendationRequestBody,
} from '../../src/api/recommendationsApi';
import { AnimeSummary } from '../../src/types/anime';
import { useResponsiveLayout } from '../../src/ui/useResponsiveLayout';

const MAX_SEEDS = 5;
const MAX_RESULTS = 60;
const PAGE_SIZE = 12;
const SIMILAR_LIST_WEIGHT_WHEN_ENABLED = 0.25;
const SEED_SEARCH_FILTERS = {
  includeExtraSeasons: true,
  includeMovies: true,
  includeOnasOvasSpecials: true,
  includeMusic: true,
  includeAdult: false,
} as const;

const MODE_CARDS = [
  {
    key: 'semantic',
    label: 'Smart Search',
    copy: 'Describe what you want and get text-driven recommendations.',
    enabled: true,
  },
  {
    key: 'similar',
    label: 'Similar Shows',
    copy: 'Pick 1-5 seeds and find adjacent shows from those titles.',
    enabled: true,
  },
  {
    key: 'cf',
    label: 'For You',
    copy: 'Use your list history and feedback signals to get personal predictions.',
    enabled: true,
  },
] as const;

const FILTER_TOGGLES = [
  { key: 'includeExtraSeasons', label: 'Extra Seasons' },
  { key: 'includeMovies', label: 'Movies' },
  { key: 'includeOnasOvasSpecials', label: 'ONAs / OVAs / Specials' },
  { key: 'includeMusic', label: 'Music' },
  { key: 'includeAdult', label: '18+ Content' },
] as const;

const POPULARITY_OPTIONS = [
  { key: 'low', label: 'Low' },
  { key: 'medium', label: 'Medium' },
  { key: 'high', label: 'High' },
] as const;

function getAnimeTitle(anime: AnimeSummary) {
  return anime.title?.english || anime.title?.romaji || anime.title?.nativeTitle || 'Unknown title';
}

function getReasonText(anime: AnimeSummary) {
  if (typeof anime.recommendationReason === 'string' && anime.recommendationReason.trim().length > 0) {
    return anime.recommendationReason.trim();
  }
  if (Array.isArray(anime.reasonCodes) && anime.reasonCodes.length > 0) {
    return anime.reasonCodes.join(' | ');
  }
  return '';
}

function getAnimeCoverUrl(anime: AnimeSummary) {
  return typeof anime.coverImage === 'string'
    ? anime.coverImage
    : anime.coverImage?.large || anime.coverImage?.medium || null;
}

function getSeedContext(seeds: AnimeSummary[]) {
  if (seeds.length === 0) return null;
  return seeds.map((seed) => getAnimeTitle(seed)).join(' | ');
}

export default function SmartRecScreen() {
  const layout = useResponsiveLayout();
  const { isLoggedIn } = useAuth();
  const adultConsent = useAdultContentConsent();
  const [mode, setMode] = useState<(typeof MODE_CARDS)[number]['key']>('semantic');
  const [context, setContext] = useState('');
  const [seeds, setSeeds] = useState<AnimeSummary[]>([]);
  const [similarUseList, setSimilarUseList] = useState(false);
  const [results, setResults] = useState<AnimeSummary[]>([]);
  const [hasRequested, setHasRequested] = useState(false);
  const [searching, setSearching] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [screenError, setScreenError] = useState('');

  const { filters, setFilters } = useRecommendationFilters(RECOMMENDATION_FILTER_DEFAULTS);
  const { addToList, message, error: addError, clearMessages } = useAddToList();
  const { hasAnime, markAnimeOnList } = useUserListIndex();
  const feedback = useRecommendationFeedback(setScreenError, isLoggedIn);
  const {
    query: seedQuery,
    setQuery: setSeedQuery,
    results: suggestions,
    loading: suggestionsLoading,
    clearResults: clearSeedResults,
  } = useDebounceSearch(220, 2, 12, SEED_SEARCH_FILTERS);

  const isCfMode = mode === 'cf';
  const isSimilarMode = mode === 'similar';
  const canSearch = isCfMode ? isLoggedIn : isSimilarMode ? seeds.length > 0 : context.trim().length > 0;

  const visibleModeCards = useMemo(
    () =>
      MODE_CARDS.map((card) =>
        card.key === 'cf'
          ? {
              ...card,
              enabled: isLoggedIn,
              copy: isLoggedIn
                ? card.copy
                : 'Login to unlock collaborative predictions based on your list and feedback.',
            }
          : card
      ),
    [isLoggedIn]
  );

  const helperText = useMemo(() => {
    if (isCfMode) {
      if (searching) return 'Generating collaborative predictions from your list history...';
      return `Showing ${results.length} prediction${results.length === 1 ? '' : 's'} from your saved taste profile.`;
    }
    if (isSimilarMode) {
      if (seeds.length === 0) return 'Add 1-5 seed anime, then run Similar Shows.';
      if (searching) return 'Finding nearby shows from your selected seeds...';
      return `Using ${seeds.length} seed${seeds.length === 1 ? '' : 's'} to generate ${results.length} recommendation${results.length === 1 ? '' : 's'}.`;
    }
    if (context.trim().length === 0) return 'Describe tone, genre, pacing, themes, or examples you want matched.';
    if (searching) return 'Finding semantic matches...';
    return `Showing ${results.length} recommendation${results.length === 1 ? '' : 's'}.`;
  }, [context, isCfMode, isSimilarMode, results.length, searching, seeds.length]);

  const modeSubtitle = isCfMode
    ? 'Use collaborative filtering from your tracked list and recommendation feedback to get account-specific predictions.'
    : isSimilarMode
      ? 'Pick anime you already like, then search outward from those seeds with the same filter stack used elsewhere in the app.'
      : 'Describe what you want and get text-driven recommendations. Similar Shows and For You now sit beside Smart Search in the same workspace.';

  const searchActionLabel = isCfMode ? 'Get Predictions' : isSimilarMode ? 'Find Similar' : 'Find Recommendations';
  const queryContext = isCfMode ? null : isSimilarMode ? getSeedContext(seeds) : context.trim() || null;

  const resetSearchState = () => {
    setResults([]);
    setHasRequested(false);
    setSearching(false);
    setLoadingMore(false);
    setNextCursor(null);
    setHasMore(false);
    setScreenError('');
    clearMessages();
  };

  const handleModeChange = (nextMode: (typeof MODE_CARDS)[number]['key']) => {
    const selected = visibleModeCards.find((card) => card.key === nextMode);
    if (!selected?.enabled || nextMode === mode) return;
    setMode(nextMode);
    resetSearchState();
  };

  useEffect(() => {
    if (!isLoggedIn && isCfMode) {
      setMode('semantic');
      resetSearchState();
    }
  }, [isCfMode, isLoggedIn]);

  const handleToggleChange = async (key: (typeof FILTER_TOGGLES)[number]['key'], value: boolean) => {
    if (key !== 'includeAdult') {
      setFilters((prev) => ({ ...prev, [key]: value }));
      return;
    }
    if (!value) {
      setFilters((prev) => ({ ...prev, includeAdult: false }));
      return;
    }
    const granted = await adultConsent.requestAdultContentConsent();
    if (!granted) return;
    setFilters((prev) => ({ ...prev, includeAdult: true }));
  };

  const handleSelectSeed = (anime: AnimeSummary) => {
    if (seeds.length >= MAX_SEEDS) return;
    if (seeds.some((seed) => seed.id === anime.id)) return;
    setSeeds((prev) => [...prev, anime]);
    clearSeedResults();
  };

  const handleRemoveSeed = (id: number) => {
    setSeeds((prev) => prev.filter((seed) => seed.id !== id));
  };

  const handleSeedSubmit = () => {
    const firstCandidate = suggestions.find((anime) => !seeds.some((seed) => seed.id === anime.id));
    if (firstCandidate) handleSelectSeed(firstCandidate);
  };

  const buildSearchBody = (cursor: string | null = null): RecommendationRequestBody => {
    const body: RecommendationRequestBody = {
      mode,
      cursor,
      pageSize: PAGE_SIZE,
      limit: MAX_RESULTS,
      filters: filters as unknown as Record<string, unknown>,
    };
    if (isSimilarMode) {
      body.seedIds = seeds.map((seed) => seed.id);
      if (isLoggedIn && similarUseList) body.listWeight = SIMILAR_LIST_WEIGHT_WHEN_ENABLED;
    } else if (isCfMode) {
      body.useListOnly = true;
    } else {
      body.query = context.trim();
    }
    return body;
  };

  const runSearch = async (cursor: string | null = null, append = false) => {
    if (!canSearch) return;
    if (append) {
      setLoadingMore(true);
    } else {
      setSearching(true);
      setHasRequested(true);
      setResults([]);
      setNextCursor(null);
      setHasMore(false);
    }
    setScreenError('');
    if (!append) clearMessages();

    try {
      const page = await getSemanticRecommendationsPaged(buildSearchBody(cursor));
      const incoming = Array.isArray(page.items) ? page.items : [];
      setResults((prev) => {
        if (!append) return incoming;
        const byId = new Map(prev.map((item) => [item.id, item]));
        incoming.forEach((item) => byId.set(item.id, item));
        return Array.from(byId.values());
      });
      setNextCursor(page.nextCursor || null);
      setHasMore(Boolean(page.hasMore));
    } catch (err) {
      setScreenError(getApiError(err, append ? 'Failed to load more results.' : 'Recommendation search failed.'));
    } finally {
      setSearching(false);
      setLoadingMore(false);
    }
  };

  const handleRefresh = useCallback(async () => {
    if (!canSearch || (!hasRequested && results.length === 0 && !screenError)) {
      return;
    }

    setRefreshing(true);
    try {
      await runSearch();
    } finally {
      setRefreshing(false);
    }
  }, [canSearch, hasRequested, results.length, runSearch, screenError]);

  return (
    <ScrollView
      style={styles.screen}
      refreshControl={
        <RefreshControl refreshing={refreshing} onRefresh={() => void handleRefresh()} tintColor="#e94560" />
      }
      contentContainerStyle={[
        styles.content,
        {
          paddingHorizontal: layout.horizontalPadding,
          paddingTop: layout.topPadding,
          paddingBottom: layout.bottomPadding,
        },
      ]}
    >
      <View style={[styles.contentInner, { maxWidth: layout.contentMaxWidth }]}>
        <View style={[styles.heroCard, { padding: layout.cardPadding }]}>
          <View style={styles.heroGlow} />
          <View style={styles.heroContent}>
            <Text style={styles.eyebrow}>Recommendation Workspace</Text>
            <Text style={[styles.title, { fontSize: layout.titleSize, lineHeight: layout.titleLineHeight }]}>
              Smart Rec
            </Text>
            <Text style={[styles.copy, { fontSize: layout.bodySize, lineHeight: layout.bodyLineHeight }]}>
              {modeSubtitle}
            </Text>

            <View style={styles.heroBadgeRow}>
              <View style={styles.heroBadge}>
                <Text style={[styles.heroBadgeText, { fontSize: layout.helperSize }]}>
                  {isCfMode ? 'For You' : isSimilarMode ? 'Similar Shows' : 'Smart Search'}
                </Text>
              </View>
              <View style={[styles.heroBadge, styles.heroBadgeMuted]}>
                <Text style={[styles.heroBadgeText, { fontSize: layout.helperSize }]}>Pull to Refresh</Text>
              </View>
            </View>
          </View>
        </View>

        <View style={styles.modeGrid}>
          {visibleModeCards.map((card) => {
            const isActive = mode === card.key;
            const disabled = !card.enabled;
            return (
              <Pressable
                key={card.key}
                disabled={disabled}
                onPress={() => handleModeChange(card.key)}
                style={({ pressed }) => [
                  styles.modeCard,
                  isActive ? styles.modeCardActive : styles.modeCardInactive,
                  disabled ? styles.modeCardDisabled : null,
                  pressed && !disabled ? styles.modeCardPressed : null,
                ]}
              >
                <Text style={styles.modeLabel}>{card.label}</Text>
                <Text style={[styles.modeCopy, { fontSize: layout.helperSize, lineHeight: layout.bodyLineHeight }]}>
                  {card.copy}
                </Text>
              </Pressable>
            );
          })}
        </View>

        {isCfMode ? (
          <View style={[styles.promptCard, { padding: layout.cardPadding }]}>
            <Text style={styles.sectionLabel}>For You</Text>
            <Text style={[styles.cfCopy, { fontSize: layout.bodySize, lineHeight: layout.bodyLineHeight }]}>
              This mode uses your saved list history plus recommendation feedback to generate collaborative predictions. The stronger your list and feedback history, the better this mode gets.
            </Text>
            <Text style={[styles.helperText, { fontSize: layout.helperSize, lineHeight: layout.bodyLineHeight }]}>
              {helperText}
            </Text>
          </View>
        ) : isSimilarMode ? (
          <View style={[styles.promptCard, { padding: layout.cardPadding }]}>
            <Text style={styles.sectionLabel}>Seed Anime (pick 1-5)</Text>

            {seeds.length > 0 ? (
              <View style={styles.seedChipRow}>
                {seeds.map((seed) => (
                  <Pressable
                    key={seed.id}
                    onPress={() => handleRemoveSeed(seed.id)}
                    style={({ pressed }) => [
                      styles.seedChip,
                      pressed ? styles.seedChipPressed : null,
                    ]}
                  >
                    <Text style={styles.seedChipText}>{getAnimeTitle(seed)}</Text>
                    <Text style={styles.seedChipRemove}>x</Text>
                  </Pressable>
                ))}
              </View>
            ) : null}

            {seeds.length < MAX_SEEDS ? (
              <>
                <TextInput
                  autoCapitalize="none"
                  autoCorrect={false}
                  onChangeText={setSeedQuery}
                  onSubmitEditing={handleSeedSubmit}
                  placeholder="Search to add a seed anime..."
                  placeholderTextColor="rgba(255,255,255,0.35)"
                  returnKeyType="search"
                  style={[
                    styles.seedInput,
                    {
                      fontSize: layout.inputSize,
                      paddingVertical: layout.inputVerticalPadding,
                    },
                  ]}
                  value={seedQuery}
                />

                {suggestionsLoading && suggestions.length === 0 ? (
                  <View style={styles.seedLoadingRow}>
                    <ActivityIndicator color="#e94560" />
                    <Text style={[styles.seedLoadingText, { fontSize: layout.helperSize }]}>Searching seeds...</Text>
                  </View>
                ) : null}

                {suggestions.length > 0 ? (
                  <View style={styles.seedSuggestionList}>
                    {suggestions.map((anime) => {
                      const alreadySelected = seeds.some((seed) => seed.id === anime.id);
                      const coverUrl = getAnimeCoverUrl(anime);
                      return (
                        <Pressable
                          key={anime.id}
                          disabled={alreadySelected}
                          onPress={() => handleSelectSeed(anime)}
                          style={({ pressed }) => [
                            styles.seedSuggestionItem,
                            alreadySelected ? styles.seedSuggestionItemDisabled : null,
                            pressed && !alreadySelected ? styles.seedSuggestionItemPressed : null,
                          ]}
                        >
                          {coverUrl ? (
                            <Image contentFit="cover" source={{ uri: coverUrl }} style={styles.seedSuggestionCover} />
                          ) : (
                            <View style={styles.seedSuggestionCoverFallback}>
                              <Text style={styles.seedSuggestionCoverFallbackText}>No Cover</Text>
                            </View>
                          )}
                          <View style={styles.seedSuggestionBody}>
                            <Text style={[styles.seedSuggestionTitle, { fontSize: layout.bodySize }]}>
                              {getAnimeTitle(anime)}
                            </Text>
                            <Text style={[styles.seedSuggestionMeta, { fontSize: layout.helperSize }]}>
                              {alreadySelected ? 'Already selected' : 'Tap to add as a seed'}
                            </Text>
                          </View>
                        </Pressable>
                      );
                    })}
                  </View>
                ) : null}
              </>
            ) : (
              <Text style={[styles.helperText, { fontSize: layout.helperSize, lineHeight: layout.bodyLineHeight }]}>
                Maximum of {MAX_SEEDS} seeds selected. Remove one to add another.
              </Text>
            )}

            <Text style={[styles.helperText, { fontSize: layout.helperSize, lineHeight: layout.bodyLineHeight }]}>
              {helperText}
            </Text>
          </View>
        ) : (
          <View style={[styles.promptCard, { padding: layout.cardPadding }]}>
            <Text style={styles.sectionLabel}>Smart Search Prompt</Text>
            <TextInput
              multiline
              numberOfLines={4}
              onChangeText={setContext}
              onSubmitEditing={() => void runSearch()}
              placeholder="e.g. psychological thriller with strong character mind games, or a warm ensemble slice-of-life with sharp comedy"
              placeholderTextColor="rgba(255,255,255,0.35)"
              returnKeyType="search"
              style={[
                styles.contextInput,
                {
                  fontSize: layout.inputSize,
                  minHeight: layout.isCompact ? 112 : 124,
                },
              ]}
              submitBehavior="blurAndSubmit"
              textAlignVertical="top"
              value={context}
            />
            <Text style={[styles.helperText, { fontSize: layout.helperSize, lineHeight: layout.bodyLineHeight }]}>
              {helperText}
            </Text>
          </View>
        )}

        <View style={styles.filterPanel}>
          <View style={[styles.filterPanelHeader, { paddingHorizontal: layout.compactCardPadding }]}>
            <Text style={[styles.filterPanelEyebrow, { fontSize: layout.helperSize }]}>Recommendation Filters</Text>
            <Text style={[styles.filterPanelCopy, { fontSize: layout.helperSize, lineHeight: layout.bodyLineHeight }]}>
              Tighten content formats and ranking behavior without leaving the recommendation flow.
            </Text>
          </View>

          <View style={[styles.filterSectionHeader, { paddingHorizontal: layout.compactCardPadding }]}>
            <Text style={[styles.filterSectionTitle, { fontSize: layout.helperSize }]}>Content</Text>
          </View>

          {FILTER_TOGGLES.map((toggle) => (
            <View key={toggle.key} style={[styles.filterRow, { paddingHorizontal: layout.compactCardPadding }]}>
              <Text style={[styles.filterLabel, { fontSize: layout.bodySize }]}>
                {toggle.label}
              </Text>
              <Switch
                trackColor={{ false: '#2c2c44', true: 'rgba(233,69,96,0.45)' }}
                thumbColor={filters[toggle.key] ? '#e94560' : '#f4f4f5'}
                disabled={toggle.key === 'includeAdult' && !adultConsent.consentLoaded}
                value={Boolean(filters[toggle.key])}
                onValueChange={(value) => void handleToggleChange(toggle.key, value)}
              />
            </View>
          ))}

          <View style={[styles.filterSectionHeader, styles.filterSectionHeaderBorder, { paddingHorizontal: layout.compactCardPadding }]}>
            <Text style={[styles.filterSectionTitle, { fontSize: layout.helperSize }]}>Ranking</Text>
          </View>

          <View
            style={[
              styles.filterRow,
              !isLoggedIn || !isSimilarMode ? styles.filterRowLast : null,
              { paddingHorizontal: layout.compactCardPadding },
            ]}
          >
            <Text style={[styles.filterLabel, { fontSize: layout.bodySize }]}>Popularity Bias</Text>
            <View style={styles.popularityRow}>
              {POPULARITY_OPTIONS.map((option) => (
                <Pressable
                  key={option.key}
                  onPress={() =>
                    setFilters((prev) => ({ ...prev, popularityAttenuation: option.key }))
                  }
                  style={({ pressed }) => [
                    styles.popularityButton,
                    filters.popularityAttenuation === option.key ? styles.popularityButtonActive : null,
                    pressed ? styles.popularityButtonPressed : null,
                  ]}
                >
                  <Text
                    style={[
                      styles.popularityButtonText,
                      filters.popularityAttenuation === option.key ? styles.popularityButtonTextActive : null,
                    ]}
                  >
                    {option.label}
                  </Text>
                </Pressable>
              ))}
            </View>
          </View>

          {isLoggedIn && isSimilarMode ? (
            <View style={[styles.filterRow, styles.filterRowLast, { paddingHorizontal: layout.compactCardPadding }]}>
              <View style={styles.filterCopyBlock}>
                <Text style={[styles.filterLabel, { fontSize: layout.bodySize }]}>Use List Personalization</Text>
                <Text style={[styles.filterHelp, { fontSize: layout.helperSize, lineHeight: layout.bodyLineHeight }]}>
                  Blend your own list taste profile into seed-based results instead of using seeds alone.
                </Text>
              </View>
              <Switch
                trackColor={{ false: '#2c2c44', true: 'rgba(233,69,96,0.45)' }}
                thumbColor={similarUseList ? '#e94560' : '#f4f4f5'}
                value={similarUseList}
                onValueChange={setSimilarUseList}
              />
            </View>
          ) : null}
        </View>

        <Text style={[styles.policyNote, { fontSize: layout.helperSize, lineHeight: layout.bodyLineHeight }]}>
          18+ content stays filtered by default and requires explicit opt-in before it can be shown.
        </Text>

        <View style={styles.actionBar}>
          <Pressable
            disabled={!canSearch || searching || loadingMore}
            onPress={() => void runSearch()}
            style={({ pressed }) => [
              styles.primaryAction,
              !canSearch || searching || loadingMore ? styles.primaryActionDisabled : null,
              pressed && canSearch && !searching && !loadingMore ? styles.primaryActionPressed : null,
            ]}
          >
            <Text style={[styles.primaryActionText, { fontSize: layout.buttonTextSize }]}>
              {searching ? 'Searching...' : searchActionLabel}
            </Text>
          </Pressable>

          {isLoggedIn ? (
            <Pressable onPress={() => void feedback.openFeedback()} style={styles.secondaryAction}>
              <Text style={[styles.secondaryActionText, { fontSize: layout.buttonTextSize }]}>Manage Feedback</Text>
            </Pressable>
          ) : null}
        </View>

        {!canSearch ? (
          <Text style={[styles.readinessHint, { fontSize: layout.helperSize, lineHeight: layout.bodyLineHeight }]}>
            {isCfMode
              ? 'Login to unlock For You predictions.'
              : isSimilarMode
              ? 'Add at least one seed anime to run Similar Shows.'
              : 'Describe what you want before running Smart Search.'}
          </Text>
        ) : null}

        {screenError ? <Text style={[styles.errorText, { fontSize: layout.bodySize }]}>{screenError}</Text> : null}
        {addError ? <Text style={[styles.errorText, { fontSize: layout.bodySize }]}>{addError}</Text> : null}
        {message ? <Text style={[styles.successText, { fontSize: layout.bodySize }]}>{message}</Text> : null}

        {hasRequested && !searching && results.length === 0 && !screenError ? (
          <View style={styles.emptyState}>
            <Text style={styles.emptyTitle}>No recommendations found.</Text>
            <Text style={[styles.emptyCopy, { fontSize: layout.bodySize, lineHeight: layout.bodyLineHeight }]}>
              Try relaxing filters, broadening the phrasing, or changing the query or seed mix.
            </Text>
          </View>
        ) : null}

        {results.length > 0 ? (
          <View style={styles.resultsSection}>
            <Text style={[styles.sectionTitle, { fontSize: layout.sectionTitleSize }]}>
              Results ({results.length}/{MAX_RESULTS})
            </Text>

            <View style={styles.resultList}>
              {results.map((anime) => {
                const title = getAnimeTitle(anime);
                const reasonText = getReasonText(anime);
                const coverUrl = getAnimeCoverUrl(anime);
                const feedbackSignal = feedback.getFeedbackSignal(anime.id);
                const isOnList = hasAnime(anime.id);

                return (
                  <View key={anime.id} style={[styles.resultCard, { padding: layout.compactCardPadding }]}>
                    <View style={styles.resultHeader}>
                      {coverUrl ? (
                        <Image contentFit="cover" source={{ uri: coverUrl }} style={styles.resultCover} />
                      ) : (
                        <View style={styles.resultCoverFallback}>
                          <Text style={styles.resultCoverFallbackText}>No Cover</Text>
                        </View>
                      )}

                      <View style={styles.resultBody}>
                        <Text style={[styles.resultTitle, { fontSize: layout.resultTitleSize }]}>{title}</Text>
                        {reasonText ? (
                          <Text style={[styles.reasonText, { fontSize: layout.bodySize, lineHeight: layout.bodyLineHeight }]}>
                            {reasonText}
                          </Text>
                        ) : null}
                        {anime.genres?.length ? (
                          <Text style={[styles.metaText, { fontSize: layout.helperSize, lineHeight: layout.bodyLineHeight }]}>
                            {anime.genres.slice(0, 3).join(' | ')}
                          </Text>
                        ) : null}
                        <Text style={[styles.metaText, { fontSize: layout.helperSize, lineHeight: layout.bodyLineHeight }]}>
                          Score: <Text style={styles.metaTextStrong}>{anime.averageScore || '?'}</Text>/100
                        </Text>
                      </View>
                    </View>

                    <View style={styles.resultActionRow}>
                      <Pressable
                        onPress={() =>
                          router.push({ pathname: '/anime/[id]', params: { id: String(anime.id) } } as any)
                        }
                        style={({ pressed }) => [
                          styles.detailButton,
                          pressed ? styles.detailButtonPressed : null,
                        ]}
                      >
                        <Text style={[styles.detailButtonText, { fontSize: layout.buttonTextSize }]}>View Details</Text>
                      </Pressable>

                      {isLoggedIn ? (
                        isOnList ? (
                          <View style={styles.onListBadge}>
                            <Text style={[styles.onListBadgeText, { fontSize: layout.buttonTextSize }]}>On Your List</Text>
                          </View>
                        ) : (
                          <Pressable
                            onPress={async () => {
                              clearMessages();
                              const success = await addToList(anime);
                              if (success) {
                                markAnimeOnList(anime.id);
                              }
                            }}
                            style={({ pressed }) => [
                              styles.addButton,
                              pressed ? styles.addButtonPressed : null,
                            ]}
                          >
                            <Text style={[styles.addButtonText, { fontSize: layout.buttonTextSize }]}>Add to List</Text>
                          </Pressable>
                        )
                      ) : (
                        <Pressable
                          onPress={() => router.push('/login' as any)}
                          style={({ pressed }) => [
                            styles.addButton,
                            pressed ? styles.addButtonPressed : null,
                          ]}
                        >
                          <Text style={[styles.addButtonText, { fontSize: layout.buttonTextSize }]}>Login to Save</Text>
                        </Pressable>
                      )}
                    </View>

                    {isLoggedIn ? (
                      <View style={styles.feedbackRow}>
                        <Pressable
                          accessibilityLabel="Thumbs up"
                          onPress={() => void feedback.handleThumbsUp(anime, mode, queryContext)}
                          style={({ pressed }) => [
                            styles.feedbackButton,
                            feedbackSignal === 'thumbs_up' ? styles.feedbackButtonActive : null,
                            pressed ? styles.feedbackButtonPressed : null,
                          ]}
                        >
                          <MaterialCommunityIcons
                            name="thumb-up"
                            size={22}
                            color={feedbackSignal === 'thumbs_up' ? '#ffffff' : 'rgba(255,255,255,0.82)'}
                          />
                        </Pressable>
                        <Pressable
                          accessibilityLabel="Thumbs down"
                          onPress={() => void feedback.handleThumbsDown(anime, mode, queryContext)}
                          style={({ pressed }) => [
                            styles.feedbackButton,
                            feedbackSignal === 'thumbs_down' ? styles.feedbackButtonActive : null,
                            pressed ? styles.feedbackButtonPressed : null,
                          ]}
                        >
                          <MaterialCommunityIcons
                            name="thumb-down"
                            size={22}
                            color={feedbackSignal === 'thumbs_down' ? '#ffffff' : 'rgba(255,255,255,0.82)'}
                          />
                        </Pressable>
                      </View>
                    ) : (
                      <Text style={[styles.loginHint, { fontSize: layout.helperSize, lineHeight: layout.bodyLineHeight }]}>
                        Login to give recommendation feedback.
                      </Text>
                    )}
                  </View>
                );
              })}
            </View>

            {hasMore ? (
              <Pressable
                disabled={loadingMore || searching || results.length >= MAX_RESULTS}
                onPress={() => void runSearch(nextCursor, true)}
                style={({ pressed }) => [
                  styles.secondaryAction,
                  styles.loadMoreButton,
                  pressed && !loadingMore && !searching ? styles.secondaryActionPressed : null,
                ]}
              >
                <Text style={[styles.secondaryActionText, { fontSize: layout.buttonTextSize }]}>
                  {loadingMore ? 'Loading...' : 'Load More'}
                </Text>
              </Pressable>
            ) : null}
          </View>
        ) : null}
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: '#0f0f1a',
  },
  content: {},
  contentInner: {
    width: '100%',
    alignSelf: 'center',
  },
  heroCard: {
    position: 'relative',
    marginBottom: 18,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.08)',
    borderRadius: 24,
    overflow: 'hidden',
    backgroundColor: '#12122a',
  },
  heroGlow: {
    position: 'absolute',
    top: -42,
    right: -14,
    width: 150,
    height: 150,
    borderRadius: 999,
    backgroundColor: 'rgba(233,69,96,0.18)',
  },
  heroContent: {
    position: 'relative',
  },
  eyebrow: {
    marginBottom: 10,
    fontSize: 12,
    fontWeight: '700',
    letterSpacing: 1.5,
    textTransform: 'uppercase',
    color: '#e94560',
  },
  title: {
    marginBottom: 10,
    fontWeight: '700',
    color: '#ffffff',
  },
  copy: {
    marginBottom: 18,
    color: 'rgba(255,255,255,0.72)',
  },
  heroBadgeRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  heroBadge: {
    borderWidth: 1,
    borderColor: 'rgba(233,69,96,0.24)',
    borderRadius: 999,
    paddingHorizontal: 10,
    paddingVertical: 6,
    backgroundColor: 'rgba(233,69,96,0.12)',
  },
  heroBadgeMuted: {
    borderColor: 'rgba(255,255,255,0.1)',
    backgroundColor: 'rgba(255,255,255,0.05)',
  },
  heroBadgeText: {
    fontWeight: '700',
    color: '#ffffff',
  },
  modeGrid: {
    gap: 12,
    marginBottom: 18,
  },
  modeCard: {
    borderWidth: 1,
    borderRadius: 18,
    padding: 16,
  },
  modeCardActive: {
    borderColor: 'rgba(233,69,96,0.35)',
    backgroundColor: '#171733',
  },
  modeCardInactive: {
    borderColor: 'rgba(255,255,255,0.08)',
    backgroundColor: '#12122a',
  },
  modeCardDisabled: {
    opacity: 0.65,
  },
  modeCardPressed: {
    opacity: 0.9,
  },
  modeLabel: {
    marginBottom: 6,
    fontSize: 17,
    fontWeight: '700',
    color: '#ffffff',
  },
  modeCopy: {
    color: 'rgba(255,255,255,0.68)',
  },
  promptCard: {
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.08)',
    borderRadius: 24,
    marginBottom: 18,
    backgroundColor: '#12122a',
  },
  sectionLabel: {
    marginBottom: 10,
    fontSize: 13,
    fontWeight: '700',
    color: '#ffffff',
  },
  cfCopy: {
    color: 'rgba(255,255,255,0.72)',
  },
  contextInput: {
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.1)',
    borderRadius: 16,
    paddingHorizontal: 16,
    paddingTop: 14,
    paddingBottom: 14,
    color: '#ffffff',
    backgroundColor: '#171733',
  },
  seedInput: {
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.1)',
    borderRadius: 16,
    paddingHorizontal: 16,
    color: '#ffffff',
    backgroundColor: '#171733',
  },
  helperText: {
    marginTop: 10,
    color: 'rgba(255,255,255,0.58)',
  },
  policyNote: {
    marginBottom: 18,
    color: 'rgba(255,255,255,0.52)',
  },
  seedChipRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    marginBottom: 12,
  },
  seedChip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    borderWidth: 1,
    borderColor: 'rgba(233,69,96,0.28)',
    borderRadius: 999,
    paddingHorizontal: 12,
    paddingVertical: 8,
    backgroundColor: 'rgba(233,69,96,0.16)',
  },
  seedChipPressed: {
    opacity: 0.9,
  },
  seedChipText: {
    fontSize: 13,
    fontWeight: '600',
    color: '#ffffff',
  },
  seedChipRemove: {
    fontSize: 13,
    fontWeight: '700',
    color: '#ffb8c4',
  },
  seedLoadingRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    marginTop: 10,
  },
  seedLoadingText: {
    color: 'rgba(255,255,255,0.68)',
  },
  seedSuggestionList: {
    marginTop: 10,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.08)',
    borderRadius: 16,
    overflow: 'hidden',
    backgroundColor: '#171733',
  },
  seedSuggestionItem: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingHorizontal: 14,
    paddingVertical: 12,
    borderBottomWidth: 1,
    borderBottomColor: 'rgba(255,255,255,0.06)',
  },
  seedSuggestionItemDisabled: {
    opacity: 0.5,
  },
  seedSuggestionItemPressed: {
    backgroundColor: 'rgba(233,69,96,0.12)',
  },
  seedSuggestionTitle: {
    marginBottom: 4,
    fontWeight: '600',
    color: '#ffffff',
  },
  seedSuggestionBody: {
    flex: 1,
  },
  seedSuggestionCover: {
    width: 44,
    height: 62,
    borderRadius: 10,
    backgroundColor: '#12122a',
  },
  seedSuggestionCoverFallback: {
    width: 44,
    height: 62,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#12122a',
  },
  seedSuggestionCoverFallbackText: {
    fontSize: 9,
    fontWeight: '600',
    color: 'rgba(255,255,255,0.48)',
    textAlign: 'center',
  },
  seedSuggestionMeta: {
    color: 'rgba(255,255,255,0.56)',
  },
  filterPanel: {
    marginBottom: 18,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.08)',
    borderRadius: 18,
    overflow: 'hidden',
    backgroundColor: '#12122a',
  },
  filterPanelHeader: {
    paddingTop: 12,
    paddingBottom: 12,
    borderBottomWidth: 1,
    borderBottomColor: 'rgba(255,255,255,0.06)',
    backgroundColor: '#15152d',
  },
  filterPanelEyebrow: {
    marginBottom: 4,
    fontWeight: '700',
    letterSpacing: 0.7,
    textTransform: 'uppercase',
    color: '#e94560',
  },
  filterPanelCopy: {
    color: 'rgba(255,255,255,0.58)',
  },
  filterSectionHeader: {
    paddingTop: 10,
    paddingBottom: 8,
    backgroundColor: '#12122a',
  },
  filterSectionHeaderBorder: {
    borderTopWidth: 1,
    borderTopColor: 'rgba(255,255,255,0.06)',
  },
  filterSectionTitle: {
    fontWeight: '700',
    letterSpacing: 0.6,
    textTransform: 'uppercase',
    color: 'rgba(255,255,255,0.5)',
  },
  filterRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 12,
    borderBottomWidth: 1,
    borderBottomColor: 'rgba(255,255,255,0.06)',
  },
  filterRowLast: {
    borderBottomWidth: 0,
  },
  filterCopyBlock: {
    flex: 1,
    paddingRight: 12,
  },
  filterLabel: {
    flex: 1,
    paddingRight: 12,
    fontWeight: '600',
    color: '#ffffff',
  },
  filterHelp: {
    marginTop: 6,
    color: 'rgba(255,255,255,0.58)',
  },
  popularityRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    justifyContent: 'flex-end',
  },
  popularityButton: {
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.12)',
    borderRadius: 999,
    paddingHorizontal: 10,
    paddingVertical: 7,
    backgroundColor: '#171733',
  },
  popularityButtonActive: {
    borderColor: 'rgba(233,69,96,0.35)',
    backgroundColor: 'rgba(233,69,96,0.18)',
  },
  popularityButtonPressed: {
    opacity: 0.9,
  },
  popularityButtonText: {
    fontSize: 13,
    fontWeight: '600',
    color: 'rgba(255,255,255,0.75)',
  },
  popularityButtonTextActive: {
    color: '#ffffff',
  },
  actionBar: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 12,
    marginBottom: 14,
  },
  primaryAction: {
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 14,
    paddingHorizontal: 18,
    paddingVertical: 14,
    backgroundColor: '#e94560',
  },
  primaryActionDisabled: {
    opacity: 0.55,
  },
  primaryActionPressed: {
    opacity: 0.9,
  },
  primaryActionText: {
    fontWeight: '700',
    color: '#ffffff',
  },
  secondaryAction: {
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.12)',
    borderRadius: 14,
    paddingHorizontal: 18,
    paddingVertical: 14,
    backgroundColor: '#171733',
  },
  secondaryActionPressed: {
    opacity: 0.9,
  },
  secondaryActionText: {
    fontWeight: '700',
    color: '#ffffff',
  },
  readinessHint: {
    marginBottom: 14,
    color: 'rgba(255,255,255,0.56)',
  },
  errorText: {
    marginBottom: 12,
    borderWidth: 1,
    borderColor: 'rgba(255,107,107,0.4)',
    borderRadius: 14,
    paddingHorizontal: 14,
    paddingVertical: 12,
    color: '#ff8d8d',
    backgroundColor: 'rgba(123,33,33,0.25)',
  },
  successText: {
    marginBottom: 12,
    borderWidth: 1,
    borderColor: 'rgba(111,207,151,0.32)',
    borderRadius: 14,
    paddingHorizontal: 14,
    paddingVertical: 12,
    color: '#9ef0b8',
    backgroundColor: 'rgba(25,84,54,0.24)',
  },
  emptyState: {
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.08)',
    borderRadius: 18,
    padding: 18,
    backgroundColor: '#12122a',
  },
  emptyTitle: {
    marginBottom: 6,
    fontSize: 18,
    fontWeight: '700',
    color: '#ffffff',
  },
  emptyCopy: {
    color: 'rgba(255,255,255,0.68)',
  },
  resultsSection: {
    marginTop: 8,
  },
  sectionTitle: {
    marginBottom: 12,
    fontWeight: '700',
    color: '#ffffff',
  },
  resultList: {
    gap: 12,
  },
  resultCard: {
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.08)',
    borderRadius: 18,
    backgroundColor: '#12122a',
  },
  resultHeader: {
    flexDirection: 'row',
    gap: 14,
  },
  resultCover: {
    width: 72,
    height: 102,
    borderRadius: 14,
    backgroundColor: '#171733',
  },
  resultCoverFallback: {
    width: 72,
    height: 102,
    borderRadius: 14,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#171733',
  },
  resultCoverFallbackText: {
    fontSize: 11,
    fontWeight: '600',
    color: 'rgba(255,255,255,0.5)',
    textAlign: 'center',
  },
  resultBody: {
    flex: 1,
  },
  resultTitle: {
    marginBottom: 8,
    fontWeight: '700',
    color: '#ffffff',
  },
  reasonText: {
    marginBottom: 10,
    color: 'rgba(255,255,255,0.76)',
  },
  metaText: {
    color: 'rgba(255,255,255,0.62)',
  },
  metaTextStrong: {
    fontWeight: '700',
    color: '#ffffff',
  },
  resultActionRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 10,
    marginTop: 14,
  },
  detailButton: {
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.12)',
    borderRadius: 14,
    paddingHorizontal: 16,
    paddingVertical: 12,
    backgroundColor: '#171733',
  },
  detailButtonPressed: {
    opacity: 0.9,
  },
  detailButtonText: {
    fontWeight: '700',
    color: '#ffffff',
  },
  addButton: {
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 14,
    paddingHorizontal: 16,
    paddingVertical: 12,
    backgroundColor: '#e94560',
  },
  addButtonPressed: {
    opacity: 0.9,
  },
  addButtonText: {
    fontWeight: '700',
    color: '#ffffff',
  },
  onListBadge: {
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: 'rgba(111,207,151,0.34)',
    borderRadius: 14,
    paddingHorizontal: 16,
    paddingVertical: 12,
    backgroundColor: 'rgba(25,84,54,0.24)',
  },
  onListBadgeText: {
    fontWeight: '700',
    color: '#9ef0b8',
  },
  feedbackRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 10,
    marginTop: 12,
  },
  feedbackButton: {
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.12)',
    borderRadius: 14,
    minWidth: 52,
    minHeight: 46,
    paddingHorizontal: 14,
    paddingVertical: 10,
    backgroundColor: '#171733',
  },
  feedbackButtonActive: {
    borderColor: 'rgba(233,69,96,0.35)',
    backgroundColor: 'rgba(233,69,96,0.18)',
  },
  feedbackButtonPressed: {
    opacity: 0.9,
  },
  loginHint: {
    marginTop: 12,
    color: '#ff9dad',
  },
  loadMoreButton: {
    alignSelf: 'flex-start',
    marginTop: 14,
  },
});
