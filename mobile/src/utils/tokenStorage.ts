import { Platform } from 'react-native';
import * as SecureStore from 'expo-secure-store';

function hasWebStorage() {
  return typeof window !== 'undefined' && typeof window.localStorage !== 'undefined';
}

export async function getStoredToken(key: string): Promise<string | null> {
  if (Platform.OS === 'web') {
    if (!hasWebStorage()) return null;
    return window.localStorage.getItem(key);
  }

  return SecureStore.getItemAsync(key);
}

export async function setStoredToken(key: string, value: string): Promise<void> {
  if (Platform.OS === 'web') {
    if (!hasWebStorage()) return;
    window.localStorage.setItem(key, value);
    return;
  }

  await SecureStore.setItemAsync(key, value);
}

export async function deleteStoredToken(key: string): Promise<void> {
  if (Platform.OS === 'web') {
    if (!hasWebStorage()) return;
    window.localStorage.removeItem(key);
    return;
  }

  await SecureStore.deleteItemAsync(key);
}
