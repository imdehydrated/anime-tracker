import { useEffect, useMemo, useState } from 'react';
import { router, useLocalSearchParams } from 'expo-router';
import { Image } from 'expo-image';
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { getAnimeById } from '../../src/api/animeApi';
import { getApiError } from '../../src/api/client';
import { useAuth } from '../../src/context/AuthContext';
import { useAddToList } from '../../src/hooks/useAddToList';
import { useUserListIndex } from '../../src/hooks/useUserListIndex';
import { AnimeDetail, AnimeRelation } from '../../src/types/anime';
import { useResponsiveLayout } from '../../src/ui/useResponsiveLayout';

const DESCRIPTION_PREVIEW_CHARS = 900;

function formatTitle(anime: AnimeDetail | null) {
  return anime?.title?.english || anime?.title?.romaji || anime?.title?.nativeTitle || 'Unknown title';
}

function formatEnumLabel(value: string | null | undefined) {
  if (!value) return 'Unknown';
  return value
    .toLowerCase()
    .replace(/_/g, ' ')
    .replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function formatSeasonLabel(season: string | null | undefined, seasonYear: number | null | undefined) {
  if (season && seasonYear) return `${formatEnumLabel(season)} ${seasonYear}`;
  if (seasonYear) return String(seasonYear);
  if (season) return formatEnumLabel(season);
  return 'Unknown';
}

function normalizeDescription(rawDescription: string | null | undefined) {
  if (!rawDescription || typeof rawDescription !== 'string') return '';
  return rawDescription
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/(p|div|h1|h2|h3|h4|h5|h6)>/gi, '\n')
    .replace(/<li[^>]*>/gi, '- ')
    .replace(/<\/li>/gi, '\n')
    .replace(/<[^>]+>/g, '')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/\r\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .replace(/[ \t]{2,}/g, ' ')
    .trim();
}

function getPrimaryStudio(anime: AnimeDetail | null) {
  const studios = Array.isArray(anime?.studios) ? anime.studios : [];
  for (const studio of studios) {
    if (typeof studio === 'string' && studio.trim().length > 0) {
      return studio.trim();
    }
    if (
      typeof studio !== 'string' &&
      studio?.name &&
      typeof studio.name === 'string' &&
      studio.name.trim().length > 0
    ) {
      return studio.name.trim();
    }
  }
  return null;
}

function getRelationTitle(relation: AnimeRelation) {
  return relation.title?.english || relation.title?.romaji || relation.title?.nativeTitle || `Anime #${relation.id}`;
}

function buildRelationItems(anime: AnimeDetail | null) {
  const relations = Array.isArray(anime?.relations) ? anime.relations : [];
  const seen = new Set<number>();

  return relations
    .filter((relation) => {
      const relationId = Number(relation?.id);
      if (!Number.isFinite(relationId) || relationId <= 0 || relationId === Number(anime?.id)) {
        return false;
      }
      if (seen.has(relationId)) return false;
      seen.add(relationId);
      return true;
    })
    .map((relation) => ({
      id: relation.id,
      relationType: formatEnumLabel(relation.relationType),
      title: getRelationTitle(relation),
    }));
}

export default function AnimeDetailScreen() {
  const { id } = useLocalSearchParams<{ id?: string }>();
  const layout = useResponsiveLayout();
  const { isLoggedIn } = useAuth();
  const { addToList, message, error, clearMessages } = useAddToList();
  const { hasAnime, markAnimeOnList } = useUserListIndex();
  const [anime, setAnime] = useState<AnimeDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [fetchError, setFetchError] = useState('');
  const [descriptionExpanded, setDescriptionExpanded] = useState(false);

  useEffect(() => {
    let isCancelled = false;

    async function fetchAnime() {
      if (!id) {
        setFetchError('Anime not found.');
        setLoading(false);
        return;
      }

      setLoading(true);
      setFetchError('');
      setDescriptionExpanded(false);
      clearMessages();

      try {
        const data = await getAnimeById(id);
        if (!isCancelled) {
          setAnime(data);
        }
      } catch (err) {
        if (!isCancelled) {
          setFetchError(getApiError(err, 'Anime not found'));
        }
      } finally {
        if (!isCancelled) {
          setLoading(false);
        }
      }
    }

    void fetchAnime();
    return () => {
      isCancelled = true;
    };
  }, [clearMessages, id]);

  const normalizedDescription = useMemo(
    () => normalizeDescription(anime?.description),
    [anime?.description],
  );
  const descriptionNeedsCollapse = normalizedDescription.length > DESCRIPTION_PREVIEW_CHARS;
  const renderedDescription = useMemo(() => {
    if (!normalizedDescription) return '';
    if (descriptionExpanded || !descriptionNeedsCollapse) return normalizedDescription;
    return `${normalizedDescription.slice(0, DESCRIPTION_PREVIEW_CHARS).trim()}...`;
  }, [descriptionExpanded, descriptionNeedsCollapse, normalizedDescription]);

  const primaryStudio = useMemo(() => getPrimaryStudio(anime), [anime]);
  const relationItems = useMemo(() => buildRelationItems(anime), [anime]);

  const detailStats = useMemo(
    () => [
      { label: 'Score', value: anime?.averageScore ? `${anime.averageScore}/100` : 'Unknown' },
      { label: 'Episodes', value: anime?.episodes || '?' },
      { label: 'Status', value: formatEnumLabel(anime?.status) },
      { label: 'Format', value: formatEnumLabel(anime?.format) },
      { label: 'Season', value: formatSeasonLabel(anime?.season, anime?.seasonYear) },
      { label: 'Popularity', value: anime?.popularity ? anime.popularity.toLocaleString() : 'Unknown' },
      { label: 'Studio', value: primaryStudio || 'Unknown' },
    ],
    [
      anime?.averageScore,
      anime?.episodes,
      anime?.format,
      anime?.popularity,
      anime?.season,
      anime?.seasonYear,
      anime?.status,
      primaryStudio,
    ],
  );

  const coverWidth = layout.isCompact ? 132 : 164;
  const coverHeight = Math.round(coverWidth * 1.42);
  const isOnList = anime ? hasAnime(anime.id) : false;

  if (loading && !anime) {
    return (
      <View style={styles.loadingScreen}>
        <View style={[styles.stateCard, { padding: layout.cardPadding }]}>
          <Text style={styles.stateKicker}>Anime Detail</Text>
          <ActivityIndicator color="#e94560" size="large" />
          <Text style={[styles.stateTitle, { fontSize: layout.sectionTitleSize }]}>Loading anime...</Text>
          <Text style={[styles.stateCopy, { fontSize: layout.bodySize, lineHeight: layout.bodyLineHeight }]}>
            Pulling the full title, stats, and series navigation from the catalog.
          </Text>
        </View>
      </View>
    );
  }

  if (fetchError && !anime) {
    return (
      <View style={styles.loadingScreen}>
        <View style={[styles.stateCard, { padding: layout.cardPadding }]}>
          <Text style={styles.stateKicker}>Anime Detail</Text>
          <Text style={[styles.stateTitle, { fontSize: layout.sectionTitleSize }]}>Could not load this anime.</Text>
          <Text style={[styles.errorStandalone, { fontSize: layout.bodySize, lineHeight: layout.bodyLineHeight }]}>
            {fetchError}
          </Text>
          <Pressable onPress={() => router.back()} style={({ pressed }) => [styles.stateButton, pressed ? styles.stateButtonPressed : null]}>
            <Text style={[styles.stateButtonText, { fontSize: layout.buttonTextSize }]}>Go Back</Text>
          </Pressable>
        </View>
      </View>
    );
  }

  if (!anime) {
    return (
      <View style={styles.loadingScreen}>
        <View style={[styles.stateCard, { padding: layout.cardPadding }]}>
          <Text style={styles.stateKicker}>Anime Detail</Text>
          <Text style={[styles.stateTitle, { fontSize: layout.sectionTitleSize }]}>Anime not found.</Text>
          <Text style={[styles.stateCopy, { fontSize: layout.bodySize, lineHeight: layout.bodyLineHeight }]}>
            The title could not be loaded from the current catalog entry.
          </Text>
          <Pressable onPress={() => router.back()} style={({ pressed }) => [styles.stateButton, pressed ? styles.stateButtonPressed : null]}>
            <Text style={[styles.stateButtonText, { fontSize: layout.buttonTextSize }]}>Go Back</Text>
          </Pressable>
        </View>
      </View>
    );
  }

  return (
    <ScrollView
      style={styles.screen}
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
        <View style={styles.topBar}>
          <Pressable onPress={() => router.back()} style={styles.backButton}>
            <Text style={styles.backButtonText}>Back</Text>
          </Pressable>
        </View>

        {anime.bannerImage ? (
          <Image
            contentFit="cover"
            source={{ uri: anime.bannerImage }}
            style={[styles.bannerImage, { height: layout.isCompact ? 180 : 220 }]}
          />
        ) : null}

        <View style={[styles.mainCard, { padding: layout.cardPadding }]}>
          <View style={[styles.headerSection, !layout.isCompact ? styles.headerSectionWide : null]}>
            {anime.coverImage ? (
              <Image
                contentFit="cover"
                source={{
                  uri:
                    typeof anime.coverImage === 'string'
                      ? anime.coverImage
                      : anime.coverImage.large || anime.coverImage.medium || undefined,
                }}
                style={[styles.coverImage, { width: coverWidth, height: coverHeight }]}
              />
            ) : null}

            <View style={styles.headerBody}>
              <Text style={[styles.title, { fontSize: layout.titleSize, lineHeight: layout.titleLineHeight }]}>
                {formatTitle(anime)}
              </Text>

              {anime.title?.english && anime.title?.romaji ? (
                <Text style={[styles.subtitle, { fontSize: layout.bodySize, lineHeight: layout.bodyLineHeight }]}>
                  {anime.title.romaji}
                </Text>
              ) : null}

              {anime.genres?.length ? (
                <View style={styles.genreRow}>
                  {anime.genres.map((genre) => (
                    <View key={genre} style={styles.genreChip}>
                      <Text style={styles.genreChipText}>{genre}</Text>
                    </View>
                  ))}
                </View>
              ) : null}

              <View style={styles.actionRow}>
                {isLoggedIn ? (
                  isOnList ? (
                    <View style={styles.onListBadge}>
                      <Text style={[styles.onListBadgeText, { fontSize: layout.buttonTextSize }]}>On Your List</Text>
                    </View>
                  ) : (
                    <Pressable
                      onPress={async () => {
                        const success = await addToList(anime);
                        if (success) {
                          markAnimeOnList(anime.id);
                        }
                      }}
                      style={styles.primaryButton}
                    >
                      <Text style={[styles.primaryButtonText, { fontSize: layout.buttonTextSize }]}>Add to List</Text>
                    </Pressable>
                  )
                ) : (
                  <Pressable onPress={() => router.push('/login' as any)} style={styles.primaryButton}>
                    <Text style={[styles.primaryButtonText, { fontSize: layout.buttonTextSize }]}>Login to Add</Text>
                  </Pressable>
                )}
              </View>

              {error ? <Text style={[styles.messageError, { fontSize: layout.bodySize }]}>{error}</Text> : null}
              {message ? <Text style={[styles.messageSuccess, { fontSize: layout.bodySize }]}>{message}</Text> : null}
            </View>
          </View>

          <View style={styles.statsGrid}>
            {detailStats.map((stat) => (
              <View key={stat.label} style={styles.statCard}>
                <Text style={styles.statLabel}>{stat.label}</Text>
                <Text style={[styles.statValue, { fontSize: layout.bodySize }]}>{stat.value}</Text>
              </View>
            ))}
          </View>

          {renderedDescription ? (
            <View style={styles.section}>
              <Text style={[styles.sectionTitle, { fontSize: layout.sectionTitleSize }]}>Synopsis</Text>
              <View style={styles.sectionCard}>
                {renderedDescription.split('\n').map((line, index) => (
                  <Text
                    key={`${index}-${line.slice(0, 16)}`}
                    style={[styles.descriptionLine, { fontSize: layout.bodySize, lineHeight: layout.bodyLineHeight }]}
                  >
                    {line}
                  </Text>
                ))}
                {descriptionNeedsCollapse ? (
                  <Pressable onPress={() => setDescriptionExpanded((prev) => !prev)} style={styles.inlineLink}>
                    <Text style={styles.inlineLinkText}>
                      {descriptionExpanded ? 'Show less' : 'Show more'}
                    </Text>
                  </Pressable>
                ) : null}
              </View>
            </View>
          ) : null}

          {relationItems.length > 0 ? (
            <View style={styles.section}>
              <Text style={[styles.sectionTitle, { fontSize: layout.sectionTitleSize }]}>Series Navigation</Text>
              <View style={styles.relationGrid}>
                {relationItems.map((relation) => (
                  <Pressable
                    key={relation.id}
                    onPress={() =>
                      router.push({ pathname: '/anime/[id]', params: { id: String(relation.id) } } as any)
                    }
                    style={({ pressed }) => [
                      styles.relationCard,
                      pressed ? styles.relationCardPressed : null,
                    ]}
                  >
                    <View style={styles.relationCardBody}>
                      <Text style={styles.relationType}>{relation.relationType}</Text>
                      <Text style={styles.relationTitle}>{relation.title}</Text>
                    </View>
                    <Text style={styles.relationActionText}>Open</Text>
                  </Pressable>
                ))}
              </View>
            </View>
          ) : null}
        </View>
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
  loadingScreen: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 24,
    backgroundColor: '#0f0f1a',
  },
  stateCard: {
    width: '100%',
    maxWidth: 520,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.08)',
    borderRadius: 24,
    alignItems: 'center',
    backgroundColor: '#12122a',
  },
  stateKicker: {
    marginBottom: 10,
    fontSize: 12,
    fontWeight: '700',
    letterSpacing: 1.3,
    textTransform: 'uppercase',
    color: '#e94560',
  },
  stateTitle: {
    marginTop: 14,
    marginBottom: 8,
    fontWeight: '700',
    color: '#ffffff',
    textAlign: 'center',
  },
  stateCopy: {
    color: 'rgba(255,255,255,0.68)',
    textAlign: 'center',
  },
  errorStandalone: {
    marginBottom: 16,
    color: '#ff8d8d',
    textAlign: 'center',
  },
  stateButton: {
    marginTop: 18,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.12)',
    borderRadius: 14,
    paddingHorizontal: 18,
    paddingVertical: 12,
    backgroundColor: '#171733',
  },
  stateButtonPressed: {
    opacity: 0.9,
  },
  stateButtonText: {
    fontWeight: '700',
    color: '#ffffff',
  },
  topBar: {
    alignItems: 'flex-start',
    marginBottom: 14,
  },
  backButton: {
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: 999,
    backgroundColor: '#171733',
  },
  backButtonText: {
    fontSize: 14,
    fontWeight: '600',
    color: '#ffffff',
  },
  bannerImage: {
    width: '100%',
    marginBottom: 16,
    borderRadius: 24,
    backgroundColor: '#171733',
  },
  mainCard: {
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.08)',
    borderRadius: 24,
    backgroundColor: '#12122a',
  },
  headerSection: {
    gap: 18,
  },
  headerSectionWide: {
    flexDirection: 'row',
    alignItems: 'flex-start',
  },
  coverImage: {
    borderRadius: 18,
    backgroundColor: '#171733',
  },
  headerBody: {
    flex: 1,
    gap: 12,
  },
  title: {
    fontWeight: '700',
    color: '#ffffff',
  },
  subtitle: {
    color: 'rgba(255,255,255,0.72)',
  },
  genreRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  genreChip: {
    paddingHorizontal: 10,
    paddingVertical: 7,
    borderRadius: 999,
    backgroundColor: '#171733',
  },
  genreChipText: {
    fontSize: 12,
    fontWeight: '600',
    color: '#ffffff',
  },
  actionRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 12,
  },
  primaryButton: {
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 14,
    paddingHorizontal: 18,
    paddingVertical: 14,
    backgroundColor: '#e94560',
  },
  primaryButtonText: {
    fontWeight: '700',
    color: '#ffffff',
  },
  onListBadge: {
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: 'rgba(111,207,151,0.34)',
    borderRadius: 14,
    paddingHorizontal: 18,
    paddingVertical: 14,
    backgroundColor: 'rgba(25,84,54,0.24)',
  },
  onListBadgeText: {
    fontWeight: '700',
    color: '#9ef0b8',
  },
  messageError: {
    borderWidth: 1,
    borderColor: 'rgba(255,107,107,0.4)',
    borderRadius: 14,
    paddingHorizontal: 14,
    paddingVertical: 12,
    color: '#ff8d8d',
    backgroundColor: 'rgba(123,33,33,0.25)',
  },
  messageSuccess: {
    borderWidth: 1,
    borderColor: 'rgba(111,207,151,0.32)',
    borderRadius: 14,
    paddingHorizontal: 14,
    paddingVertical: 12,
    color: '#9ef0b8',
    backgroundColor: 'rgba(25,84,54,0.24)',
  },
  statsGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 12,
    marginTop: 20,
  },
  statCard: {
    minWidth: 120,
    flexGrow: 1,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.08)',
    borderRadius: 16,
    padding: 14,
    backgroundColor: '#171733',
  },
  statLabel: {
    marginBottom: 6,
    fontSize: 12,
    fontWeight: '600',
    color: 'rgba(255,255,255,0.58)',
  },
  statValue: {
    fontWeight: '700',
    color: '#ffffff',
  },
  section: {
    marginTop: 22,
  },
  sectionTitle: {
    marginBottom: 12,
    fontWeight: '700',
    color: '#ffffff',
  },
  sectionCard: {
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.08)',
    borderRadius: 18,
    padding: 16,
    backgroundColor: '#171733',
  },
  descriptionLine: {
    marginBottom: 10,
    color: 'rgba(255,255,255,0.76)',
  },
  inlineLink: {
    marginTop: 4,
    alignSelf: 'flex-start',
    paddingVertical: 4,
  },
  inlineLinkText: {
    fontSize: 14,
    fontWeight: '600',
    color: '#ff7f93',
  },
  relationGrid: {
    gap: 10,
  },
  relationCard: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.08)',
    borderRadius: 18,
    padding: 14,
    backgroundColor: '#171733',
  },
  relationCardPressed: {
    opacity: 0.92,
  },
  relationCardBody: {
    flex: 1,
    paddingRight: 12,
  },
  relationType: {
    marginBottom: 6,
    fontSize: 12,
    fontWeight: '700',
    textTransform: 'uppercase',
    color: '#e94560',
  },
  relationTitle: {
    fontSize: 14,
    fontWeight: '600',
    lineHeight: 20,
    color: '#ffffff',
  },
  relationActionText: {
    fontSize: 12,
    fontWeight: '700',
    letterSpacing: 0.6,
    textTransform: 'uppercase',
    color: 'rgba(255,255,255,0.46)',
  },
});
