import { useCallback, useMemo, useState } from 'react';
import { router } from 'expo-router';
import { Image } from 'expo-image';
import {
  ActivityIndicator,
  FlatList,
  Pressable,
  RefreshControl,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';
import { useAuth } from '../../src/context/AuthContext';
import { useAddToList } from '../../src/hooks/useAddToList';
import { useAdultContentConsent } from '../../src/hooks/useAdultContentConsent';
import { useDebounceSearch } from '../../src/hooks/useDebounceSearch';
import {
  SEARCH_FILTER_DEFAULTS,
  useRecommendationFilters,
} from '../../src/hooks/useRecommendationFilters';
import { useUserListIndex } from '../../src/hooks/useUserListIndex';
import { AnimeSummary } from '../../src/types/anime';
import { useResponsiveLayout } from '../../src/ui/useResponsiveLayout';

const FILTER_TOGGLES = [
  { key: 'includeExtraSeasons', label: 'Extra Seasons' },
  { key: 'includeMovies', label: 'Movies' },
  { key: 'includeOnasOvasSpecials', label: 'ONAs / OVAs / Specials' },
  { key: 'includeMusic', label: 'Music' },
  { key: 'includeAdult', label: '18+ Content' },
] as const;

function getAnimeTitle(anime: AnimeSummary) {
  return anime.title?.english || anime.title?.romaji || anime.title?.nativeTitle || 'Unknown title';
}

function getAnimeMeta(anime: AnimeSummary) {
  const seasonYear = anime.seasonYear || anime.startDate?.year || null;
  const format = anime.format
    ? anime.format.toLowerCase().replace(/_/g, ' ').replace(/\b\w/g, (letter) => letter.toUpperCase())
    : null;
  return [seasonYear, format, anime.episodes ? `${anime.episodes} eps` : null]
    .filter(Boolean)
    .join(' | ');
}

function getAnimeCoverUrl(anime: AnimeSummary) {
  return typeof anime.coverImage === 'string'
    ? anime.coverImage
    : anime.coverImage?.large || anime.coverImage?.medium || null;
}

function openAnimeDetail(id: number) {
  router.push({ pathname: '/anime/[id]', params: { id: String(id) } } as any);
}

export default function SearchScreen() {
  const { isLoggedIn } = useAuth();
  const layout = useResponsiveLayout();
  const adultConsent = useAdultContentConsent();
  const { filters, setFilters } = useRecommendationFilters(SEARCH_FILTER_DEFAULTS);
  const { addToList, message, error: addError, clearMessages } = useAddToList();
  const { hasAnime, markAnimeOnList } = useUserListIndex();
  const { query, setQuery, results, loading, error, search } = useDebounceSearch(250, 2, 20, filters);
  const [refreshing, setRefreshing] = useState(false);

  const helperText = useMemo(() => {
    if (query.trim().length === 0) {
      return 'Search by title, franchise, or a clean name fragment.';
    }
    if (query.trim().length < 2) {
      return 'Type at least 2 characters to start searching.';
    }
    if (loading) {
      return 'Searching the local catalog...';
    }
    return `Showing ${results.length} result${results.length === 1 ? '' : 's'}.`;
  }, [loading, query, results.length]);

  const renderAnimeCard = ({ item }: { item: AnimeSummary }) => {
    const title = getAnimeTitle(item);
    const meta = getAnimeMeta(item);
    const coverUrl = getAnimeCoverUrl(item);
    const isOnList = hasAnime(item.id);

    return (
      <View style={[styles.contentInner, { maxWidth: layout.contentMaxWidth }]}>
        <View style={[styles.resultCard, { padding: layout.compactCardPadding }]}>
          <View style={styles.resultHeader}>
            {coverUrl ? (
              <Image contentFit="cover" source={{ uri: coverUrl }} style={styles.coverThumb} />
            ) : (
              <View style={styles.coverFallback}>
                <Text style={styles.coverFallbackText}>No Cover</Text>
              </View>
            )}

            <View style={styles.resultBody}>
              <Text style={[styles.resultTitle, { fontSize: layout.resultTitleSize }]}>{title}</Text>
              {meta ? (
                <Text style={[styles.resultMeta, { fontSize: layout.helperSize, lineHeight: layout.bodyLineHeight }]}>
                  {meta}
                </Text>
              ) : null}
              {item.genres?.length ? (
                <Text style={[styles.resultGenres, { fontSize: layout.helperSize, lineHeight: layout.bodyLineHeight }]}>
                  {item.genres.slice(0, 3).join(' | ')}
                </Text>
              ) : null}
              <Text style={[styles.resultScore, { fontSize: layout.helperSize, lineHeight: layout.bodyLineHeight }]}>
                Score: <Text style={styles.resultScoreValue}>{item.averageScore || '?'}</Text>/100
              </Text>
            </View>
          </View>

          {isLoggedIn ? (
            <View style={styles.resultActionRow}>
              <Pressable
                onPress={() => openAnimeDetail(item.id)}
                style={({ pressed }) => [
                  styles.detailButton,
                  pressed ? styles.detailButtonPressed : null,
                ]}
              >
                <Text style={[styles.detailButtonText, { fontSize: layout.buttonTextSize }]}>View Details</Text>
              </Pressable>
              {isOnList ? (
                <View style={styles.onListBadge}>
                  <Text style={[styles.onListBadgeText, { fontSize: layout.buttonTextSize }]}>On Your List</Text>
                </View>
              ) : (
                <Pressable
                  onPress={async () => {
                    clearMessages();
                    const success = await addToList(item);
                    if (success) {
                      markAnimeOnList(item.id);
                    }
                  }}
                  style={({ pressed }) => [
                    styles.addButton,
                    pressed ? styles.addButtonPressed : null,
                  ]}
                >
                  <Text style={[styles.addButtonText, { fontSize: layout.buttonTextSize }]}>Add to List</Text>
                </Pressable>
              )}
            </View>
          ) : (
            <View style={styles.resultActionRow}>
              <Pressable
                onPress={() => openAnimeDetail(item.id)}
                style={({ pressed }) => [
                  styles.detailButton,
                  pressed ? styles.detailButtonPressed : null,
                ]}
              >
                <Text style={[styles.detailButtonText, { fontSize: layout.buttonTextSize }]}>View Details</Text>
              </Pressable>
              <Text style={[styles.loginHint, { fontSize: layout.helperSize, lineHeight: layout.bodyLineHeight }]}>
                Login to save this title
              </Text>
            </View>
          )}
        </View>
      </View>
    );
  };

  const handleToggleChange = async (
    key: (typeof FILTER_TOGGLES)[number]['key'],
    value: boolean,
  ) => {
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

  const handleRefresh = useCallback(async () => {
    const normalized = query.trim();
    if (normalized.length < 2) {
      return;
    }

    setRefreshing(true);
    try {
      await search(normalized);
    } finally {
      setRefreshing(false);
    }
  }, [query, search]);

  return (
    <View style={styles.screen}>
      <FlatList
        data={results}
        keyExtractor={(item) => String(item.id)}
        keyboardShouldPersistTaps="handled"
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
        ListHeaderComponent={
          <View style={[styles.contentInner, styles.header, { maxWidth: layout.contentMaxWidth }]}>
            <Text style={styles.eyebrow}>Catalog Search</Text>
            <Text style={[styles.title, { fontSize: layout.titleSize, lineHeight: layout.titleLineHeight }]}>
              Search the anime catalog.
            </Text>
            <Text style={[styles.subtitle, { fontSize: layout.bodySize, lineHeight: layout.bodyLineHeight }]}>
              Start with a known title or franchise fragment, then refine the result set with the
              same core filters used by the web app.
            </Text>

            <TextInput
              autoCapitalize="none"
              autoCorrect={false}
              onChangeText={setQuery}
              placeholder="Search anime..."
              placeholderTextColor="rgba(255,255,255,0.35)"
              style={[
                styles.searchInput,
                {
                  paddingVertical: layout.inputVerticalPadding,
                  fontSize: layout.inputSize,
                },
              ]}
              value={query}
            />

            <Text style={[styles.helperText, { fontSize: layout.helperSize, lineHeight: layout.bodyLineHeight }]}>
              {helperText}
            </Text>

            <View style={styles.filterPanel}>
              <View style={[styles.filterPanelHeader, { paddingHorizontal: layout.compactCardPadding }]}>
                <Text style={[styles.filterPanelEyebrow, { fontSize: layout.helperSize }]}>Result Filters</Text>
                <Text style={[styles.filterPanelCopy, { fontSize: layout.helperSize, lineHeight: layout.bodyLineHeight }]}>
                  Trim formats you do not want before the result list renders.
                </Text>
              </View>

              {FILTER_TOGGLES.map((toggle, index) => (
                <View
                  key={toggle.key}
                  style={[
                    styles.filterRow,
                    index === FILTER_TOGGLES.length - 1 ? styles.filterRowLast : null,
                    { paddingHorizontal: layout.compactCardPadding },
                  ]}
                >
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
            </View>

            <Text style={[styles.policyNote, { fontSize: layout.helperSize, lineHeight: layout.bodyLineHeight }]}>
              18+ content stays filtered by default and requires explicit opt-in before it can be shown.
            </Text>

            {loading ? (
              <View style={styles.loadingRow}>
                <ActivityIndicator color="#e94560" />
                <Text style={[styles.loadingText, { fontSize: layout.bodySize }]}>Loading results...</Text>
              </View>
            ) : null}

            {error ? <Text style={[styles.errorText, { fontSize: layout.bodySize }]}>{error}</Text> : null}
            {addError ? <Text style={[styles.errorText, { fontSize: layout.bodySize }]}>{addError}</Text> : null}
            {message ? <Text style={[styles.successText, { fontSize: layout.bodySize }]}>{message}</Text> : null}
          </View>
        }
        ListEmptyComponent={
          !loading && query.trim().length >= 2 ? (
            <View style={[styles.contentInner, styles.emptyState, { maxWidth: layout.contentMaxWidth }]}>
              <Text style={styles.emptyTitle}>No results found.</Text>
              <Text style={[styles.emptyCopy, { fontSize: layout.bodySize, lineHeight: layout.bodyLineHeight }]}>
                Try a broader title fragment or switch off one of the stricter format filters.
              </Text>
            </View>
          ) : null
        }
        renderItem={renderAnimeCard}
        ItemSeparatorComponent={() => <View style={styles.separator} />}
      />
    </View>
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
  header: {
    marginBottom: 20,
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
  subtitle: {
    marginBottom: 18,
    color: 'rgba(255,255,255,0.72)',
  },
  searchInput: {
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.1)',
    borderRadius: 16,
    paddingHorizontal: 16,
    color: '#ffffff',
    backgroundColor: '#12122a',
  },
  helperText: {
    marginTop: 10,
    color: 'rgba(255,255,255,0.58)',
  },
  policyNote: {
    marginTop: 10,
    color: 'rgba(255,255,255,0.52)',
  },
  filterPanel: {
    marginTop: 18,
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
  filterLabel: {
    flex: 1,
    paddingRight: 12,
    fontWeight: '600',
    color: '#ffffff',
  },
  loadingRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    marginTop: 16,
  },
  loadingText: {
    color: 'rgba(255,255,255,0.7)',
  },
  errorText: {
    marginTop: 14,
    borderWidth: 1,
    borderColor: 'rgba(255,107,107,0.4)',
    borderRadius: 14,
    paddingHorizontal: 14,
    paddingVertical: 12,
    color: '#ff8d8d',
    backgroundColor: 'rgba(123,33,33,0.25)',
  },
  successText: {
    marginTop: 14,
    borderWidth: 1,
    borderColor: 'rgba(111,207,151,0.32)',
    borderRadius: 14,
    paddingHorizontal: 14,
    paddingVertical: 12,
    color: '#9ef0b8',
    backgroundColor: 'rgba(25,84,54,0.24)',
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
  coverThumb: {
    width: 72,
    height: 102,
    borderRadius: 14,
    backgroundColor: '#171733',
  },
  coverFallback: {
    width: 72,
    height: 102,
    borderRadius: 14,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#171733',
  },
  coverFallbackText: {
    fontSize: 11,
    fontWeight: '600',
    color: 'rgba(255,255,255,0.5)',
    textAlign: 'center',
  },
  resultBody: {
    flex: 1,
    gap: 6,
  },
  resultTitle: {
    fontWeight: '700',
    color: '#ffffff',
  },
  resultMeta: {
    color: 'rgba(255,255,255,0.6)',
  },
  resultGenres: {
    color: 'rgba(255,255,255,0.76)',
  },
  resultScore: {
    color: 'rgba(255,255,255,0.62)',
  },
  resultScoreValue: {
    fontWeight: '700',
    color: '#ffffff',
  },
  addButton: {
    flexGrow: 1,
    marginTop: 14,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 14,
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
    flexGrow: 1,
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
  loginHint: {
    flexShrink: 1,
    paddingTop: 12,
    color: '#ff9dad',
  },
  emptyState: {
    marginTop: 8,
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
  separator: {
    height: 12,
  },
});
