import { useMemo } from 'react';
import { useWindowDimensions } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

export function useResponsiveLayout() {
  const { width } = useWindowDimensions();
  const insets = useSafeAreaInsets();

  return useMemo(() => {
    const isCompact = width < 370;
    const isLargePhone = width >= 430;
    const horizontalPadding = isCompact ? 16 : 20;
    const topPadding = Math.max(insets.top + 12, 24);
    const bottomPadding = Math.max(insets.bottom + 24, 32);
    const heroPadding = isCompact ? 16 : 20;
    const cardPadding = isCompact ? 16 : 20;
    const compactCardPadding = isCompact ? 14 : 16;
    const titleSize = isCompact ? 24 : isLargePhone ? 32 : 28;
    const titleLineHeight = titleSize + 6;
    const sectionTitleSize = isCompact ? 20 : 22;
    const quickCardTitleSize = isCompact ? 16 : 17;
    const resultTitleSize = isCompact ? 16 : 18;
    const bodySize = isCompact ? 14 : 15;
    const bodyLineHeight = isCompact ? 21 : 22;
    const helperSize = isCompact ? 12 : 13;
    const inputSize = bodySize + 1;
    const inputVerticalPadding = isCompact ? 13 : 15;
    const buttonTextSize = bodySize + 1;
    const contentMaxWidth = width >= 768 ? 860 : 720;
    const authMaxWidth = 480;

    return {
      width,
      isCompact,
      isLargePhone,
      horizontalPadding,
      topPadding,
      bottomPadding,
      heroPadding,
      cardPadding,
      compactCardPadding,
      titleSize,
      titleLineHeight,
      sectionTitleSize,
      quickCardTitleSize,
      resultTitleSize,
      bodySize,
      bodyLineHeight,
      helperSize,
      inputSize,
      inputVerticalPadding,
      buttonTextSize,
      contentMaxWidth,
      authMaxWidth,
    };
  }, [insets.bottom, insets.top, width]);
}
