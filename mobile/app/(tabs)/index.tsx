import { StyleSheet, Text, View } from 'react-native';

export default function HomeTabPlaceholder() {
  return (
    <View style={styles.container}>
      <Text style={styles.eyebrow}>AniRec Mobile</Text>
      <Text style={styles.title}>Home tab scaffolded.</Text>
      <Text style={styles.copy}>
        This placeholder confirms the native tab shell is wired.
        The next passes will replace it with the real popular strip,
        greeting, and quick-entry actions from the web app.
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
});
