import { useCallback, useEffect, useState } from 'react';
import { getUserList } from '../api/listApi';
import { useAuth } from '../context/AuthContext';
import { UserListEntry } from '../types/anime';

interface UserListIndexSnapshot {
  ids: Set<number>;
  loaded: boolean;
  loading: boolean;
}

let snapshot: UserListIndexSnapshot = {
  ids: new Set<number>(),
  loaded: false,
  loading: false,
};

let inFlightRequest: Promise<void> | null = null;
const listeners = new Set<() => void>();

function notifyListeners() {
  listeners.forEach((listener) => listener());
}

function buildIdSet(entries: UserListEntry[]) {
  return new Set(
    entries
      .map((entry) => Number(entry.anilistId))
      .filter((id) => Number.isFinite(id) && id > 0),
  );
}

function setSnapshot(nextSnapshot: UserListIndexSnapshot) {
  snapshot = nextSnapshot;
  notifyListeners();
}

export function setUserListIndexFromEntries(entries: UserListEntry[]) {
  setSnapshot({
    ids: buildIdSet(entries),
    loaded: true,
    loading: false,
  });
}

export function markAnimeInUserListIndex(anilistId: number) {
  if (!Number.isFinite(anilistId) || anilistId <= 0 || snapshot.ids.has(anilistId)) {
    return;
  }

  const nextIds = new Set(snapshot.ids);
  nextIds.add(anilistId);
  setSnapshot({
    ids: nextIds,
    loaded: true,
    loading: false,
  });
}

export function removeAnimeFromUserListIndex(anilistId: number) {
  if (!snapshot.ids.has(anilistId)) {
    return;
  }

  const nextIds = new Set(snapshot.ids);
  nextIds.delete(anilistId);
  setSnapshot({
    ids: nextIds,
    loaded: true,
    loading: false,
  });
}

export function clearUserListIndex() {
  inFlightRequest = null;
  setSnapshot({
    ids: new Set<number>(),
    loaded: false,
    loading: false,
  });
}

async function refreshUserListIndexSnapshot() {
  if (inFlightRequest) {
    return inFlightRequest;
  }

  setSnapshot({
    ...snapshot,
    loading: true,
  });

  inFlightRequest = (async () => {
    try {
      const entries = await getUserList();
      setUserListIndexFromEntries(entries);
    } finally {
      inFlightRequest = null;
    }
  })();

  return inFlightRequest;
}

export function useUserListIndex() {
  const { isLoggedIn } = useAuth();
  const [state, setState] = useState(snapshot);

  useEffect(() => {
    const handleChange = () => {
      setState(snapshot);
    };

    listeners.add(handleChange);
    return () => {
      listeners.delete(handleChange);
    };
  }, []);

  useEffect(() => {
    if (!isLoggedIn) {
      clearUserListIndex();
      return;
    }

    if (!snapshot.loaded && !snapshot.loading) {
      void refreshUserListIndexSnapshot();
    }
  }, [isLoggedIn]);

  const hasAnime = useCallback(
    (anilistId: number) => state.ids.has(anilistId),
    [state.ids],
  );

  const refreshUserListIndex = useCallback(async () => {
    if (!isLoggedIn) {
      clearUserListIndex();
      return;
    }

    await refreshUserListIndexSnapshot();
  }, [isLoggedIn]);

  return {
    hasAnime,
    userListIds: state.ids,
    listIndexLoaded: state.loaded,
    listIndexLoading: state.loading,
    refreshUserListIndex,
    markAnimeOnList: markAnimeInUserListIndex,
    removeAnimeFromListIndex: removeAnimeFromUserListIndex,
  };
}
