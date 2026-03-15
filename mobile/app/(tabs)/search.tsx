import { StyleSheet, Text, View } from 'react-native';

export default function SearchTabPlaceholder() {
  return (
    <View style={styles.container}>
      <Text style={styles.eyebrow}>Catalog Search</Text>
      <Text style={styles.title}>Search tab scaffolded.</Text>
      <Text style={styles.copy}>
        This route is in place so the final mobile search screen can take over
        without changing the tab contract again.
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
