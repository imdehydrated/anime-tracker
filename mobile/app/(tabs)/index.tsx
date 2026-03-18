import { useCallback, useEffect, useMemo, useState } from 'react';
import { router } from 'expo-router';
import { Image } from 'expo-image';
import {
  ActivityIndicator,
  Pressable,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  useWindowDimensions,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { getPopularAnime } from '../../src/api/animeApi';
import { useAuth } from '../../src/context/AuthContext';
import { AnimeSummary } from '../../src/types/anime';

const QUICK_ACTIONS = [
  {
    title: 'Search',
    copy: 'Look up a title you know.',
    route: '/search',
  },
  {
    title: 'Smart Rec',
    copy: 'Open the recommendation workspace for semantic and taste-aware discovery.',
    route: '/smart-rec',
  },
] as const;

function getAnimeTitle(anime: AnimeSummary) {
  return anime.title?.english || anime.title?.romaji || anime.title?.nativeTitle || 'Unknown title';
}

function getCoverUrl(anime: AnimeSummary) {
  return typeof anime.coverImage === 'string'
    ? anime.coverImage
    : anime.coverImage?.large || anime.coverImage?.medium || null;
}

export default function HomeScreen() {
  const { isLoggedIn, username } = useAuth();
  const { width } = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const [featured, setFeatured] = useState<AnimeSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState('');

  const loadFeatured = useCallback(async (refresh = false) => {
    if (refresh) {
      setRefreshing(true);
    } else {
      setLoading(true);
    }

    try {
      const items = await getPopularAnime(12);
      setFeatured(items);
      setError('');
    } catch {
      setError('Could not load the popular anime strip.');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    let isMounted = true;

    void (async () => {
      if (!isMounted) return;
      await loadFeatured();
    })();

    return () => {
      isMounted = false;
    };
  }, [loadFeatured]);

  const greeting = useMemo(() => {
    if (isLoggedIn) {
      return `Welcome back${username ? `, ${username}` : ''}`;
    }
    return 'Find your next anime';
  }, [isLoggedIn, username]);

  const layout = useMemo(() => {
    const isCompact = width < 370;
    const isLargePhone = width >= 430;
    const horizontalPadding = isCompact ? 16 : 20;
    const topPadding = Math.max(insets.top + 12, 24);
    const bottomPadding = Math.max(insets.bottom + 24, 32);
    const heroPadding = isCompact ? 16 : 20;
    const titleSize = isCompact ? 24 : isLargePhone ? 32 : 28;
    const titleLineHeight = titleSize + 6;
    const bodySize = isCompact ? 14 : 15;
    const bodyLineHeight = isCompact ? 21 : 22;
    const sectionTitleSize = isCompact ? 20 : 22;
    const quickCardTitleSize = isCompact ? 16 : 17;
    const availableWidth = Math.max(width - horizontalPadding * 2, 280);
    const posterWidth = Math.max(
      122,
      Math.min(158, Math.round(availableWidth * (isCompact ? 0.39 : 0.35))),
    );
    const posterHeight = Math.round(posterWidth * 1.42);

    return {
      horizontalPadding,
      topPadding,
      bottomPadding,
      heroPadding,
      titleSize,
      titleLineHeight,
      bodySize,
      bodyLineHeight,
      sectionTitleSize,
      quickCardTitleSize,
      posterWidth,
      posterHeight,
    };
  }, [insets.bottom, insets.top, width]);

  return (
    <ScrollView
      style={styles.screen}
      refreshControl={
        <RefreshControl refreshing={refreshing} onRefresh={() => void loadFeatured(true)} tintColor="#e94560" />
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
      <View style={styles.contentInner}>
        <View style={[styles.heroCard, { padding: layout.heroPadding }]}>
          <Text style={styles.eyebrow}>{greeting}</Text>
          <Text style={[styles.title, { fontSize: layout.titleSize, lineHeight: layout.titleLineHeight }]}>
            AniRec makes anime discovery feel focused.
          </Text>
          <Text style={[styles.copy, { fontSize: layout.bodySize, lineHeight: layout.bodyLineHeight }]}>
            Browse the catalog, open recommendation flows, and keep your list moving without leaving the app.
          </Text>

          <View style={styles.quickGrid}>
            {QUICK_ACTIONS.map((action) => (
              <Pressable
                key={action.title}
                onPress={() => router.push(action.route as any)}
                style={({ pressed }) => [
                  styles.quickCard,
                  pressed ? styles.quickCardPressed : null,
                ]}
              >
                <Text style={[styles.quickCardTitle, { fontSize: layout.quickCardTitleSize }]}>
                  {action.title}
                </Text>
                <Text style={[styles.quickCardCopy, { fontSize: layout.bodySize, lineHeight: layout.bodyLineHeight }]}>
                  {action.copy}
                </Text>
              </Pressable>
            ))}

            <Pressable
              onPress={() => router.push((isLoggedIn ? '/my-list' : '/login') as any)}
              style={({ pressed }) => [
                styles.quickCard,
                styles.quickCardAccent,
                pressed ? styles.quickCardPressed : null,
              ]}
            >
              <Text style={[styles.quickCardTitle, { fontSize: layout.quickCardTitleSize }]}>
                {isLoggedIn ? 'My List' : 'Login'}
              </Text>
              <Text style={[styles.quickCardCopy, { fontSize: layout.bodySize, lineHeight: layout.bodyLineHeight }]}>
                {isLoggedIn
                  ? 'Jump into your tracked anime, scores, and imports.'
                  : 'Sign in to save titles and unlock personalized recommendations.'}
              </Text>
            </Pressable>
          </View>
        </View>

        <View style={styles.sectionHeader}>
          <Text style={[styles.sectionTitle, { fontSize: layout.sectionTitleSize }]}>
            Popular right now
          </Text>
          <Text style={[styles.sectionCopy, { fontSize: layout.bodySize, lineHeight: layout.bodyLineHeight }]}>
            The strip below is loaded from the same local popular-catalog endpoint used by the web app.
          </Text>
        </View>

        {loading ? (
          <View style={styles.stateCard}>
            <Text style={styles.stateKicker}>Popular Catalog</Text>
            <View style={styles.loadingCard}>
              <ActivityIndicator color="#e94560" />
              <Text style={[styles.loadingText, { fontSize: layout.bodySize }]}>
                Loading popular anime...
              </Text>
            </View>
            <Text style={[styles.stateCopy, { fontSize: layout.bodySize, lineHeight: layout.bodyLineHeight }]}>
              Pulling the same popular strip used by the web app.
            </Text>
          </View>
        ) : null}

        {error ? (
          <View style={styles.stateCard}>
            <Text style={styles.stateKicker}>Popular Catalog</Text>
            <Text style={[styles.stateTitle, { fontSize: layout.sectionTitleSize }]}>Could not load featured anime.</Text>
            <Text style={[styles.errorText, { fontSize: layout.bodySize, marginBottom: 0 }]}>{error}</Text>
          </View>
        ) : null}

        {featured.length > 0 ? (
          <ScrollView
            horizontal
            showsHorizontalScrollIndicator={false}
            contentContainerStyle={styles.featuredStrip}
          >
            {featured.map((anime) => {
              const title = getAnimeTitle(anime);
              const coverUrl = getCoverUrl(anime);

              return (
                <Pressable
                  key={anime.id}
                  onPress={() =>
                    router.push({ pathname: '/anime/[id]', params: { id: String(anime.id) } } as any)
                  }
                  style={({ pressed }) => [
                    styles.posterCard,
                    { width: layout.posterWidth },
                    pressed ? styles.posterCardPressed : null,
                  ]}
                >
                  {coverUrl ? (
                    <Image
                      contentFit="cover"
                      source={{ uri: coverUrl }}
                      style={[styles.posterImage, { height: layout.posterHeight }]}
                    />
                  ) : (
                    <View style={[styles.posterFallback, { height: layout.posterHeight }]}>
                      <Text style={styles.posterFallbackText}>No Cover</Text>
                    </View>
                  )}
                  <Text
                    numberOfLines={2}
                    style={[styles.posterTitle, { fontSize: layout.bodySize, lineHeight: layout.bodyLineHeight }]}
                  >
                    {title}
                  </Text>
                  <Text style={[styles.posterMeta, { fontSize: Math.max(layout.bodySize - 1, 13) }]}>
                    {anime.averageScore ? `${anime.averageScore}/100` : 'Unscored'}
                  </Text>
                </Pressable>
              );
            })}
          </ScrollView>
        ) : null}

        {!loading && !error && featured.length === 0 ? (
          <View style={styles.stateCard}>
            <Text style={styles.stateKicker}>Popular Catalog</Text>
            <Text style={[styles.stateTitle, { fontSize: layout.sectionTitleSize }]}>Nothing is featured right now.</Text>
            <Text style={[styles.stateCopy, { fontSize: layout.bodySize, lineHeight: layout.bodyLineHeight }]}>
              Use Search or Smart Rec for now while the popular strip repopulates.
            </Text>
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
  content: {
  },
  contentInner: {
    width: '100%',
    maxWidth: 860,
    alignSelf: 'center',
  },
  heroCard: {
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.08)',
    borderRadius: 24,
    padding: 20,
    backgroundColor: '#12122a',
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
    marginBottom: 20,
    color: 'rgba(255,255,255,0.72)',
  },
  quickGrid: {
    gap: 12,
  },
  quickCard: {
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.08)',
    borderRadius: 18,
    padding: 16,
    backgroundColor: '#171733',
  },
  quickCardAccent: {
    borderColor: 'rgba(233,69,96,0.28)',
  },
  quickCardPressed: {
    opacity: 0.92,
    transform: [{ scale: 0.99 }],
  },
  quickCardTitle: {
    marginBottom: 6,
    fontWeight: '700',
    color: '#ffffff',
  },
  quickCardCopy: {
    color: 'rgba(255,255,255,0.68)',
  },
  sectionHeader: {
    marginTop: 24,
    marginBottom: 14,
  },
  sectionTitle: {
    marginBottom: 6,
    fontWeight: '700',
    color: '#ffffff',
  },
  sectionCopy: {
    color: 'rgba(255,255,255,0.62)',
  },
  stateCard: {
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.08)',
    borderRadius: 18,
    padding: 16,
    backgroundColor: '#12122a',
  },
  stateKicker: {
    marginBottom: 8,
    fontSize: 12,
    fontWeight: '700',
    letterSpacing: 1.2,
    textTransform: 'uppercase',
    color: '#e94560',
  },
  stateTitle: {
    marginBottom: 8,
    fontWeight: '700',
    color: '#ffffff',
  },
  stateCopy: {
    color: 'rgba(255,255,255,0.68)',
  },
  loadingCard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    marginBottom: 8,
  },
  loadingText: {
    color: 'rgba(255,255,255,0.7)',
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
  featuredStrip: {
    gap: 14,
    paddingRight: 4,
  },
  posterCard: {
  },
  posterCardPressed: {
    opacity: 0.92,
  },
  posterImage: {
    width: '100%',
    borderRadius: 18,
    backgroundColor: '#171733',
  },
  posterFallback: {
    width: '100%',
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 18,
    backgroundColor: '#171733',
  },
  posterFallbackText: {
    fontSize: 13,
    fontWeight: '600',
    color: 'rgba(255,255,255,0.5)',
  },
  posterTitle: {
    marginTop: 10,
    fontWeight: '700',
    color: '#ffffff',
  },
  posterMeta: {
    marginTop: 4,
    color: 'rgba(255,255,255,0.62)',
  },
});
