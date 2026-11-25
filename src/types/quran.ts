export interface Surah {
  id: number;
  name: string;
  englishName: string;
  versesCount: number;
}

export interface Reciter {
  id: string;
  name: string;
  serverUrl: string;
  availableSurahsCount: number;
  availableSurahsList?: number[];
  isCustom?: boolean;
  subInfo?: string;
}

export interface Bookmark {
  id: number;
  surahNumber: number;
  surahName: string;
  reciterId: string;
  reciterName: string;
  positionMs: number;
  note: string;
  timestamp: number;
}

export enum DownloadStatus {
  IDLE = 'IDLE',
  DOWNLOADING = 'DOWNLOADING',
  COMPLETED = 'COMPLETED',
  FAILED = 'FAILED',
}

export interface DownloadItem {
  id: string; // `${reciterId}_${surahNumber}`
  surahNumber: number;
  surahName: string;
  reciterId: string;
  reciterName: string;
  fileSizeBytes: number;
  status: DownloadStatus;
  progress: number;
  errorMessage?: string;
}

export interface SurahState {
  surahNumber: number;
  isFavorite: boolean;
  lastPositionMs: number;
  lastPlayedTimestamp: number;
}

export enum RepeatMode {
  NONE = 'NONE',
  ONE = 'ONE',
  ALL = 'ALL',
}

export enum SleepTimerOption {
  OFF = 'OFF',
  END_OF_SURAH = 'END_OF_SURAH',
  MINUTES_5 = 'MINUTES_5',
  MINUTES_10 = 'MINUTES_10',
  MINUTES_15 = 'MINUTES_15',
  MINUTES_20 = 'MINUTES_20',
  MINUTES_30 = 'MINUTES_30',
  MINUTES_45 = 'MINUTES_45',
  HOUR_1 = 'HOUR_1',
  SECONDS_20_TEST = 'SECONDS_20_TEST',
}

export interface SleepTimerOptionMeta {
  option: SleepTimerOption;
  totalSeconds: number;
  label: string;
}

export const SLEEP_TIMER_OPTIONS: Record<SleepTimerOption, SleepTimerOptionMeta> = {
  [SleepTimerOption.OFF]: {
    option: SleepTimerOption.OFF,
    totalSeconds: 0,
    label: 'إيقاف مؤقت النوم',
  },
  [SleepTimerOption.END_OF_SURAH]: {
    option: SleepTimerOption.END_OF_SURAH,
    totalSeconds: -1,
    label: 'عند نهاية السورة',
  },
  [SleepTimerOption.MINUTES_5]: {
    option: SleepTimerOption.MINUTES_5,
    totalSeconds: 5 * 60,
    label: '5 دقائق',
  },
  [SleepTimerOption.MINUTES_10]: {
    option: SleepTimerOption.MINUTES_10,
    totalSeconds: 10 * 60,
    label: '10 دقائق',
  },
  [SleepTimerOption.MINUTES_15]: {
    option: SleepTimerOption.MINUTES_15,
    totalSeconds: 15 * 60,
    label: '15 دقيقة',
  },
  [SleepTimerOption.MINUTES_20]: {
    option: SleepTimerOption.MINUTES_20,
    totalSeconds: 20 * 60,
    label: '20 دقيقة',
  },
  [SleepTimerOption.MINUTES_30]: {
    option: SleepTimerOption.MINUTES_30,
    totalSeconds: 30 * 60,
    label: '30 دقيقة',
  },
  [SleepTimerOption.MINUTES_45]: {
    option: SleepTimerOption.MINUTES_45,
    totalSeconds: 45 * 60,
    label: '45 دقيقة',
  },
  [SleepTimerOption.HOUR_1]: {
    option: SleepTimerOption.HOUR_1,
    totalSeconds: 60 * 60,
    label: 'ساعة واحدة',
  },
  [SleepTimerOption.SECONDS_20_TEST]: {
    option: SleepTimerOption.SECONDS_20_TEST,
    totalSeconds: 20,
    label: '20 ثانية (اختبار)',
  },
};

export interface PlayerState {
  isPlaying: boolean;
  isBuffering: boolean;
  currentSurah: Surah | null;
  currentReciter: Reciter | null;
  currentPositionMs: number;
  totalDurationMs: number;
  playbackSpeed: number;
  repeatMode: RepeatMode;
  sleepTimerOption: SleepTimerOption;
  sleepTimerSecondsRemaining: number;
  isPlayingOffline: boolean;
  errorMessage: string | null;
}

export interface BackupImportResult {
  success: boolean;
  message: string;
}

export type ThemeMode = 'system' | 'light' | 'dark';

export type QuranNavTab = 'surahs' | 'reciters' | 'downloads' | 'bookmarks';
