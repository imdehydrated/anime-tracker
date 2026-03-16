import { useMemo } from 'react';
import {
  ActivityIndicator,
  FlatList,
  Pressable,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';
import { useAuth } from '../../src/context/AuthContext';
import { useAddToList } from '../../src/hooks/useAddToList';
import { useDebounceSearch } from '../../src/hooks/useDebounceSearch';
import {
  SEARCH_FILTER_DEFAULTS,
  useRecommendationFilters,
} from '../../src/hooks/useRecommendationFilters';
import { AnimeSummary } from '../../src/types/anime';

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
    .join(' • ');
}

export default function SearchScreen() {
  const { isLoggedIn } = useAuth();
  const { filters, setFilters } = useRecommendationFilters(SEARCH_FILTER_DEFAULTS);
  const { addToList, message, error: addError, clearMessages } = useAddToList();
  const { query, setQuery, results, loading, error } = useDebounceSearch(250, 2, 20, filters);

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

    return (
      <View style={styles.resultCard}>
        <View style={styles.resultBody}>
          <Text style={styles.resultTitle}>{title}</Text>
          {meta ? <Text style={styles.resultMeta}>{meta}</Text> : null}
          {item.genres?.length ? (
            <Text style={styles.resultGenres}>{item.genres.slice(0, 3).join(' • ')}</Text>
          ) : null}
          <Text style={styles.resultScore}>
            Score: <Text style={styles.resultScoreValue}>{item.averageScore || '?'}</Text>/100
          </Text>
        </View>

        {isLoggedIn ? (
          <Pressable
            onPress={async () => {
              clearMessages();
              await addToList(item);
            }}
            style={({ pressed }) => [
              styles.addButton,
              pressed ? styles.addButtonPressed : null,
            ]}
          >
            <Text style={styles.addButtonText}>Add to List</Text>
          </Pressable>
        ) : (
          <Text style={styles.loginHint}>Login to save this title</Text>
        )}
      </View>
    );
  };

  return (
    <View style={styles.screen}>
      <FlatList
        data={results}
        keyExtractor={(item) => String(item.id)}
        keyboardShouldPersistTaps="handled"
        contentContainerStyle={styles.content}
        ListHeaderComponent={
          <View style={styles.header}>
            <Text style={styles.eyebrow}>Catalog Search</Text>
            <Text style={styles.title}>Search the anime catalog.</Text>
            <Text style={styles.subtitle}>
              Start with a known title or franchise fragment, then refine the result set with the
              same core filters used by the web app.
            </Text>

            <TextInput
              autoCapitalize="none"
              autoCorrect={false}
              onChangeText={setQuery}
              placeholder="Search anime..."
              placeholderTextColor="rgba(255,255,255,0.35)"
              style={styles.searchInput}
              value={query}
            />

            <Text style={styles.helperText}>{helperText}</Text>

            <View style={styles.filterPanel}>
              {FILTER_TOGGLES.map((toggle) => (
                <View key={toggle.key} style={styles.filterRow}>
                  <Text style={styles.filterLabel}>{toggle.label}</Text>
                  <Switch
                    trackColor={{ false: '#2c2c44', true: 'rgba(233,69,96,0.45)' }}
                    thumbColor={filters[toggle.key] ? '#e94560' : '#f4f4f5'}
                    value={Boolean(filters[toggle.key])}
                    onValueChange={(value) =>
                      setFilters((prev) => ({ ...prev, [toggle.key]: value }))
                    }
                  />
                </View>
              ))}
            </View>

            {loading ? (
              <View style={styles.loadingRow}>
                <ActivityIndicator color="#e94560" />
                <Text style={styles.loadingText}>Loading results...</Text>
              </View>
            ) : null}

            {error ? <Text style={styles.errorText}>{error}</Text> : null}
            {addError ? <Text style={styles.errorText}>{addError}</Text> : null}
            {message ? <Text style={styles.successText}>{message}</Text> : null}
          </View>
        }
        ListEmptyComponent={
          !loading && query.trim().length >= 2 ? (
            <View style={styles.emptyState}>
              <Text style={styles.emptyTitle}>No results found.</Text>
              <Text style={styles.emptyCopy}>
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
  content: {
    paddingHorizontal: 20,
    paddingTop: 24,
    paddingBottom: 32,
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
    fontSize: 28,
    fontWeight: '700',
    color: '#ffffff',
  },
  subtitle: {
    marginBottom: 18,
    fontSize: 15,
    lineHeight: 22,
    color: 'rgba(255,255,255,0.72)',
  },
  searchInput: {
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.1)',
    borderRadius: 16,
    paddingHorizontal: 16,
    paddingVertical: 15,
    fontSize: 16,
    color: '#ffffff',
    backgroundColor: '#12122a',
  },
  helperText: {
    marginTop: 10,
    fontSize: 13,
    lineHeight: 20,
    color: 'rgba(255,255,255,0.58)',
  },
  filterPanel: {
    marginTop: 18,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.08)',
    borderRadius: 18,
    overflow: 'hidden',
    backgroundColor: '#12122a',
  },
  filterRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 14,
    borderBottomWidth: 1,
    borderBottomColor: 'rgba(255,255,255,0.06)',
  },
  filterLabel: {
    flex: 1,
    paddingRight: 12,
    fontSize: 14,
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
    fontSize: 14,
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
    padding: 16,
    backgroundColor: '#12122a',
  },
  resultBody: {
    gap: 6,
  },
  resultTitle: {
    fontSize: 18,
    fontWeight: '700',
    color: '#ffffff',
  },
  resultMeta: {
    fontSize: 13,
    lineHeight: 20,
    color: 'rgba(255,255,255,0.6)',
  },
  resultGenres: {
    fontSize: 13,
    lineHeight: 20,
    color: 'rgba(255,255,255,0.76)',
  },
  resultScore: {
    fontSize: 13,
    lineHeight: 20,
    color: 'rgba(255,255,255,0.62)',
  },
  resultScoreValue: {
    fontWeight: '700',
    color: '#ffffff',
  },
  addButton: {
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
    fontSize: 14,
    fontWeight: '700',
    color: '#ffffff',
  },
  loginHint: {
    marginTop: 14,
    fontSize: 13,
    lineHeight: 20,
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
    fontSize: 14,
    lineHeight: 22,
    color: 'rgba(255,255,255,0.68)',
  },
  separator: {
    height: 12,
  },
});
