import type {
  Bookmark,
  DownloadItem,
  Reciter,
  RepeatMode,
  SleepTimerOption,
  SurahState,
  ThemeMode,
} from '../types/quran';
import { DEFAULT_RECITER, DEFAULT_RECITERS } from '../data/defaultReciters';
import { DownloadStatus } from '../types/quran';

const DB_NAME = 'quran_audio_db';
const DB_VERSION = 1;

const STORE_AUDIO_BLOBS = 'audio_blobs';
const STORE_DOWNLOADS = 'downloads';
const STORE_BOOKMARKS = 'bookmarks';
const STORE_CUSTOM_RECITERS = 'custom_reciters';
const STORE_SURAH_STATES = 'surah_states';

const PREFS_KEY = 'quran_prefs';
const PLAYER_PREFS_KEY = 'quran_player_prefs';

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    if (typeof indexedDB === 'undefined') {
      reject(new Error('IndexedDB unavailable'));
      return;
    }
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE_AUDIO_BLOBS)) {
        db.createObjectStore(STORE_AUDIO_BLOBS, { keyPath: 'id' });
      }
      if (!db.objectStoreNames.contains(STORE_DOWNLOADS)) {
        db.createObjectStore(STORE_DOWNLOADS, { keyPath: 'id' });
      }
      if (!db.objectStoreNames.contains(STORE_BOOKMARKS)) {
        db.createObjectStore(STORE_BOOKMARKS, { keyPath: 'id', autoIncrement: true });
      }
      if (!db.objectStoreNames.contains(STORE_CUSTOM_RECITERS)) {
        db.createObjectStore(STORE_CUSTOM_RECITERS, { keyPath: 'id' });
      }
      if (!db.objectStoreNames.contains(STORE_SURAH_STATES)) {
        db.createObjectStore(STORE_SURAH_STATES, { keyPath: 'surahNumber' });
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error('Failed to open IndexedDB'));
  });
}

async function withStore<T>(
  storeName: string,
  mode: IDBTransactionMode,
  action: (store: IDBObjectStore) => IDBRequest<T>
): Promise<T> {
  const db = await openDatabase();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(storeName, mode);
    const store = tx.objectStore(storeName);
    const req = action(store);
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error ?? new Error('IDB request failed'));
  });
}

// --- General & Player Prefs (localStorage) ---

function readJsonMap(key: string): Record<string, unknown> {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return {};
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

function writeJsonMap(key: string, map: Record<string, unknown>): boolean {
  try {
    localStorage.setItem(key, JSON.stringify(map));
    return true;
  } catch {
    return false;
  }
}

export function getAppPref<T>(key: string, fallback: T): T {
  const map = readJsonMap(PREFS_KEY);
  return key in map ? (map[key] as T) : fallback;
}

export function setAppPref(key: string, value: unknown): void {
  const map = readJsonMap(PREFS_KEY);
  map[key] = value;
  writeJsonMap(PREFS_KEY, map);
}

export function getThemeMode(): ThemeMode {
  const val = getAppPref<string>('theme_mode', 'system');
  if (val === 'light' || val === 'dark' || val === 'system') return val;
  return 'system';
}

export function setThemeMode(mode: ThemeMode): void {
  setAppPref('theme_mode', mode);
}

export function getPlayerPrefs(): Record<string, unknown> {
  return readJsonMap(PLAYER_PREFS_KEY);
}

export function setPlayerPrefs(updates: Record<string, unknown>, removals: string[] = []): boolean {
  const map = readJsonMap(PLAYER_PREFS_KEY);
  for (const k of removals) {
    delete map[k];
  }
  for (const [k, v] of Object.entries(updates)) {
    if (v === undefined) {
      delete map[k];
    } else {
      map[k] = v;
    }
  }
  return writeJsonMap(PLAYER_PREFS_KEY, map);
}

export function getSavedPosition(reciterId: string, surahNumber: number): number {
  const map = getPlayerPrefs();
  const val = map[`pos_${reciterId}_${surahNumber}`];
  return typeof val === 'number' && Number.isFinite(val) ? val : 0;
}

export function getSavedDuration(reciterId: string, surahNumber: number): number {
  const map = getPlayerPrefs();
  const val = map[`dur_${reciterId}_${surahNumber}`];
  return typeof val === 'number' && Number.isFinite(val) ? val : 0;
}

export function loadSavedReciterSync(): Reciter {
  const map = getPlayerPrefs();
  const reciterId =
    typeof map.saved_reciter_id === 'string' && map.saved_reciter_id
      ? map.saved_reciter_id
      : DEFAULT_RECITER.id;

  const builtIn = DEFAULT_RECITERS.find((r) => r.id === reciterId);
  if (builtIn) return builtIn;

  const cachedJson = map.saved_custom_reciter_json;
  if (typeof cachedJson === 'string' && cachedJson.trim()) {
    try {
      const obj = JSON.parse(cachedJson);
      if (obj && obj.id === reciterId) {
        const csv = typeof obj.availableSurahsCsv === 'string' ? obj.availableSurahsCsv : '';
        const list = csv
          ? csv
              .split(',')
              .map((s: string) => Number.parseInt(s.trim(), 10))
              .filter((n: number) => !Number.isNaN(n))
          : [];
        return {
          id: reciterId,
          name: typeof obj.name === 'string' ? obj.name : DEFAULT_RECITER.name,
          serverUrl: typeof obj.serverUrl === 'string' ? obj.serverUrl : '',
          availableSurahsCount:
            typeof obj.availableSurahsCount === 'number' ? obj.availableSurahsCount : 114,
          availableSurahsList: list,
          isCustom: true,
          subInfo: typeof obj.subInfo === 'string' ? obj.subInfo : 'قارئ مخصص',
        };
      }
    } catch {
      // fallback
    }
  }
  return DEFAULT_RECITER;
}

export function saveCustomReciterSnapshot(reciter: Reciter): void {
  if (reciter.isCustom) {
    const snapshot = JSON.stringify({
      id: reciter.id,
      name: reciter.name,
      serverUrl: reciter.serverUrl,
      availableSurahsCount: reciter.availableSurahsCount,
      availableSurahsCsv: (reciter.availableSurahsList ?? []).join(','),
      subInfo: reciter.subInfo ?? '',
    });
    setPlayerPrefs({ saved_custom_reciter_json: snapshot });
  } else {
    setPlayerPrefs({}, ['saved_custom_reciter_json']);
  }
}

export function loadSavedRepeatMode(): RepeatMode {
  const map = getPlayerPrefs();
  const raw = map.saved_repeat_mode;
  if (raw === 'NONE' || raw === 'ONE' || raw === 'ALL') return raw as RepeatMode;
  return 'ALL' as RepeatMode;
}

export function loadSavedPlaybackSpeed(): number {
  const map = getPlayerPrefs();
  const raw = map.saved_playback_speed;
  if (typeof raw === 'number' && Number.isFinite(raw)) {
    return Math.min(2.0, Math.max(0.5, raw));
  }
  return 1.0;
}

export function getSavedSleepTimer(): { option: SleepTimerOption; deadlineMs: number } | null {
  const map = getPlayerPrefs();
  const opt = map.sleep_timer_option;
  if (typeof opt !== 'string') return null;
  const deadline = typeof map.sleep_timer_end_ms === 'number' ? map.sleep_timer_end_ms : 0;
  return { option: opt as SleepTimerOption, deadlineMs: deadline };
}

// --- IndexedDB Entity Helpers ---

export async function getAllCustomReciters(): Promise<Reciter[]> {
  try {
    const list = await withStore<Reciter[]>(STORE_CUSTOM_RECITERS, 'readonly', (s) => s.getAll());
    return list.map((r) => ({ ...r, isCustom: true }));
  } catch {
    return [];
  }
}

export async function getCustomReciterById(id: string): Promise<Reciter | undefined> {
  try {
    return await withStore<Reciter | undefined>(STORE_CUSTOM_RECITERS, 'readonly', (s) => s.get(id));
  } catch {
    return undefined;
  }
}

export async function upsertCustomReciter(reciter: Reciter): Promise<void> {
  await withStore(STORE_CUSTOM_RECITERS, 'readwrite', (s) => s.put({ ...reciter, isCustom: true }));
}

export async function deleteCustomReciterById(id: string): Promise<void> {
  await withStore(STORE_CUSTOM_RECITERS, 'readwrite', (s) => s.delete(id));
}

export async function getAllBookmarks(): Promise<Bookmark[]> {
  try {
    const list = await withStore<Bookmark[]>(STORE_BOOKMARKS, 'readonly', (s) => s.getAll());
    return list.sort((a, b) => b.timestamp - a.timestamp);
  } catch {
    return [];
  }
}

export async function insertBookmark(bookmark: Omit<Bookmark, 'id'>): Promise<number> {
  const result = await withStore<IDBValidKey>(STORE_BOOKMARKS, 'readwrite', (s) =>
    s.add(bookmark)
  );
  return Number(result);
}

export async function deleteBookmarkById(id: number): Promise<void> {
  await withStore(STORE_BOOKMARKS, 'readwrite', (s) => s.delete(id));
}

export async function getAllSurahStates(): Promise<SurahState[]> {
  try {
    return await withStore<SurahState[]>(STORE_SURAH_STATES, 'readonly', (s) => s.getAll());
  } catch {
    return [];
  }
}

export async function getSurahState(surahNumber: number): Promise<SurahState | undefined> {
  try {
    return await withStore<SurahState | undefined>(STORE_SURAH_STATES, 'readonly', (s) =>
      s.get(surahNumber)
    );
  } catch {
    return undefined;
  }
}

export async function upsertSurahState(state: SurahState): Promise<void> {
  await withStore(STORE_SURAH_STATES, 'readwrite', (s) => s.put(state));
}

export async function updateSurahLastPosition(
  surahNumber: number,
  positionMs: number
): Promise<void> {
  const existing = await getSurahState(surahNumber);
  await upsertSurahState({
    surahNumber,
    isFavorite: existing?.isFavorite ?? false,
    lastPositionMs: positionMs,
    lastPlayedTimestamp: Date.now(),
  });
}

export async function getAllDownloads(): Promise<DownloadItem[]> {
  try {
    const list = await withStore<DownloadItem[]>(STORE_DOWNLOADS, 'readonly', (s) => s.getAll());
    return list.sort((a, b) => a.surahNumber - b.surahNumber);
  } catch {
    return [];
  }
}

export async function getDownloadById(id: string): Promise<DownloadItem | undefined> {
  try {
    return await withStore<DownloadItem | undefined>(STORE_DOWNLOADS, 'readonly', (s) => s.get(id));
  } catch {
    return undefined;
  }
}

export async function upsertDownload(item: DownloadItem): Promise<void> {
  await withStore(STORE_DOWNLOADS, 'readwrite', (s) => s.put(item));
}

export async function deleteDownloadRecord(id: string): Promise<void> {
  await withStore(STORE_DOWNLOADS, 'readwrite', (s) => s.delete(id));
}

export async function saveAudioBlob(id: string, blob: Blob): Promise<void> {
  await withStore(STORE_AUDIO_BLOBS, 'readwrite', (s) => s.put({ id, blob }));
}

export async function getAudioBlob(id: string): Promise<Blob | null> {
  try {
    const record = await withStore<{ id: string; blob: Blob } | undefined>(
      STORE_AUDIO_BLOBS,
      'readonly',
      (s) => s.get(id)
    );
    return record?.blob ?? null;
  } catch {
    return null;
  }
}

export async function deleteAudioBlob(id: string): Promise<void> {
  await withStore(STORE_AUDIO_BLOBS, 'readwrite', (s) => s.delete(id));
}

export async function markStuckDownloadsFailed(): Promise<void> {
  const all = await getAllDownloads();
  for (const item of all) {
    if (item.status === DownloadStatus.DOWNLOADING) {
      await upsertDownload({
        ...item,
        status: DownloadStatus.FAILED,
        errorMessage: 'توقف التنزيل قبل اكتماله، اضغط لإعادة المحاولة',
      });
    }
  }
}
