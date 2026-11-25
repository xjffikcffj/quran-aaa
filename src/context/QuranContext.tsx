import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react';
import {
  DEFAULT_RECITER,
  DEFAULT_RECITERS,
  isValidHttpsServerUrl,
  normalizeServerUrl,
} from '../data/defaultReciters';
import { ALL_SURAHS, getSurahById } from '../data/quranData';
import { exportBackupJson, importBackupJsonWithResult } from '../services/backupService';
import { downloadService } from '../services/downloadService';
import { showFridayReminderNotification } from '../services/fridayReminderService';
import { playerService } from '../services/playerService';
import {
  deleteBookmarkById,
  deleteCustomReciterById,
  getAllBookmarks,
  getAllCustomReciters,
  getAllSurahStates,
  getAppPref,
  getSurahState,
  getThemeMode,
  insertBookmark,
  setAppPref,
  setThemeMode as persistThemeMode,
  upsertCustomReciter,
  upsertSurahState,
} from '../services/storageService';
import type {
  BackupImportResult,
  Bookmark,
  DownloadItem,
  PlayerState,
  Reciter,
  RepeatMode,
  SleepTimerOption,
  Surah,
  ThemeMode,
} from '../types/quran';
import { matchesQuery } from '../utils/arabicSearch';

export interface ToastMessage {
  id: number;
  text: string;
  variant: 'info' | 'success' | 'error';
}

interface QuranContextValue {
  playerState: PlayerState;
  reciters: Reciter[];
  displayedSurahs: Surah[];
  searchQuery: string;
  showFavoritesOnly: boolean;
  favoriteSurahNumbers: number[];
  bookmarks: Bookmark[];
  downloads: DownloadItem[];
  fridayReminderEnabled: boolean;
  themeMode: ThemeMode;
  toasts: ToastMessage[];
  showToast: (text: string, variant?: ToastMessage['variant']) => void;
  dismissToast: (id: number) => void;
  setSearchQuery: (query: string) => void;
  toggleFavoritesOnly: () => void;
  setShowFavoritesOnly: (val: boolean) => void;
  toggleFavorite: (surahId: number) => Promise<void>;
  playSurah: (
    surah: Surah,
    options?: { seekToMs?: number; startFromBeginning?: boolean; autoPlay?: boolean }
  ) => Promise<void>;
  togglePlayPause: () => void;
  resume: () => void;
  pause: () => void;
  playNext: () => void;
  playPrevious: () => void;
  seekTo: (positionMs: number) => void;
  selectReciter: (reciter: Reciter) => void;
  setRepeatMode: (mode: RepeatMode) => void;
  setPlaybackSpeed: (speed: number) => void;
  setSleepTimer: (option: SleepTimerOption) => void;
  addBookmark: (surah: Surah, note: string) => Promise<void>;
  deleteBookmark: (bookmark: Bookmark) => Promise<void>;
  playFromBookmark: (bookmark: Bookmark) => Promise<void>;
  startDownload: (surah: Surah, reciterOverride?: Reciter) => Promise<void>;
  cancelDownload: (downloadId: string) => Promise<void>;
  deleteDownload: (downloadId: string) => Promise<void>;
  isSurahDownloaded: (surahId: number) => boolean;
  getSurahDownloadItem: (surahId: number) => DownloadItem | undefined;
  toggleFridayReminder: (enabled: boolean) => void;
  triggerFridayReminderTest: (onNavigateToKahf?: () => void) => Promise<void>;
  addCustomReciter: (name: StringOrEmpty, serverUrl: string, availableCount: number) => Promise<boolean>;
  deleteCustomReciter: (reciterId: string) => Promise<void>;
  exportBackup: () => Promise<string>;
  importBackup: (jsonString: string) => Promise<BackupImportResult>;
  setThemeMode: (mode: ThemeMode) => void;
}

type StringOrEmpty = string;

const QuranContext = createContext<QuranContextValue | null>(null);

export function QuranProvider({ children }: { children: React.ReactNode }) {
  const [playerState, setPlayerState] = useState<PlayerState>(() => playerService.getState());
  const [customReciters, setCustomReciters] = useState<Reciter[]>([]);
  const [favoriteSurahNumbers, setFavoriteSurahNumbers] = useState<number[]>([]);
  const [bookmarks, setBookmarks] = useState<Bookmark[]>([]);
  const [downloads, setDownloads] = useState<DownloadItem[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [showFavoritesOnly, setShowFavoritesOnly] = useState(false);
  const [fridayReminderEnabled, setFridayReminderEnabled] = useState<boolean>(() =>
    getAppPref<boolean>('friday_reminder', true)
  );
  const [themeMode, setThemeModeState] = useState<ThemeMode>(() => getThemeMode());
  const [toasts, setToasts] = useState<ToastMessage[]>([]);

  const showToast = useCallback(
    (text: string, variant: ToastMessage['variant'] = 'info') => {
      const id = Date.now() + Math.floor(Math.random() * 1000);
      setToasts((prev) => [...prev.slice(-2), { id, text, variant }]);
      window.setTimeout(() => {
        setToasts((prev) => prev.filter((t) => t.id !== id));
      }, 3600);
    },
    []
  );

  const dismissToast = useCallback((id: number) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const refreshPersistence = useCallback(async () => {
    const [customs, states, savedBookmarks] = await Promise.all([
      getAllCustomReciters(),
      getAllSurahStates(),
      getAllBookmarks(),
    ]);
    setCustomReciters(customs);
    setFavoriteSurahNumbers(states.filter((s) => s.isFavorite).map((s) => s.surahNumber));
    setBookmarks(savedBookmarks);
  }, []);

  useEffect(() => {
    void downloadService.init();
    void refreshPersistence();

    const unsubPlayer = playerService.subscribe((next) => {
      setPlayerState(next);
    });
    const unsubDownloads = downloadService.subscribe((items) => {
      setDownloads(items);
    });

    return () => {
      unsubPlayer();
      unsubDownloads();
    };
  }, [refreshPersistence]);

  // Apply light/dark theme to <html>
  useEffect(() => {
    const applyTheme = () => {
      const root = document.documentElement;
      const prefersDark =
        typeof window !== 'undefined' &&
        window.matchMedia('(prefers-color-scheme: dark)').matches;
      const isDark = themeMode === 'dark' || (themeMode === 'system' && prefersDark);
      if (isDark) {
        root.classList.add('dark');
      } else {
        root.classList.remove('dark');
      }
    };

    applyTheme();

    if (themeMode === 'system' && typeof window !== 'undefined') {
      const mq = window.matchMedia('(prefers-color-scheme: dark)');
      const handler = () => applyTheme();
      mq.addEventListener('change', handler);
      return () => mq.removeEventListener('change', handler);
    }
    return undefined;
  }, [themeMode]);

  // Show player error messages as snackbar/toast
  useEffect(() => {
    if (playerState.errorMessage) {
      showToast(playerState.errorMessage, 'error');
    }
  }, [playerState.errorMessage, showToast]);

  const setThemeMode = useCallback((mode: ThemeMode) => {
    setThemeModeState(mode);
    persistThemeMode(mode);
  }, []);

  const reciters = useMemo<Reciter[]>(() => {
    return [...DEFAULT_RECITERS, ...customReciters];
  }, [customReciters]);

  const displayedSurahs = useMemo<Surah[]>(() => {
    return ALL_SURAHS.filter((surah) => {
      const matchesFav = !showFavoritesOnly || favoriteSurahNumbers.includes(surah.id);
      const matchesSearch = matchesQuery(surah.id, surah.name, surah.englishName, searchQuery);
      return matchesFav && matchesSearch;
    });
  }, [favoriteSurahNumbers, searchQuery, showFavoritesOnly]);

  const toggleFavoritesOnly = useCallback(() => {
    setShowFavoritesOnly((prev) => !prev);
  }, []);

  const toggleFavorite = useCallback(
    async (surahId: number) => {
      const existing = await getSurahState(surahId);
      const nextFav = !(existing?.isFavorite ?? favoriteSurahNumbers.includes(surahId));
      await upsertSurahState({
        surahNumber: surahId,
        isFavorite: nextFav,
        lastPositionMs: existing?.lastPositionMs ?? 0,
        lastPlayedTimestamp: existing?.lastPlayedTimestamp ?? 0,
      });
      await refreshPersistence();
    },
    [favoriteSurahNumbers, refreshPersistence]
  );

  const playSurah = useCallback(
    async (
      surah: Surah,
      options?: { seekToMs?: number; startFromBeginning?: boolean; autoPlay?: boolean }
    ) => {
      await playerService.playSurah(surah, options);
    },
    []
  );

  const togglePlayPause = useCallback(() => playerService.togglePlayPause(), []);
  const resume = useCallback(() => playerService.resume(), []);
  const pause = useCallback(() => playerService.pause(), []);
  const playNext = useCallback(() => playerService.playNextSurah(), []);
  const playPrevious = useCallback(() => playerService.playPreviousSurah(), []);
  const seekTo = useCallback((ms: number) => playerService.seekTo(ms), []);
  const selectReciter = useCallback((reciter: Reciter) => playerService.setReciter(reciter), []);
  const setRepeatMode = useCallback((mode: RepeatMode) => playerService.setRepeatMode(mode), []);
  const setPlaybackSpeed = useCallback(
    (speed: number) => playerService.setPlaybackSpeed(speed),
    []
  );
  const setSleepTimer = useCallback(
    (opt: SleepTimerOption) => playerService.setSleepTimer(opt),
    []
  );

  const addBookmark = useCallback(
    async (surah: Surah, note: string) => {
      const reciter = playerState.currentReciter ?? DEFAULT_RECITER;
      const pos = playerState.currentSurah?.id === surah.id ? playerState.currentPositionMs : 0;
      await insertBookmark({
        surahNumber: surah.id,
        surahName: surah.name,
        reciterId: reciter.id,
        reciterName: reciter.name,
        positionMs: pos,
        note: note.trim(),
        timestamp: Date.now(),
      });
      await refreshPersistence();
    },
    [playerState.currentPositionMs, playerState.currentReciter, playerState.currentSurah?.id, refreshPersistence]
  );

  const deleteBookmark = useCallback(
    async (bookmark: Bookmark) => {
      await deleteBookmarkById(bookmark.id);
      await refreshPersistence();
    },
    [refreshPersistence]
  );

  const playFromBookmark = useCallback(
    async (bookmark: Bookmark) => {
      const surah = getSurahById(bookmark.surahNumber);
      if (!surah) return;
      const reciter =
        reciters.find((r) => r.id === bookmark.reciterId) ??
        playerState.currentReciter ??
        DEFAULT_RECITER;
      playerService.setReciter(reciter);
      await playerService.playSurah(surah, { seekToMs: bookmark.positionMs, autoPlay: true });
    },
    [playerState.currentReciter, reciters]
  );

  const startDownload = useCallback(
    async (surah: Surah, reciterOverride?: Reciter) => {
      const reciter = reciterOverride ?? playerState.currentReciter ?? DEFAULT_RECITER;
      await downloadService.startDownload(reciter, surah);
    },
    [playerState.currentReciter]
  );

  const cancelDownload = useCallback(async (downloadId: string) => {
    await downloadService.cancelDownload(downloadId);
  }, []);

  const deleteDownload = useCallback(async (downloadId: string) => {
    await downloadService.deleteDownload(downloadId);
  }, []);

  const isSurahDownloaded = useCallback(
    (surahId: number) => {
      const reciter = playerState.currentReciter ?? DEFAULT_RECITER;
      return downloads.some(
        (d) => d.id === `${reciter.id}_${surahId}` && d.status === 'COMPLETED'
      );
    },
    [downloads, playerState.currentReciter]
  );

  const getSurahDownloadItem = useCallback(
    (surahId: number) => {
      const reciter = playerState.currentReciter ?? DEFAULT_RECITER;
      return downloads.find((d) => d.id === `${reciter.id}_${surahId}`);
    },
    [downloads, playerState.currentReciter]
  );

  const toggleFridayReminder = useCallback((enabled: boolean) => {
    setFridayReminderEnabled(enabled);
    setAppPref('friday_reminder', enabled);
  }, []);

  const triggerFridayReminderTest = useCallback(
    async (onNavigateToKahf?: () => void) => {
      const sentNative = await showFridayReminderNotification(onNavigateToKahf);
      if (sentNative) {
        showToast('تم إرسال إشعار تذكير يوم الجمعة التجريبي', 'success');
      } else {
        showToast(
          'تذكير يوم الجمعة: لا تنسَ قراءة أو الاستماع إلى سورة الكهف يوم الجمعة',
          'info'
        );
      }
    },
    [showToast]
  );

  const addCustomReciter = useCallback(
    async (name: string, serverUrl: string, availableCount: number) => {
      const cleanName = name.trim();
      const trimmedUrl = serverUrl.trim();
      if (!cleanName || !isValidHttpsServerUrl(trimmedUrl)) {
        return false;
      }
      const cleanUrl = normalizeServerUrl(trimmedUrl);
      const count = Math.min(114, Math.max(1, availableCount));
      const newReciter: Reciter = {
        id: `custom_${Date.now()}`,
        name: cleanName,
        serverUrl: cleanUrl,
        availableSurahsCount: count,
        isCustom: true,
        subInfo: 'قارئ مخصص مضاف من لوحة المطور',
      };
      await upsertCustomReciter(newReciter);
      await refreshPersistence();
      return true;
    },
    [refreshPersistence]
  );

  const deleteCustomReciter = useCallback(
    async (reciterId: string) => {
      await deleteCustomReciterById(reciterId);
      if (playerState.currentReciter?.id === reciterId) {
        playerService.setReciter(DEFAULT_RECITER);
      }
      await refreshPersistence();
    },
    [playerState.currentReciter?.id, refreshPersistence]
  );

  const exportBackup = useCallback(async () => {
    return exportBackupJson();
  }, []);

  const importBackup = useCallback(
    async (jsonString: string) => {
      const result = await importBackupJsonWithResult(jsonString);
      if (result.success) {
        await refreshPersistence();
      }
      return result;
    },
    [refreshPersistence]
  );

  const value: QuranContextValue = {
    playerState,
    reciters,
    displayedSurahs,
    searchQuery,
    showFavoritesOnly,
    favoriteSurahNumbers,
    bookmarks,
    downloads,
    fridayReminderEnabled,
    themeMode,
    toasts,
    showToast,
    dismissToast,
    setSearchQuery,
    toggleFavoritesOnly,
    setShowFavoritesOnly,
    toggleFavorite,
    playSurah,
    togglePlayPause,
    resume,
    pause,
    playNext,
    playPrevious,
    seekTo,
    selectReciter,
    setRepeatMode,
    setPlaybackSpeed,
    setSleepTimer,
    addBookmark,
    deleteBookmark,
    playFromBookmark,
    startDownload,
    cancelDownload,
    deleteDownload,
    isSurahDownloaded,
    getSurahDownloadItem,
    toggleFridayReminder,
    triggerFridayReminderTest,
    addCustomReciter,
    deleteCustomReciter,
    exportBackup,
    importBackup,
    setThemeMode,
  };

  return <QuranContext.Provider value={value}>{children}</QuranContext.Provider>;
}

export function useQuranApp(): QuranContextValue {
  const ctx = useContext(QuranContext);
  if (!ctx) {
    throw new Error('useQuranApp must be used inside QuranProvider');
  }
  return ctx;
}
