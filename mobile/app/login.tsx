import { useState } from 'react';
import { Link, router } from 'expo-router';
import {
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { loginUser } from '../src/api/authApi';
import { getApiError } from '../src/api/client';
import { useAuth } from '../src/context/AuthContext';
import { useResponsiveLayout } from '../src/ui/useResponsiveLayout';

const REGISTER_ROUTE = '/register' as any;

export default function LoginScreen() {
  const { login } = useAuth();
  const layout = useResponsiveLayout();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async () => {
    if (submitting) return;

    const trimmedEmail = email.trim();
    if (!trimmedEmail || !password) {
      setError('Enter both email and password.');
      return;
    }

    setSubmitting(true);
    setError('');

    try {
      const data = await loginUser(trimmedEmail, password);
      await login(data.token);
      router.replace('/my-list');
    } catch (err) {
      setError(getApiError(err, 'Login failed'));
    } finally {
      setSubmitting(false);
    }
  };

  const handleClose = () => {
    if (router.canGoBack()) {
      router.back();
      return;
    }
    router.replace('/');
  };

  return (
    <KeyboardAvoidingView
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      style={styles.screen}
    >
      <ScrollView
        contentContainerStyle={[
          styles.scrollContent,
          {
            paddingHorizontal: layout.horizontalPadding,
            paddingTop: layout.topPadding,
            paddingBottom: layout.bottomPadding,
          },
        ]}
        keyboardShouldPersistTaps="handled"
      >
        <View style={[styles.contentInner, { maxWidth: layout.authMaxWidth }]}>
          <View style={styles.topBar}>
            <Pressable onPress={handleClose} style={styles.closeButton}>
              <Text style={styles.closeButtonText}>Close</Text>
            </Pressable>
          </View>

          <View style={[styles.card, { paddingHorizontal: layout.cardPadding, paddingVertical: layout.cardPadding + 4 }]}>
            <Text style={styles.eyebrow}>AniRec Account</Text>
            <Text style={[styles.title, { fontSize: layout.titleSize, lineHeight: layout.titleLineHeight }]}>
              Login
            </Text>
            <Text style={[styles.subtitle, { fontSize: layout.bodySize, lineHeight: layout.bodyLineHeight }]}>
              Sign in to save your list, recommendation feedback, and progress across devices.
            </Text>

            {error ? <Text style={[styles.errorText, { fontSize: layout.bodySize }]}>{error}</Text> : null}

            <View style={styles.form}>
              <View style={styles.fieldGroup}>
                <Text style={styles.label}>Email</Text>
                <TextInput
                  autoCapitalize="none"
                  autoCorrect={false}
                  keyboardType="email-address"
                  onChangeText={setEmail}
                  placeholder="you@example.com"
                  placeholderTextColor="rgba(255,255,255,0.35)"
                  style={[
                    styles.input,
                    { paddingVertical: layout.inputVerticalPadding, fontSize: layout.inputSize },
                  ]}
                  value={email}
                />
              </View>

              <View style={styles.fieldGroup}>
                <Text style={styles.label}>Password</Text>
                <TextInput
                  autoCapitalize="none"
                  onChangeText={setPassword}
                  placeholder="Password"
                  placeholderTextColor="rgba(255,255,255,0.35)"
                  secureTextEntry
                  style={[
                    styles.input,
                    { paddingVertical: layout.inputVerticalPadding, fontSize: layout.inputSize },
                  ]}
                  value={password}
                />
              </View>

              <Pressable
                disabled={submitting}
                onPress={handleSubmit}
                style={({ pressed }) => [
                  styles.submitButton,
                  pressed && !submitting ? styles.submitButtonPressed : null,
                  submitting ? styles.submitButtonDisabled : null,
                ]}
              >
                <Text style={[styles.submitButtonText, { fontSize: layout.buttonTextSize }]}>
                  {submitting ? 'Signing in...' : 'Login'}
                </Text>
              </Pressable>
            </View>

            <View style={styles.trustBlock}>
              <Text style={[styles.trustLine, { fontSize: layout.helperSize, lineHeight: layout.bodyLineHeight }]}>
                Tracks your anime list and recommendation history.
              </Text>
              <Link href={REGISTER_ROUTE} style={styles.footerLink}>
                Need an account? Register
              </Link>
            </View>
          </View>
        </View>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: '#0f0f1a',
  },
  scrollContent: {
    flexGrow: 1,
  },
  contentInner: {
    width: '100%',
    alignSelf: 'center',
    flexGrow: 1,
    justifyContent: 'center',
  },
  topBar: {
    alignItems: 'flex-end',
    marginBottom: 12,
  },
  closeButton: {
    paddingHorizontal: 10,
    paddingVertical: 8,
  },
  closeButtonText: {
    fontSize: 15,
    fontWeight: '600',
    color: 'rgba(255,255,255,0.72)',
  },
  card: {
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.08)',
    borderRadius: 24,
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
    marginBottom: 8,
    fontWeight: '700',
    color: '#ffffff',
  },
  subtitle: {
    marginBottom: 20,
    color: 'rgba(255,255,255,0.72)',
  },
  errorText: {
    marginBottom: 16,
    borderWidth: 1,
    borderColor: 'rgba(255,107,107,0.4)',
    borderRadius: 14,
    paddingHorizontal: 14,
    paddingVertical: 12,
    color: '#ff8d8d',
    backgroundColor: 'rgba(123,33,33,0.25)',
  },
  form: {
    gap: 16,
  },
  fieldGroup: {
    gap: 8,
  },
  label: {
    fontSize: 13,
    fontWeight: '600',
    color: '#ffffff',
  },
  input: {
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.1)',
    borderRadius: 14,
    paddingHorizontal: 14,
    color: '#ffffff',
    backgroundColor: 'rgba(255,255,255,0.04)',
  },
  submitButton: {
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 14,
    paddingVertical: 15,
    backgroundColor: '#e94560',
  },
  submitButtonPressed: {
    opacity: 0.9,
    transform: [{ scale: 0.99 }],
  },
  submitButtonDisabled: {
    opacity: 0.65,
  },
  submitButtonText: {
    fontWeight: '700',
    color: '#ffffff',
  },
  trustBlock: {
    marginTop: 20,
    gap: 8,
  },
  trustLine: {
    color: 'rgba(255,255,255,0.58)',
  },
  footerLink: {
    fontSize: 14,
    fontWeight: '600',
    color: '#ff7f93',
  },
});
