import { useState } from 'react';
import { Link, router } from 'expo-router';
import {
  KeyboardAvoidingView,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { registerUser } from '../src/api/authApi';
import { getApiError } from '../src/api/client';

const LOGIN_ROUTE = '/login' as any;

export default function RegisterScreen() {
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async () => {
    if (submitting) return;

    const trimmedUsername = username.trim();
    const trimmedEmail = email.trim();
    if (!trimmedUsername || !trimmedEmail || !password) {
      setError('Enter username, email, and password.');
      return;
    }

    setSubmitting(true);
    setError('');

    try {
      await registerUser(trimmedUsername, trimmedEmail, password);
      router.replace(LOGIN_ROUTE);
    } catch (err) {
      setError(getApiError(err, 'Registration failed'));
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
      <View style={styles.shell}>
        <Pressable onPress={handleClose} style={styles.closeButton}>
          <Text style={styles.closeButtonText}>Close</Text>
        </Pressable>

        <View style={styles.card}>
          <Text style={styles.eyebrow}>AniRec Account</Text>
          <Text style={styles.title}>Register</Text>
          <Text style={styles.subtitle}>
            Create an account to track anime, keep progress, and unlock personalized recommendations.
          </Text>

          {error ? <Text style={styles.errorText}>{error}</Text> : null}

          <View style={styles.form}>
            <View style={styles.fieldGroup}>
              <Text style={styles.label}>Username</Text>
              <TextInput
                autoCapitalize="none"
                autoCorrect={false}
                onChangeText={setUsername}
                placeholder="Username"
                placeholderTextColor="rgba(255,255,255,0.35)"
                style={styles.input}
                value={username}
              />
            </View>

            <View style={styles.fieldGroup}>
              <Text style={styles.label}>Email</Text>
              <TextInput
                autoCapitalize="none"
                autoCorrect={false}
                keyboardType="email-address"
                onChangeText={setEmail}
                placeholder="you@example.com"
                placeholderTextColor="rgba(255,255,255,0.35)"
                style={styles.input}
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
                style={styles.input}
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
              <Text style={styles.submitButtonText}>
                {submitting ? 'Creating account...' : 'Register'}
              </Text>
            </Pressable>
          </View>

          <View style={styles.footerBlock}>
            <Text style={styles.trustLine}>
              After registration you will return to login so the auth flow stays explicit.
            </Text>
            <Link href={LOGIN_ROUTE} style={styles.footerLink}>
              Already have an account? Login
            </Link>
          </View>
        </View>
      </View>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: '#0f0f1a',
  },
  shell: {
    flex: 1,
    justifyContent: 'center',
    paddingHorizontal: 20,
    paddingVertical: 28,
    backgroundColor: '#0f0f1a',
  },
  closeButton: {
    position: 'absolute',
    top: 28,
    right: 20,
    zIndex: 2,
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
    paddingHorizontal: 20,
    paddingVertical: 24,
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
    fontSize: 30,
    fontWeight: '700',
    color: '#ffffff',
  },
  subtitle: {
    marginBottom: 20,
    fontSize: 15,
    lineHeight: 22,
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
    paddingVertical: 14,
    fontSize: 16,
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
    fontSize: 16,
    fontWeight: '700',
    color: '#ffffff',
  },
  footerBlock: {
    marginTop: 20,
    gap: 10,
  },
  trustLine: {
    fontSize: 13,
    lineHeight: 20,
    color: 'rgba(255,255,255,0.58)',
  },
  footerLink: {
    fontSize: 14,
    fontWeight: '600',
    color: '#ff7f93',
  },
});
