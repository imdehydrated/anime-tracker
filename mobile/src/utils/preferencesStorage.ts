import { Platform } from 'react-native';
import * as SecureStore from 'expo-secure-store';

function hasWebStorage() {
  return typeof window !== 'undefined' && typeof window.localStorage !== 'undefined';
}

export async function getStoredPreference(key: string): Promise<string | null> {
  if (Platform.OS === 'web') {
    if (!hasWebStorage()) return null;
    return window.localStorage.getItem(key);
  }

  return SecureStore.getItemAsync(key);
}

export async function setStoredPreference(key: string, value: string): Promise<void> {
  if (Platform.OS === 'web') {
    if (!hasWebStorage()) return;
    window.localStorage.setItem(key, value);
    return;
  }

  await SecureStore.setItemAsync(key, value);
}
