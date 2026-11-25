const ARABIC_INDIC_DIGITS = ['٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩'];
const DIACRITICS_REGEX = /[\u064B-\u0652\u0670]/g;

/**
 * Converts Eastern Arabic (Indic) numerals (٠-٩) to standard ASCII numerals (0-9).
 */
export function normalizeDigits(input: string): string {
  let result = input;
  for (let i = 0; i < ARABIC_INDIC_DIGITS.length; i++) {
    result = result.split(ARABIC_INDIC_DIGITS[i]).join(String(i));
  }
  return result;
}

/**
 * Normalizes Arabic text for flexible search:
 * - Converts digits to standard ASCII.
 * - Strips Arabic diacritics / tashkeel first.
 * - Removes "سورة" or "سوره".
 * - Normalizes alef variations (أ, إ, آ, ٱ -> ا), taa marbuta (ة -> ه), alif maqsura (ى -> ي), ؤ -> و, ئ -> ي.
 */
export function normalizeArabicText(text: string): string {
  let str = normalizeDigits(text.trim());
  str = str.replace(DIACRITICS_REGEX, '');
  str = str.split('سورة').join('').split('سوره').join('');

  let builder = '';
  for (const ch of str) {
    switch (ch) {
      case 'أ':
      case 'إ':
      case 'آ':
      case 'ٱ':
        builder += 'ا';
        break;
      case 'ة':
        builder += 'ه';
        break;
      case 'ى':
        builder += 'ي';
        break;
      case 'ؤ':
        builder += 'و';
        break;
      case 'ئ':
        builder += 'ي';
        break;
      default:
        builder += ch;
        break;
    }
  }
  return builder.trim();
}

/**
 * Matches a search query against a Surah:
 * Checks surah number (both Western and Arabic numerals) or normalized Arabic name or English name.
 */
export function matchesQuery(
  surahId: number,
  surahName: string,
  englishName: string,
  query: string
): boolean {
  if (!query.trim()) return true;

  const cleanQuery = normalizeArabicText(query);
  if (!cleanQuery) return true;

  if (/^\d+$/.test(cleanQuery)) {
    const num = Number.parseInt(cleanQuery, 10);
    if (num === surahId) return true;
  }

  const normalizedSurahName = normalizeArabicText(surahName);
  if (normalizedSurahName.toLowerCase().includes(cleanQuery.toLowerCase())) {
    return true;
  }

  if (englishName.toLowerCase().includes(cleanQuery.toLowerCase())) {
    return true;
  }

  return false;
}
