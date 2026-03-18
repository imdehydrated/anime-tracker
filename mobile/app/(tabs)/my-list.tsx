import { useEffect, useMemo, useState } from 'react';
import { router } from 'expo-router';
import { Image } from 'expo-image';
import {
  ActivityIndicator,
  Alert,
  Platform,
  Pressable,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { getApiError } from '../../src/api/client';
import {
  deleteListEntry,
  getUserList,
  importAniListByUsername,
  importMalByUsername,
  ImportResponse,
  updateListEntry,
} from '../../src/api/listApi';
import { useAuth } from '../../src/context/AuthContext';
import {
  removeAnimeFromUserListIndex,
  setUserListIndexFromEntries,
} from '../../src/hooks/useUserListIndex';
import { UserListEntry } from '../../src/types/anime';
import { useResponsiveLayout } from '../../src/ui/useResponsiveLayout';

const STATUS_OPTIONS = [
  { value: 'WATCHING', label: 'Watching' },
  { value: 'COMPLETED', label: 'Completed' },
  { value: 'PLAN_TO_WATCH', label: 'Plan to Watch' },
  { value: 'ON_HOLD', label: 'On Hold' },
  { value: 'DROPPED', label: 'Dropped' },
] as const;

const FILTER_STATUS_OPTIONS = [
  { value: 'ALL', label: 'All' },
  ...STATUS_OPTIONS,
] as const;

const SORT_OPTIONS = [
  { value: 'DATE_DESC', label: 'Newest' },
  { value: 'TITLE_ASC', label: 'A-Z' },
  { value: 'SCORE_DESC', label: 'Top Score' },
  { value: 'STATUS', label: 'Status' },
] as const;

const SCORE_OPTIONS = [
  { value: '', label: '-' },
  { value: '1', label: '1' },
  { value: '2', label: '2' },
  { value: '3', label: '3' },
  { value: '4', label: '4' },
  { value: '5', label: '5' },
  { value: '6', label: '6' },
  { value: '7', label: '7' },
  { value: '8', label: '8' },
  { value: '9', label: '9' },
  { value: '10', label: '10' },
] as const;

type FilterStatus = (typeof FILTER_STATUS_OPTIONS)[number]['value'];
type SortValue = (typeof SORT_OPTIONS)[number]['value'];

function getStatusColor(status: string | null | undefined) {
  switch (status) {
    case 'WATCHING':
      return '#7ad7ff';
    case 'COMPLETED':
      return '#8ee29a';
    case 'ON_HOLD':
      return '#ffd479';
    case 'DROPPED':
      return '#ff8d8d';
    case 'PLAN_TO_WATCH':
    default:
      return '#ff9dad';
  }
}

function getStatusLabel(status: string | null | undefined) {
  return STATUS_OPTIONS.find((option) => option.value === status)?.label || 'Plan to Watch';
}

function getEntryTitle(entry: UserListEntry) {
  return entry.title || `AniList #${entry.anilistId}`;
}

export default function MyListScreen() {
  const { isLoggedIn, username } = useAuth();
  const layout = useResponsiveLayout();
  const [entries, setEntries] = useState<UserListEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState('');
  const [filterText, setFilterText] = useState('');
  const [filterStatus, setFilterStatus] = useState<FilterStatus>('ALL');
  const [sortBy, setSortBy] = useState<SortValue>('DATE_DESC');
  const [editingEntryId, setEditingEntryId] = useState<number | null>(null);
  const [updatingEntryId, setUpdatingEntryId] = useState<number | null>(null);
  const [deletingEntryId, setDeletingEntryId] = useState<number | null>(null);
  const [episodesDrafts, setEpisodesDrafts] = useState<Record<number, string>>({});
  const [importProvider, setImportProvider] = useState<'anilist' | 'mal'>('anilist');
  const [importUsername, setImportUsername] = useState('');
  const [importLoading, setImportLoading] = useState(false);
  const [importError, setImportError] = useState('');
  const [importResult, setImportResult] = useState<(ImportResponse & { dryRun?: boolean }) | null>(null);

  const syncEpisodeDrafts = (list: UserListEntry[]) => {
    const next: Record<number, string> = {};
    for (const entry of list) {
      next[entry.id] = String(entry.episodesWatched || 0);
    }
    setEpisodesDrafts(next);
  };

  const normalizeProgressDraft = (entry: UserListEntry) => {
    const raw = episodesDrafts[entry.id] ?? String(entry.episodesWatched || 0);
    let nextValue = parseInt(raw, 10);
    if (Number.isNaN(nextValue) || nextValue < 0) {
      nextValue = 0;
    }
    if (entry.totalEpisodes != null && nextValue > entry.totalEpisodes) {
      nextValue = entry.totalEpisodes;
    }
    return nextValue;
  };

  const fetchList = async (refresh = false) => {
    if (refresh) {
      setRefreshing(true);
    } else {
      setLoading(true);
    }
    setError('');

    try {
      const data = await getUserList();
      setEntries(data);
      setUserListIndexFromEntries(data);
      syncEpisodeDrafts(data);
    } catch (err) {
      setError(getApiError(err, 'Failed to load your list.'));
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  };

  const handleImport = async (dryRun: boolean) => {
    setImportError('');
    setImportResult(null);
    const usernameToImport = importUsername.trim();
    if (!usernameToImport) {
      setImportError('Enter an AniList or MAL username before running import.');
      return;
    }

    setImportLoading(true);
    try {
      const response =
        importProvider === 'mal'
          ? await importMalByUsername(usernameToImport, dryRun)
          : await importAniListByUsername(usernameToImport, dryRun);

      setImportResult({
        ...(response || {}),
        dryRun,
      });

      if (!dryRun) {
        await fetchList(true);
      }
    } catch (err) {
      setImportError(getApiError(err, 'Import failed.'));
    } finally {
      setImportLoading(false);
    }
  };

  useEffect(() => {
    if (!isLoggedIn) return;
    void fetchList();
  }, [isLoggedIn]);

  const visibleEntries = useMemo(() => {
    return [...entries]
      .filter((entry) => {
        const matchesStatus = filterStatus === 'ALL' || entry.status === filterStatus;
        const matchesText = getEntryTitle(entry).toLowerCase().includes(filterText.trim().toLowerCase());
        return matchesStatus && matchesText;
      })
      .sort((a, b) => {
        switch (sortBy) {
          case 'TITLE_ASC':
            return getEntryTitle(a).localeCompare(getEntryTitle(b));
          case 'SCORE_DESC':
            return Number(b.score || 0) - Number(a.score || 0);
          case 'STATUS':
            return String(a.status || '').localeCompare(String(b.status || ''));
          case 'DATE_DESC':
          default:
            return new Date(b.createdAt || 0).getTime() - new Date(a.createdAt || 0).getTime();
        }
      });
  }, [entries, filterStatus, filterText, sortBy]);

  const summary = useMemo(() => {
    const scoredEntries = visibleEntries.filter((entry) => Number.isFinite(entry.score));
    const completed = visibleEntries.filter((entry) => entry.status === 'COMPLETED').length;
    const watching = visibleEntries.filter((entry) => entry.status === 'WATCHING').length;
    const averageScore = scoredEntries.length
      ? (scoredEntries.reduce((sum, entry) => sum + Number(entry.score || 0), 0) / scoredEntries.length).toFixed(1)
      : null;

    return [
      { label: 'Visible Entries', value: String(visibleEntries.length) },
      { label: 'Completed', value: String(completed) },
      { label: 'Watching', value: String(watching) },
      { label: 'Average Score', value: averageScore ? `${averageScore}/10` : 'No scores yet' },
    ];
  }, [visibleEntries]);

  const handleEntryUpdate = async (entry: UserListEntry, updates: Record<string, unknown>) => {
    const previousEntries = entries;
    const payload = { ...updates };
    setUpdatingEntryId(entry.id);
    setError('');
    if (payload.status === 'COMPLETED' && entry.totalEpisodes != null) {
      payload.episodesWatched = entry.totalEpisodes;
    }
    setEntries((prev) =>
      prev.map((item) =>
        item.id === entry.id
          ? {
              ...item,
              ...payload,
              episodesWatched:
                Object.prototype.hasOwnProperty.call(payload, 'episodesWatched')
                  ? Number(payload.episodesWatched ?? item.episodesWatched ?? 0)
                  : item.episodesWatched,
              score:
                Object.prototype.hasOwnProperty.call(payload, 'score')
                  ? (payload.score as number | null)
                  : item.score,
            }
          : item
      )
    );
    if (Object.prototype.hasOwnProperty.call(payload, 'episodesWatched')) {
      setEpisodesDrafts((prev) => ({
        ...prev,
        [entry.id]: String(payload.episodesWatched ?? 0),
      }));
    }

    try {
      await updateListEntry(entry.id, payload);
    } catch (err) {
      setEntries(previousEntries);
      syncEpisodeDrafts(previousEntries);
      setError(getApiError(err, 'Failed to update status.'));
    } finally {
      setUpdatingEntryId(null);
    }
  };

  const handleStatusUpdate = async (entry: UserListEntry, nextStatus: string) => {
    await handleEntryUpdate(entry, { status: nextStatus });
  };

  const handleScoreUpdate = async (entry: UserListEntry, nextScore: string) => {
    await handleEntryUpdate(entry, { score: nextScore === '' ? null : parseInt(nextScore, 10) });
  };

  const handleProgressCommit = async (entry: UserListEntry) => {
    const nextValue = normalizeProgressDraft(entry);
    setEpisodesDrafts((prev) => ({
      ...prev,
      [entry.id]: String(nextValue),
    }));
    if (nextValue === (entry.episodesWatched || 0)) {
      return;
    }
    await handleEntryUpdate(entry, { episodesWatched: nextValue });
  };

  const confirmDelete = async (entry: UserListEntry) => {
    const runDelete = async () => {
      const previousEntries = entries;
      setDeletingEntryId(entry.id);
      setError('');
      setEntries((prev) => prev.filter((item) => item.id !== entry.id));
      removeAnimeFromUserListIndex(entry.anilistId);

      try {
        await deleteListEntry(entry.id);
      } catch (err) {
        setEntries(previousEntries);
        setUserListIndexFromEntries(previousEntries);
        setError(getApiError(err, 'Failed to delete list entry.'));
      } finally {
        setDeletingEntryId(null);
      }
    };

    if (Platform.OS === 'web') {
      const accepted =
        typeof window !== 'undefined' &&
        typeof window.confirm === 'function' &&
        window.confirm(`Remove "${getEntryTitle(entry)}" from your list?`);
      if (accepted) {
        await runDelete();
      }
      return;
    }

    Alert.alert('Remove from list?', `Remove "${getEntryTitle(entry)}" from your list?`, [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Remove',
        style: 'destructive',
        onPress: () => {
          void runDelete();
        },
      },
    ]);
  };

  if (!isLoggedIn) {
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
          <View style={[styles.card, { padding: layout.cardPadding }]}>
            <Text style={styles.eyebrow}>Tracked Library</Text>
            <Text style={[styles.title, { fontSize: layout.titleSize, lineHeight: layout.titleLineHeight }]}>
              Login required for My List.
            </Text>
            <Text style={[styles.copy, { fontSize: layout.bodySize, lineHeight: layout.bodyLineHeight }]}>
              This tab manages your tracked anime, scores, and status history. Sign in first so the mobile app can load your saved list from the current backend.
            </Text>

            <View style={styles.actionRow}>
              <Pressable onPress={() => router.push('/login' as any)} style={styles.primaryButton}>
                <Text style={[styles.primaryButtonText, { fontSize: layout.buttonTextSize }]}>Login</Text>
              </Pressable>
              <Pressable onPress={() => router.push('/register' as any)} style={styles.secondaryButton}>
                <Text style={[styles.secondaryButtonText, { fontSize: layout.buttonTextSize }]}>Register</Text>
              </Pressable>
            </View>
          </View>
        </View>
      </ScrollView>
    );
  }

  return (
    <ScrollView
      style={styles.screen}
      refreshControl={
        <RefreshControl refreshing={refreshing} onRefresh={() => void fetchList(true)} tintColor="#e94560" />
      }
      contentContainerStyle={[
        styles.scrollContent,
        {
          paddingHorizontal: layout.horizontalPadding,
          paddingTop: layout.topPadding,
          paddingBottom: layout.bottomPadding,
        },
      ]}
    >
      <View style={[styles.contentInner, { maxWidth: layout.contentMaxWidth }]}>
        <Text style={styles.eyebrow}>Tracked Library</Text>
        <Text style={[styles.title, { fontSize: layout.titleSize, lineHeight: layout.titleLineHeight }]}>
          My List
        </Text>
        <Text style={[styles.copy, { fontSize: layout.bodySize, lineHeight: layout.bodyLineHeight }]}>
          Welcome back{username ? `, ${username}` : ''}. Your saved entries, progress, and status changes live here.
        </Text>

        <View style={styles.headerActions}>
          <Pressable onPress={() => router.push('/search' as any)} style={styles.primaryButton}>
            <Text style={[styles.primaryButtonText, { fontSize: layout.buttonTextSize }]}>Add from Search</Text>
          </Pressable>
          <Pressable onPress={() => void fetchList(true)} style={styles.secondaryButton}>
            <Text style={[styles.secondaryButtonText, { fontSize: layout.buttonTextSize }]}>
              {refreshing ? 'Refreshing...' : 'Refresh'}
            </Text>
          </Pressable>
        </View>

        <View style={[styles.card, { padding: layout.cardPadding }]}>
          <View style={styles.importHeader}>
            <Text style={styles.importTitle}>Import From AniList / MAL</Text>
            <Text style={[styles.importSubtitle, { fontSize: layout.helperSize, lineHeight: layout.bodyLineHeight }]}>
              Username-based sync into your saved mobile list
            </Text>
          </View>

          <View style={styles.importProviderRow}>
            <Pressable
              disabled={importLoading}
              onPress={() => setImportProvider('anilist')}
              style={({ pressed }) => [
                styles.importProviderButton,
                importProvider === 'anilist' ? styles.importProviderButtonActive : null,
                pressed && !importLoading ? styles.importProviderButtonPressed : null,
              ]}
            >
              <Text
                style={[
                  styles.importProviderText,
                  importProvider === 'anilist' ? styles.importProviderTextActive : null,
                  { fontSize: layout.buttonTextSize },
                ]}
              >
                AniList
              </Text>
            </Pressable>
            <Pressable
              disabled={importLoading}
              onPress={() => setImportProvider('mal')}
              style={({ pressed }) => [
                styles.importProviderButton,
                importProvider === 'mal' ? styles.importProviderButtonActive : null,
                pressed && !importLoading ? styles.importProviderButtonPressed : null,
              ]}
            >
              <Text
                style={[
                  styles.importProviderText,
                  importProvider === 'mal' ? styles.importProviderTextActive : null,
                  { fontSize: layout.buttonTextSize },
                ]}
              >
                MAL
              </Text>
            </Pressable>
          </View>

          <TextInput
            autoCapitalize="none"
            autoCorrect={false}
            editable={!importLoading}
            onChangeText={setImportUsername}
            onSubmitEditing={() => void handleImport(false)}
            placeholder={`Enter ${importProvider === 'mal' ? 'MAL' : 'AniList'} username`}
            placeholderTextColor="rgba(255,255,255,0.35)"
            returnKeyType="go"
            style={[
              styles.searchInput,
              {
                fontSize: layout.inputSize,
                paddingVertical: layout.inputVerticalPadding,
              },
            ]}
            value={importUsername}
          />

          <View style={styles.importActionRow}>
            <Pressable
              disabled={importLoading}
              onPress={() => void handleImport(true)}
              style={({ pressed }) => [
                styles.secondaryButton,
                styles.importActionButton,
                pressed && !importLoading ? styles.secondaryButtonPressed : null,
                importLoading ? styles.disabledButton : null,
              ]}
            >
              <Text style={[styles.secondaryButtonText, { fontSize: layout.buttonTextSize }]}>
                {importLoading ? 'Running...' : 'Dry Run'}
              </Text>
            </Pressable>
            <Pressable
              disabled={importLoading}
              onPress={() => void handleImport(false)}
              style={({ pressed }) => [
                styles.primaryButton,
                styles.importActionButton,
                pressed && !importLoading ? styles.primaryButtonPressed : null,
                importLoading ? styles.disabledButton : null,
              ]}
            >
              <Text style={[styles.primaryButtonText, { fontSize: layout.buttonTextSize }]}>
                {importLoading ? 'Running...' : 'Import'}
              </Text>
            </Pressable>
          </View>

          {importError ? <Text style={[styles.errorText, { fontSize: layout.bodySize }]}>{importError}</Text> : null}

          {importResult ? (
            <View style={styles.importResultCard}>
              <Text style={[styles.importResultTitle, { fontSize: layout.bodySize }]}>
                {importResult.message || 'Import completed'}
                {importResult.dryRun ? ' (dry run)' : ''}
              </Text>
              <Text style={[styles.importResultText, { fontSize: layout.helperSize, lineHeight: layout.bodyLineHeight }]}>
                Discovered: {importResult.stats?.discovered ?? 0} | Imported: {importResult.stats?.imported ?? 0} | Updated: {importResult.stats?.updated ?? 0} | Skipped: {importResult.stats?.skipped ?? 0} | Failed: {importResult.stats?.failed ?? 0}
              </Text>
              {Array.isArray(importResult.stats?.failureSamples) && importResult.stats!.failureSamples!.length > 0 ? (
                <Text style={[styles.importResultText, { fontSize: layout.helperSize, lineHeight: layout.bodyLineHeight }]}>
                  Failures: {importResult.stats!.failureSamples!
                    .map((sample) => sample?.detail || sample?.reason || 'unknown')
                    .join(' | ')}
                </Text>
              ) : null}
            </View>
          ) : null}
        </View>

        {loading ? (
          <View style={[styles.card, styles.centerCard, { padding: layout.cardPadding }]}>
            <ActivityIndicator color="#e94560" />
            <Text style={[styles.loadingText, { fontSize: layout.bodySize }]}>Loading your list...</Text>
          </View>
        ) : (
          <>
            <View style={[styles.card, { padding: layout.cardPadding }]}>
              <TextInput
                autoCapitalize="none"
                autoCorrect={false}
                onChangeText={setFilterText}
                placeholder="Filter by title..."
                placeholderTextColor="rgba(255,255,255,0.35)"
                style={[
                  styles.searchInput,
                  {
                    fontSize: layout.inputSize,
                    paddingVertical: layout.inputVerticalPadding,
                  },
                ]}
                value={filterText}
              />

              <View style={styles.filterSection}>
                <Text style={[styles.filterSectionLabel, { fontSize: layout.helperSize }]}>Status Filter</Text>
                <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.filterChipRow}>
                  {FILTER_STATUS_OPTIONS.map((option) => (
                    <Pressable
                      key={option.value}
                      onPress={() => setFilterStatus(option.value)}
                      style={({ pressed }) => [
                        styles.filterChip,
                        filterStatus === option.value ? styles.filterChipActive : null,
                        pressed ? styles.filterChipPressed : null,
                      ]}
                    >
                      <Text
                        style={[
                          styles.filterChipText,
                          filterStatus === option.value ? styles.filterChipTextActive : null,
                          { fontSize: layout.helperSize },
                        ]}
                      >
                        {option.label}
                      </Text>
                    </Pressable>
                  ))}
                </ScrollView>
              </View>

              <View style={styles.filterSection}>
                <Text style={[styles.filterSectionLabel, { fontSize: layout.helperSize }]}>Sort</Text>
                <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.filterChipRow}>
                  {SORT_OPTIONS.map((option) => (
                    <Pressable
                      key={option.value}
                      onPress={() => setSortBy(option.value)}
                      style={({ pressed }) => [
                        styles.filterChip,
                        sortBy === option.value ? styles.filterChipActive : null,
                        pressed ? styles.filterChipPressed : null,
                      ]}
                    >
                      <Text
                        style={[
                          styles.filterChipText,
                          sortBy === option.value ? styles.filterChipTextActive : null,
                          { fontSize: layout.helperSize },
                        ]}
                      >
                        {option.label}
                      </Text>
                    </Pressable>
                  ))}
                </ScrollView>
              </View>
            </View>

            <View style={styles.summaryGrid}>
              {summary.map((item) => (
                <View key={item.label} style={[styles.summaryCard, { padding: layout.compactCardPadding }]}>
                  <Text style={styles.summaryLabel}>{item.label}</Text>
                  <Text style={[styles.summaryValue, { fontSize: layout.resultTitleSize }]}>{item.value}</Text>
                </View>
              ))}
            </View>

            {error ? <Text style={[styles.errorText, { fontSize: layout.bodySize }]}>{error}</Text> : null}

            {entries.length === 0 ? (
              <View style={[styles.card, { padding: layout.cardPadding }]}>
                <Text style={styles.emptyKicker}>Your list is empty</Text>
                <Text style={[styles.emptyTitle, { fontSize: layout.sectionTitleSize }]}>
                  Start tracking shows to sharpen recommendations.
                </Text>
                <Text style={[styles.emptyCopy, { fontSize: layout.bodySize, lineHeight: layout.bodyLineHeight }]}>
                  Search for a title, add it to your list, and your status history will start shaping future results.
                </Text>
                <Pressable onPress={() => router.push('/search' as any)} style={styles.primaryButton}>
                  <Text style={[styles.primaryButtonText, { fontSize: layout.buttonTextSize }]}>Search the Catalog</Text>
                </Pressable>
              </View>
            ) : visibleEntries.length === 0 ? (
              <View style={[styles.card, { padding: layout.cardPadding }]}>
                <Text style={styles.emptyKicker}>No matching entries</Text>
                <Text style={[styles.emptyTitle, { fontSize: layout.sectionTitleSize }]}>
                  Nothing fits the current filters.
                </Text>
                <Text style={[styles.emptyCopy, { fontSize: layout.bodySize, lineHeight: layout.bodyLineHeight }]}>
                  Adjust the title search, status filter, or sort mode to bring more of your list back into view.
                </Text>
              </View>
            ) : (
              <View style={styles.listStack}>
                {visibleEntries.map((entry) => (
                  <View key={entry.id} style={[styles.entryCard, { padding: layout.compactCardPadding }]}>
                    <View style={styles.entryHeader}>
                      {entry.coverImage ? (
                        <Image contentFit="cover" source={{ uri: entry.coverImage }} style={styles.coverImage} />
                      ) : (
                        <View style={styles.coverFallback}>
                          <Text style={styles.coverFallbackText}>No Cover</Text>
                        </View>
                      )}

                      <View style={styles.entryBody}>
                        <Text style={[styles.entryTitle, { fontSize: layout.resultTitleSize }]}>{getEntryTitle(entry)}</Text>
                        <View style={[styles.statusBadge, { borderColor: getStatusColor(entry.status) }]}>
                          <Text style={[styles.statusBadgeText, { color: getStatusColor(entry.status), fontSize: layout.helperSize }]}>
                            {getStatusLabel(entry.status)}
                          </Text>
                        </View>
                        <Text style={[styles.entryMeta, { fontSize: layout.helperSize, lineHeight: layout.bodyLineHeight }]}>
                          Score: {entry.score != null ? `${entry.score}/10` : 'Unscored'}
                        </Text>
                        <Text style={[styles.entryMeta, { fontSize: layout.helperSize, lineHeight: layout.bodyLineHeight }]}>
                          Progress: {entry.episodesWatched || 0}{entry.totalEpisodes != null ? ` / ${entry.totalEpisodes}` : ' eps'}
                        </Text>
                      </View>
                    </View>

                    <View style={styles.entryActionRow}>
                      <Pressable
                        onPress={() => router.push({ pathname: '/anime/[id]', params: { id: String(entry.anilistId) } } as any)}
                        style={({ pressed }) => [
                          styles.secondaryButton,
                          styles.actionButton,
                          pressed ? styles.secondaryButtonPressed : null,
                        ]}
                      >
                        <Text style={[styles.secondaryButtonText, { fontSize: layout.buttonTextSize }]}>View Details</Text>
                      </Pressable>
                        <Pressable
                          onPress={() => setEditingEntryId((prev) => (prev === entry.id ? null : entry.id))}
                          style={({ pressed }) => [
                            styles.secondaryButton,
                            styles.actionButton,
                          pressed ? styles.secondaryButtonPressed : null,
                        ]}
                        >
                          <Text style={[styles.secondaryButtonText, { fontSize: layout.buttonTextSize }]}>
                          {editingEntryId === entry.id ? 'Close Editor' : 'Edit Entry'}
                          </Text>
                        </Pressable>
                      <Pressable
                        disabled={deletingEntryId === entry.id}
                        onPress={() => void confirmDelete(entry)}
                        style={({ pressed }) => [
                          styles.dangerButton,
                          styles.actionButton,
                          pressed && deletingEntryId !== entry.id ? styles.dangerButtonPressed : null,
                          deletingEntryId === entry.id ? styles.disabledButton : null,
                        ]}
                      >
                        <Text style={[styles.primaryButtonText, { fontSize: layout.buttonTextSize }]}>
                          {deletingEntryId === entry.id ? 'Removing...' : 'Delete'}
                        </Text>
                      </Pressable>
                    </View>

                    {editingEntryId === entry.id ? (
                      <View style={styles.editorPanel}>
                        <View style={styles.editorSection}>
                          <Text style={[styles.editorLabel, { fontSize: layout.helperSize }]}>Status</Text>
                          <View style={styles.statusOptionRow}>
                            {STATUS_OPTIONS.map((option) => (
                              <Pressable
                                key={option.value}
                                disabled={updatingEntryId === entry.id && entry.status !== option.value}
                                onPress={() => void handleStatusUpdate(entry, option.value)}
                                style={({ pressed }) => [
                                  styles.statusOptionChip,
                                  entry.status === option.value ? styles.statusOptionChipActive : null,
                                  pressed ? styles.statusOptionChipPressed : null,
                                ]}
                              >
                                <Text
                                  style={[
                                    styles.statusOptionChipText,
                                    entry.status === option.value ? styles.statusOptionChipTextActive : null,
                                    { fontSize: layout.helperSize },
                                  ]}
                                >
                                  {option.label}
                                </Text>
                              </Pressable>
                            ))}
                          </View>
                        </View>

                        <View style={styles.editorSection}>
                          <Text style={[styles.editorLabel, { fontSize: layout.helperSize }]}>Score</Text>
                          <View style={styles.scoreChipRow}>
                            {SCORE_OPTIONS.map((option) => (
                              <Pressable
                                key={option.value || 'blank'}
                                disabled={updatingEntryId === entry.id}
                                onPress={() => void handleScoreUpdate(entry, option.value)}
                                style={({ pressed }) => [
                                  styles.scoreChip,
                                  String(entry.score ?? '') === option.value ? styles.scoreChipActive : null,
                                  pressed ? styles.scoreChipPressed : null,
                                ]}
                              >
                                <Text
                                  style={[
                                    styles.scoreChipText,
                                    String(entry.score ?? '') === option.value ? styles.scoreChipTextActive : null,
                                    { fontSize: layout.helperSize },
                                  ]}
                                >
                                  {option.label}
                                </Text>
                              </Pressable>
                            ))}
                          </View>
                        </View>

                        <View style={styles.editorSection}>
                          <Text style={[styles.editorLabel, { fontSize: layout.helperSize }]}>Progress</Text>
                          <View style={styles.progressRow}>
                            <TextInput
                              keyboardType="number-pad"
                              onChangeText={(value) =>
                                setEpisodesDrafts((prev) => ({
                                  ...prev,
                                  [entry.id]: value,
                                }))
                              }
                              onBlur={() => void handleProgressCommit(entry)}
                              onSubmitEditing={() => void handleProgressCommit(entry)}
                              returnKeyType="done"
                              style={[
                                styles.progressInput,
                                {
                                  fontSize: layout.inputSize,
                                  paddingVertical: layout.inputVerticalPadding,
                                },
                              ]}
                              value={episodesDrafts[entry.id] ?? String(entry.episodesWatched || 0)}
                            />
                            <Text style={[styles.progressSuffix, { fontSize: layout.bodySize }]}>
                              {entry.totalEpisodes != null ? `/ ${entry.totalEpisodes}` : 'eps'}
                            </Text>
                          </View>
                        </View>

                        <View style={styles.editorFooter}>
                          <Pressable
                            disabled={updatingEntryId === entry.id}
                            onPress={() => setEditingEntryId(null)}
                            style={({ pressed }) => [
                              styles.secondaryButton,
                              styles.doneButton,
                              pressed && updatingEntryId !== entry.id ? styles.secondaryButtonPressed : null,
                              updatingEntryId === entry.id ? styles.disabledButton : null,
                            ]}
                          >
                            <Text style={[styles.secondaryButtonText, { fontSize: layout.buttonTextSize }]}>Done</Text>
                          </Pressable>
                        </View>
                      </View>
                    ) : null}
                  </View>
                ))}
              </View>
            )}
          </>
        )}
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
    flexGrow: 1,
    justifyContent: 'center',
  },
  scrollContent: {
    gap: 14,
  },
  contentInner: {
    width: '100%',
    alignSelf: 'center',
  },
  card: {
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.08)',
    borderRadius: 24,
    backgroundColor: '#12122a',
  },
  centerCard: {
    alignItems: 'center',
    gap: 12,
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
    marginBottom: 12,
    fontWeight: '700',
    color: '#ffffff',
  },
  copy: {
    color: 'rgba(255,255,255,0.72)',
    marginBottom: 18,
  },
  headerActions: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 12,
    marginBottom: 16,
  },
  actionRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 12,
    marginTop: 24,
  },
  primaryButton: {
    flexGrow: 1,
    minWidth: 140,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 14,
    paddingHorizontal: 18,
    paddingVertical: 14,
    backgroundColor: '#e94560',
  },
  primaryButtonPressed: {
    opacity: 0.9,
  },
  primaryButtonText: {
    fontWeight: '700',
    color: '#ffffff',
  },
  secondaryButton: {
    flexGrow: 1,
    minWidth: 140,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.14)',
    borderRadius: 14,
    paddingHorizontal: 18,
    paddingVertical: 14,
    backgroundColor: '#12122a',
  },
  secondaryButtonPressed: {
    opacity: 0.9,
  },
  secondaryButtonText: {
    fontWeight: '700',
    color: '#ffffff',
  },
  dangerButton: {
    flexGrow: 1,
    minWidth: 120,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 14,
    paddingHorizontal: 18,
    paddingVertical: 14,
    backgroundColor: '#7b2121',
  },
  dangerButtonPressed: {
    opacity: 0.9,
  },
  disabledButton: {
    opacity: 0.55,
  },
  loadingText: {
    color: 'rgba(255,255,255,0.72)',
  },
  importHeader: {
    marginBottom: 14,
  },
  importTitle: {
    marginBottom: 4,
    fontSize: 18,
    fontWeight: '700',
    color: '#ffffff',
  },
  importSubtitle: {
    color: 'rgba(255,255,255,0.62)',
  },
  importProviderRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 10,
    marginBottom: 14,
  },
  importProviderButton: {
    flexGrow: 1,
    minWidth: 120,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.12)',
    borderRadius: 14,
    paddingHorizontal: 16,
    paddingVertical: 12,
    backgroundColor: '#171733',
  },
  importProviderButtonActive: {
    borderColor: 'rgba(233,69,96,0.35)',
    backgroundColor: 'rgba(233,69,96,0.18)',
  },
  importProviderButtonPressed: {
    opacity: 0.9,
  },
  importProviderText: {
    fontWeight: '700',
    color: 'rgba(255,255,255,0.82)',
  },
  importProviderTextActive: {
    color: '#ffffff',
  },
  importActionRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 12,
    marginTop: 14,
  },
  importActionButton: {
    minWidth: 120,
  },
  importResultCard: {
    marginTop: 14,
    borderWidth: 1,
    borderColor: 'rgba(111,207,151,0.2)',
    borderRadius: 16,
    paddingHorizontal: 14,
    paddingVertical: 12,
    backgroundColor: 'rgba(25,84,54,0.18)',
  },
  importResultTitle: {
    marginBottom: 6,
    fontWeight: '700',
    color: '#d3ffd9',
  },
  importResultText: {
    color: 'rgba(255,255,255,0.74)',
  },
  searchInput: {
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.1)',
    borderRadius: 16,
    paddingHorizontal: 16,
    color: '#ffffff',
    backgroundColor: '#171733',
  },
  filterChipRow: {
    gap: 8,
  },
  filterSection: {
    marginTop: 14,
  },
  filterSectionLabel: {
    marginBottom: 8,
    fontWeight: '700',
    letterSpacing: 0.6,
    textTransform: 'uppercase',
    color: 'rgba(255,255,255,0.56)',
  },
  filterChip: {
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.12)',
    borderRadius: 999,
    paddingHorizontal: 12,
    paddingVertical: 8,
    backgroundColor: '#171733',
  },
  filterChipActive: {
    borderColor: 'rgba(233,69,96,0.35)',
    backgroundColor: 'rgba(233,69,96,0.18)',
  },
  filterChipPressed: {
    opacity: 0.9,
  },
  filterChipText: {
    fontWeight: '600',
    color: 'rgba(255,255,255,0.76)',
  },
  filterChipTextActive: {
    color: '#ffffff',
  },
  summaryGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 12,
    marginTop: 14,
    marginBottom: 14,
  },
  summaryCard: {
    flexGrow: 1,
    minWidth: 150,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.08)',
    borderRadius: 18,
    backgroundColor: '#12122a',
  },
  summaryLabel: {
    marginBottom: 6,
    fontSize: 12,
    fontWeight: '700',
    letterSpacing: 1,
    textTransform: 'uppercase',
    color: 'rgba(255,255,255,0.5)',
  },
  summaryValue: {
    fontWeight: '700',
    color: '#ffffff',
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
  emptyKicker: {
    marginBottom: 8,
    fontSize: 12,
    fontWeight: '700',
    letterSpacing: 1.3,
    textTransform: 'uppercase',
    color: '#e94560',
  },
  emptyTitle: {
    marginBottom: 10,
    fontWeight: '700',
    color: '#ffffff',
  },
  emptyCopy: {
    marginBottom: 18,
    color: 'rgba(255,255,255,0.72)',
  },
  listStack: {
    gap: 12,
  },
  entryCard: {
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.08)',
    borderRadius: 20,
    backgroundColor: '#12122a',
  },
  entryHeader: {
    flexDirection: 'row',
    gap: 14,
  },
  coverImage: {
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
  },
  entryBody: {
    flex: 1,
    gap: 8,
  },
  entryTitle: {
    fontWeight: '700',
    color: '#ffffff',
  },
  statusBadge: {
    alignSelf: 'flex-start',
    borderWidth: 1,
    borderRadius: 999,
    paddingHorizontal: 10,
    paddingVertical: 6,
    backgroundColor: 'rgba(255,255,255,0.03)',
  },
  statusBadgeText: {
    fontWeight: '700',
  },
  entryMeta: {
    color: 'rgba(255,255,255,0.65)',
  },
  entryActionRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 10,
    marginTop: 16,
  },
  actionButton: {
    minWidth: 120,
  },
  editorPanel: {
    gap: 14,
    marginTop: 14,
  },
  editorSection: {
    gap: 8,
  },
  editorLabel: {
    fontWeight: '700',
    letterSpacing: 0.5,
    textTransform: 'uppercase',
    color: 'rgba(255,255,255,0.58)',
  },
  statusOptionRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 10,
  },
  statusOptionChip: {
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.12)',
    borderRadius: 999,
    paddingHorizontal: 14,
    paddingVertical: 10,
    backgroundColor: '#171733',
  },
  statusOptionChipActive: {
    borderColor: 'rgba(233,69,96,0.35)',
    backgroundColor: 'rgba(233,69,96,0.18)',
  },
  statusOptionChipPressed: {
    opacity: 0.9,
  },
  statusOptionChipText: {
    fontWeight: '600',
    color: 'rgba(255,255,255,0.76)',
  },
  statusOptionChipTextActive: {
    color: '#ffffff',
  },
  scoreChipRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 10,
  },
  scoreChip: {
    minWidth: 42,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.12)',
    borderRadius: 999,
    paddingHorizontal: 12,
    paddingVertical: 10,
    backgroundColor: '#171733',
  },
  scoreChipActive: {
    borderColor: 'rgba(233,69,96,0.35)',
    backgroundColor: 'rgba(233,69,96,0.18)',
  },
  scoreChipPressed: {
    opacity: 0.9,
  },
  scoreChipText: {
    fontWeight: '600',
    color: 'rgba(255,255,255,0.76)',
  },
  scoreChipTextActive: {
    color: '#ffffff',
  },
  progressRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
  },
  progressInput: {
    minWidth: 92,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.1)',
    borderRadius: 14,
    paddingHorizontal: 14,
    color: '#ffffff',
    backgroundColor: '#171733',
  },
  progressSuffix: {
    fontWeight: '600',
    color: 'rgba(255,255,255,0.62)',
  },
  editorFooter: {
    marginTop: 4,
    alignItems: 'flex-start',
  },
  doneButton: {
    flexGrow: 0,
    minWidth: 120,
  },
});
