import type { Reciter } from '../types/quran';

export const DEFAULT_RECITERS: Reciter[] = [
  {
    id: 'afs',
    name: 'مشاري العفاسي',
    serverUrl: 'https://cdn.mp3quran.net/audio/mishary-alafasy/r1/',
    availableSurahsCount: 114,
    subInfo: 'رواية حفص عن عاصم - المصحف المرتل كاملاً',
  },
  {
    id: 'sds',
    name: 'عبد الرحمن السديس',
    serverUrl: 'https://cdn.mp3quran.net/audio/abdulrahman-sudais/r1/',
    availableSurahsCount: 114,
    subInfo: 'رواية حفص عن عاصم - المصحف المرتل كاملاً',
  },
  {
    id: 'husr',
    name: 'محمود خليل الحصري',
    serverUrl: 'https://cdn.mp3quran.net/audio/mahmoud-husary/r1/',
    availableSurahsCount: 114,
    subInfo: 'رواية حفص عن عاصم - المصحف المرتل كاملاً',
  },
  {
    id: 'basit',
    name: 'عبد الباسط عبد الصمد',
    serverUrl: 'https://cdn.mp3quran.net/audio/abdulbasit-abdulsamad/r1/',
    availableSurahsCount: 114,
    subInfo: 'رواية حفص عن عاصم - المصحف المرتل كاملاً',
  },
  {
    id: 'ghamdi',
    name: 'سعد الغامدي',
    serverUrl: 'https://cdn.mp3quran.net/audio/saad-ghamdi/r1/',
    availableSurahsCount: 114,
    subInfo: 'رواية حفص عن عاصم - المصحف المرتل كاملاً',
  },
  {
    id: 'shl',
    name: 'سهل ياسين',
    serverUrl: 'https://cdn.mp3quran.net/audio/sahl-yassin/r1/',
    availableSurahsCount: 114,
    subInfo: 'رواية حفص عن عاصم - المصحف المرتل كاملاً',
  },
  {
    id: 'raad',
    name: 'رعد الكردي',
    serverUrl: 'https://cdn.mp3quran.net/audio/raad-kurdi/r1/',
    availableSurahsCount: 114,
    subInfo: 'رواية حفص عن عاصم - المصحف المرتل كاملاً',
  },
  {
    id: 'abdulrazzaq_dlaimi',
    name: 'عبد الرزاق الدليمي',
    serverUrl: 'https://archive.org/download/mmmmmm002_20220618/',
    availableSurahsCount: 114,
    subInfo: 'رواية حفص عن عاصم - المصحف المرتل كاملاً',
  },
];

export const DEFAULT_RECITER: Reciter = DEFAULT_RECITERS[0];

export function getReciterById(id: string, list: Reciter[] = DEFAULT_RECITERS): Reciter {
  return list.find((r) => r.id === id) ?? DEFAULT_RECITER;
}

export function isSurahAvailable(reciter: Reciter, surahId: number): boolean {
  if (surahId < 1 || surahId > 114) return false;
  if (reciter.availableSurahsList && reciter.availableSurahsList.length > 0) {
    return reciter.availableSurahsList.includes(surahId);
  }
  if (reciter.availableSurahsCount >= 114) return true;
  if (reciter.availableSurahsCount <= 0) return false;
  return surahId <= reciter.availableSurahsCount;
}

export function getAudioUrl(reciter: Reciter, surahId: number): string {
  const paddedNumber = String(surahId).padStart(3, '0');
  const trimmed = reciter.serverUrl.trim();
  return trimmed.endsWith('/')
    ? `${trimmed}${paddedNumber}.mp3`
    : `${trimmed}/${paddedNumber}.mp3`;
}

export function isValidHttpsServerUrl(rawUrl: string): boolean {
  const trimmed = rawUrl.trim();
  if (trimmed.length <= 8 || !trimmed.toLowerCase().startsWith('https://')) {
    return false;
  }
  if (/\s/.test(trimmed)) {
    return false;
  }
  try {
    const parsed = new URL(trimmed);
    return parsed.protocol === 'https:' && parsed.hostname.trim().length > 0;
  } catch {
    return false;
  }
}

export function normalizeServerUrl(rawUrl: string): string {
  const trimmed = rawUrl.trim();
  return trimmed.endsWith('/') ? trimmed : `${trimmed}/`;
}
