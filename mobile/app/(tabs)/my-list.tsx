import { router } from 'expo-router';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useAuth } from '../../src/context/AuthContext';

export default function MyListTabPlaceholder() {
  const { isLoggedIn, username } = useAuth();

  if (!isLoggedIn) {
    return (
      <View style={styles.container}>
        <Text style={styles.eyebrow}>Tracked Library</Text>
        <Text style={styles.title}>Login required for My List.</Text>
        <Text style={styles.copy}>
          This tab will manage your tracked anime, scores, imports, and progress.
          Sign in first so the mobile app can load your existing list from the current backend.
        </Text>

        <View style={styles.actionRow}>
          <Pressable onPress={() => router.push('/login' as any)} style={styles.primaryButton}>
            <Text style={styles.primaryButtonText}>Login</Text>
          </Pressable>
          <Pressable onPress={() => router.push('/register' as any)} style={styles.secondaryButton}>
            <Text style={styles.secondaryButtonText}>Register</Text>
          </Pressable>
        </View>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <Text style={styles.eyebrow}>Tracked Library</Text>
      <Text style={styles.title}>My List tab scaffolded.</Text>
      <Text style={styles.copy}>
        Welcome back{username ? `, ${username}` : ''}. This route will become the
        authenticated list manager with inline edits, imports, and stats in the next
        implementation pass.
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    justifyContent: 'center',
    paddingHorizontal: 24,
    backgroundColor: '#0f0f1a',
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
    fontSize: 28,
    fontWeight: '700',
    color: '#ffffff',
  },
  copy: {
    fontSize: 16,
    lineHeight: 24,
    color: 'rgba(255,255,255,0.72)',
  },
  actionRow: {
    flexDirection: 'row',
    gap: 12,
    marginTop: 24,
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
    fontSize: 15,
    fontWeight: '700',
    color: '#ffffff',
  },
  secondaryButton: {
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.14)',
    borderRadius: 14,
    paddingHorizontal: 18,
    paddingVertical: 14,
    backgroundColor: '#12122a',
  },
  secondaryButtonText: {
    fontSize: 15,
    fontWeight: '700',
    color: '#ffffff',
  },
});
