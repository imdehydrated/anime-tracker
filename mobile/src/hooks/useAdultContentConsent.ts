import { useCallback, useEffect, useState } from 'react';
import { Alert, Platform } from 'react-native';
import { getStoredPreference, setStoredPreference } from '../utils/preferencesStorage';

const ADULT_CONTENT_CONSENT_KEY = 'adult_content_consent_v1';

export function useAdultContentConsent() {
  const [hasAdultContentConsent, setHasAdultContentConsent] = useState(false);
  const [consentLoaded, setConsentLoaded] = useState(false);

  useEffect(() => {
    let isMounted = true;

    async function loadConsent() {
      try {
        const storedValue = await getStoredPreference(ADULT_CONTENT_CONSENT_KEY);
        if (!isMounted) return;
        setHasAdultContentConsent(storedValue === 'true');
      } finally {
        if (isMounted) {
          setConsentLoaded(true);
        }
      }
    }

    void loadConsent();
    return () => {
      isMounted = false;
    };
  }, []);

  const grantConsent = useCallback(async () => {
    await setStoredPreference(ADULT_CONTENT_CONSENT_KEY, 'true');
    setHasAdultContentConsent(true);
  }, []);

  const requestAdultContentConsent = useCallback(async () => {
    if (hasAdultContentConsent) return true;

    if (Platform.OS === 'web' && typeof window !== 'undefined' && typeof window.confirm === 'function') {
      const accepted = window.confirm(
        'This setting may show 18+ anime content. Do you want to allow adult content in the app?',
      );
      if (!accepted) return false;
      await grantConsent();
      return true;
    }

    return new Promise<boolean>((resolve) => {
      Alert.alert(
        'Allow 18+ Content?',
        'This setting may show adult anime content. Keep the filter on unless you explicitly want to view 18+ results.',
        [
          {
            text: 'Keep Filter On',
            style: 'cancel',
            onPress: () => resolve(false),
          },
          {
            text: 'I Understand',
            style: 'destructive',
            onPress: () => {
              void grantConsent().then(() => resolve(true));
            },
          },
        ],
      );
    });
  }, [grantConsent, hasAdultContentConsent]);

  return {
    hasAdultContentConsent,
    consentLoaded,
    requestAdultContentConsent,
  };
}
