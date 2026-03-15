import { StyleSheet, Text, View } from 'react-native';

export default function SmartRecTabPlaceholder() {
  return (
    <View style={styles.container}>
      <Text style={styles.eyebrow}>Recommendation Modes</Text>
      <Text style={styles.title}>Smart Rec tab scaffolded.</Text>
      <Text style={styles.copy}>
        This placeholder reserves the semantic, similar, and For You tab entry
        point before the full native recommendation experience is ported.
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
