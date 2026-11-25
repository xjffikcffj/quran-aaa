import {
  DEFAULT_RECITER,
  DEFAULT_RECITERS,
  getAudioUrl,
  isSurahAvailable,
} from '../data/defaultReciters';
import { ALL_SURAHS, getSurahById } from '../data/quranData';
import {
  RepeatMode,
  SleepTimerOption,
  type PlayerState,
  type Reciter,
  type Surah,
} from '../types/quran';
import { downloadService } from './downloadService';
import {
  getCustomReciterById,
  getPlayerPrefs,
  getSavedDuration,
  getSavedPosition,
  getSavedSleepTimer,
  loadSavedPlaybackSpeed,
  loadSavedReciterSync,
  loadSavedRepeatMode,
  saveCustomReciterSnapshot,
  setPlayerPrefs,
  updateSurahLastPosition,
} from './storageService';

type PlayerListener = (state: PlayerState) => void;

const PREPARE_TIMEOUT_MS = 20_000;
const MAX_RECONNECT_ATTEMPTS = 1;

class PlayerService {
  private audio: HTMLAudioElement | null = null;
  private activeObjectUrl: string | null = null;
  private listeners = new Set<PlayerListener>();
  private state: PlayerState;

  private playbackRequestId = 0;
  private isPrepared = false;
  private reconnectAttemptCount = 0;
  private lastPausedTimeMs = 0;
  private isNetworkLostDuringPlayback = false;

  private prepareTimeoutTimer: number | null = null;
  private sleepTimerInterval: number | null = null;
  private reconnectTimer: number | null = null;

  constructor() {
    const reciter = loadSavedReciterSync();
    const prefs = getPlayerPrefs();
    const savedSurahId = typeof prefs.last_surah_id === 'number' ? prefs.last_surah_id : 1;
    const surah = getSurahById(savedSurahId) ?? ALL_SURAHS[0];
    const savedPos = getSavedPosition(reciter.id, surah.id);
    const savedDur = getSavedDuration(reciter.id, surah.id);

    this.state = {
      isPlaying: false,
      isBuffering: false,
      currentSurah: surah,
      currentReciter: reciter,
      currentPositionMs: savedPos,
      totalDurationMs: savedDur,
      playbackSpeed: loadSavedPlaybackSpeed(),
      repeatMode: loadSavedRepeatMode(),
      sleepTimerOption: SleepTimerOption.OFF,
      sleepTimerSecondsRemaining: 0,
      isPlayingOffline: false,
      errorMessage: null,
    };

    void this.restoreCustomReciterIfNeeded(reciter.id, surah.id);
    this.restoreSavedSleepTimer();
    this.setupNetworkListeners();
    this.setupMediaSessionHandlers();
  }

  subscribe(listener: PlayerListener): () => void {
    this.listeners.add(listener);
    listener(this.state);
    return () => {
      this.listeners.delete(listener);
    };
  }

  getState(): PlayerState {
    return this.state;
  }

  private updateState(partial: Partial<PlayerState>): void {
    this.state = { ...this.state, ...partial };
    for (const listener of this.listeners) {
      listener(this.state);
    }
    this.updateMediaSessionState();
  }

  private async restoreCustomReciterIfNeeded(savedId: string, savedSurahId: number): Promise<void> {
    if (DEFAULT_RECITERS.some((r) => r.id === savedId)) return;
    const dbReciter = await getCustomReciterById(savedId);
    if (!dbReciter) return;
    saveCustomReciterSnapshot(dbReciter);
    const pos = getSavedPosition(dbReciter.id, savedSurahId);
    const dur = getSavedDuration(dbReciter.id, savedSurahId);
    if (
      !this.state.isPlaying &&
      !this.state.isBuffering &&
      this.state.currentReciter?.id !== dbReciter.id
    ) {
      this.updateState({
        currentReciter: dbReciter,
        currentPositionMs: pos,
        totalDurationMs: dur,
      });
    }
  }

  reloadPersistedStateIfIdle(): void {
    if (this.state.isPlaying || this.state.isBuffering) return;
    const reciter = loadSavedReciterSync();
    const prefs = getPlayerPrefs();
    const savedSurahId = typeof prefs.last_surah_id === 'number' ? prefs.last_surah_id : 1;
    const surah = getSurahById(savedSurahId) ?? ALL_SURAHS[0];
    const savedPos = getSavedPosition(reciter.id, surah.id);
    const savedDur = getSavedDuration(reciter.id, surah.id);
    this.updateState({
      currentReciter: reciter,
      currentSurah: surah,
      currentPositionMs: savedPos,
      totalDurationMs: savedDur,
      repeatMode: loadSavedRepeatMode(),
      playbackSpeed: loadSavedPlaybackSpeed(),
      isPlaying: false,
      isBuffering: false,
    });
    void this.restoreCustomReciterIfNeeded(reciter.id, surah.id);
  }

  private setupNetworkListeners(): void {
    if (typeof window === 'undefined') return;
    window.addEventListener('online', () => {
      if (this.isNetworkLostDuringPlayback) {
        this.isNetworkLostDuringPlayback = false;
        this.updateState({ errorMessage: null });
      }
    });
    window.addEventListener('offline', () => {
      if (!this.state.isPlayingOffline && this.state.isBuffering) {
        this.isNetworkLostDuringPlayback = true;
        this.playbackRequestId++;
        this.releaseAudio();
        this.updateState({
          isBuffering: false,
          isPlaying: false,
          errorMessage:
            'انقطع الاتصال بالإنترنت. يمكنك متابعة التشغيل عند عودة الشبكة أو الاستماع للسور المنزلة.',
        });
      }
    });
  }

  private setupMediaSessionHandlers(): void {
    if (typeof navigator === 'undefined' || !('mediaSession' in navigator)) return;
    try {
      navigator.mediaSession.setActionHandler('play', () => this.resume());
      navigator.mediaSession.setActionHandler('pause', () => this.pause());
      navigator.mediaSession.setActionHandler('previoustrack', () => this.playPreviousSurah());
      navigator.mediaSession.setActionHandler('nexttrack', () => this.playNextSurah());
      navigator.mediaSession.setActionHandler('seekto', (details) => {
        if (typeof details.seekTime === 'number') {
          this.seekTo(details.seekTime * 1000);
        }
      });
    } catch {
      // ignore unsupported MediaSession actions
    }
  }

  private updateMediaSessionState(): void {
    if (typeof navigator === 'undefined' || !('mediaSession' in navigator)) return;
    try {
      const surah = this.state.currentSurah;
      const reciter = this.state.currentReciter;
      if (surah && typeof MediaMetadata !== 'undefined') {
        navigator.mediaSession.metadata = new MediaMetadata({
          title: `سورة ${surah.name}`,
          artist: reciter?.name ?? 'القرآن الكريم',
          album: 'القرآن الصوتي',
          artwork: [{ src: '/assets/ic_app_foreground_art.jpg', sizes: '512x512', type: 'image/jpeg' }],
        });
      }
      navigator.mediaSession.playbackState = this.state.isPlaying ? 'playing' : 'paused';
    } catch {
      // ignore
    }
  }

  savePosition(
    reciterId: string,
    surahNumber: number,
    positionMs: number,
    durationMs: number,
    force = false
  ): void {
    if (!force && positionMs <= 1000) {
      const existing = getSavedPosition(reciterId, surahNumber);
      if (existing > 3000) return;
    }
    setPlayerPrefs({
      [`pos_${reciterId}_${surahNumber}`]: Math.round(positionMs),
      [`dur_${reciterId}_${surahNumber}`]: Math.round(durationMs),
      saved_reciter_id: reciterId,
      last_surah_id: surahNumber,
    });
    void updateSurahLastPosition(surahNumber, Math.round(positionMs));
  }

  setReciter(reciter: Reciter): void {
    setPlayerPrefs({ saved_reciter_id: reciter.id });
    saveCustomReciterSnapshot(reciter);

    const currentSurah = this.state.currentSurah;
    const wasActive = this.state.isPlaying || this.state.isBuffering;
    const savedPos = currentSurah ? getSavedPosition(reciter.id, currentSurah.id) : 0;
    const savedDur = currentSurah ? getSavedDuration(reciter.id, currentSurah.id) : 0;

    this.playbackRequestId++;
    if (this.reconnectTimer !== null) {
      window.clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }

    this.updateState({
      currentReciter: reciter,
      currentPositionMs: savedPos,
      totalDurationMs: savedDur,
      isBuffering: false,
      errorMessage: null,
    });

    if (wasActive && currentSurah) {
      void this.playSurah(currentSurah, { seekToMs: savedPos, autoPlay: true });
    } else {
      this.releaseAudio();
    }
  }

  setRepeatMode(mode: RepeatMode): void {
    setPlayerPrefs({ saved_repeat_mode: mode });
    this.updateState({ repeatMode: mode });
  }

  setPlaybackSpeed(speed: number): void {
    const clamped = Math.min(2.0, Math.max(0.5, speed));
    setPlayerPrefs({ saved_playback_speed: clamped });
    this.updateState({ playbackSpeed: clamped });
    if (this.audio) {
      this.audio.playbackRate = clamped;
    }
  }

  setSleepTimer(option: SleepTimerOption): void {
    this.clearSleepTimerInterval();

    if (option === SleepTimerOption.OFF) {
      setPlayerPrefs({}, ['sleep_timer_option', 'sleep_timer_end_ms']);
      this.updateState({
        sleepTimerOption: SleepTimerOption.OFF,
        sleepTimerSecondsRemaining: 0,
      });
      return;
    }

    if (option === SleepTimerOption.END_OF_SURAH) {
      setPlayerPrefs({ sleep_timer_option: option }, ['sleep_timer_end_ms']);
      this.updateState({
        sleepTimerOption: SleepTimerOption.END_OF_SURAH,
        sleepTimerSecondsRemaining: 0,
      });
      return;
    }

    const secondsMap: Record<SleepTimerOption, number> = {
      [SleepTimerOption.OFF]: 0,
      [SleepTimerOption.END_OF_SURAH]: -1,
      [SleepTimerOption.MINUTES_5]: 5 * 60,
      [SleepTimerOption.MINUTES_10]: 10 * 60,
      [SleepTimerOption.MINUTES_15]: 15 * 60,
      [SleepTimerOption.MINUTES_20]: 20 * 60,
      [SleepTimerOption.MINUTES_30]: 30 * 60,
      [SleepTimerOption.MINUTES_45]: 45 * 60,
      [SleepTimerOption.HOUR_1]: 60 * 60,
      [SleepTimerOption.SECONDS_20_TEST]: 20,
    };

    const deadlineMs = Date.now() + secondsMap[option] * 1000;
    setPlayerPrefs({
      sleep_timer_option: option,
      sleep_timer_end_ms: deadlineMs,
    });
    this.startSleepTimerCountdown(option, deadlineMs);
  }

  private startSleepTimerCountdown(option: SleepTimerOption, deadlineMs: number): void {
    this.clearSleepTimerInterval();
    const initialRemaining = Math.max(0, Math.floor((deadlineMs - Date.now() + 500) / 1000));
    if (initialRemaining <= 0) {
      setPlayerPrefs({}, ['sleep_timer_option', 'sleep_timer_end_ms']);
      this.updateState({
        sleepTimerOption: SleepTimerOption.OFF,
        sleepTimerSecondsRemaining: 0,
      });
      return;
    }

    this.updateState({
      sleepTimerOption: option,
      sleepTimerSecondsRemaining: initialRemaining,
    });

    this.sleepTimerInterval = window.setInterval(() => {
      const remaining = Math.max(0, Math.floor((deadlineMs - Date.now() + 500) / 1000));
      this.updateState({ sleepTimerSecondsRemaining: remaining });
      if (remaining <= 0) {
        this.clearSleepTimerInterval();
        setPlayerPrefs({}, ['sleep_timer_option', 'sleep_timer_end_ms']);
        this.pause();
        this.updateState({
          sleepTimerOption: SleepTimerOption.OFF,
          sleepTimerSecondsRemaining: 0,
        });
      }
    }, 1000);
  }

  private restoreSavedSleepTimer(): void {
    const saved = getSavedSleepTimer();
    if (!saved) return;
    if (saved.option === SleepTimerOption.OFF) {
      setPlayerPrefs({}, ['sleep_timer_option', 'sleep_timer_end_ms']);
    } else if (saved.option === SleepTimerOption.END_OF_SURAH) {
      this.updateState({
        sleepTimerOption: SleepTimerOption.END_OF_SURAH,
        sleepTimerSecondsRemaining: 0,
      });
    } else if (saved.deadlineMs > Date.now()) {
      this.startSleepTimerCountdown(saved.option, saved.deadlineMs);
    } else {
      setPlayerPrefs({}, ['sleep_timer_option', 'sleep_timer_end_ms']);
    }
  }

  private clearSleepTimerInterval(): void {
    if (this.sleepTimerInterval !== null) {
      window.clearInterval(this.sleepTimerInterval);
      this.sleepTimerInterval = null;
    }
  }

  async playSurah(
    surah: Surah,
    options: { seekToMs?: number; startFromBeginning?: boolean; autoPlay?: boolean } = {}
  ): Promise<void> {
    await this.playSurahInternal(
      surah,
      options.seekToMs,
      options.startFromBeginning ?? false,
      options.autoPlay ?? true,
      false
    );
  }

  private async playSurahInternal(
    surah: Surah,
    seekToMs: number | undefined,
    startFromBeginning: boolean,
    autoPlay: boolean,
    isReconnectAttempt: boolean
  ): Promise<void> {
    const reciter = this.state.currentReciter ?? DEFAULT_RECITER;
    const requestId = ++this.playbackRequestId;

    if (this.reconnectTimer !== null) {
      window.clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.clearPrepareTimeout();

    if (!isReconnectAttempt) {
      this.reconnectAttemptCount = 0;
    }

    if (!isSurahAvailable(reciter, surah.id)) {
      this.releaseAudio();
      this.updateState({
        isBuffering: false,
        isPlaying: false,
        errorMessage: `سورة ${surah.name} غير متوفرة بتسجيل ${reciter.name}`,
      });
      return;
    }

    this.releaseAudio();

    const savedPos = getSavedPosition(reciter.id, surah.id);
    const savedDur = getSavedDuration(reciter.id, surah.id);
    const targetSeekMs =
      seekToMs !== undefined
        ? seekToMs
        : startFromBeginning
          ? 0
          : savedDur > 0 && savedPos >= savedDur - 3000
            ? 0
            : savedPos;

    const offlineBlob = await downloadService.getOfflineAudioBlob(reciter.id, surah.id);
    if (requestId !== this.playbackRequestId) return;

    const isOffline = offlineBlob !== null;

    this.updateState({
      currentSurah: surah,
      currentPositionMs: targetSeekMs,
      totalDurationMs: savedDur > 0 ? savedDur : 0,
      isBuffering: autoPlay,
      isPlaying: false,
      isPlayingOffline: isOffline,
      errorMessage: null,
    });

    setPlayerPrefs({ last_surah_id: surah.id });

    if (!isOffline && autoPlay && typeof navigator !== 'undefined' && navigator.onLine === false) {
      this.updateState({
        isBuffering: false,
        isPlaying: false,
        errorMessage:
          'لا يوجد اتصال بالإنترنت لتشغيل البث المباشر. تأكد من الشبكة أو شغّل السور المنزلة.',
      });
      return;
    }

    let sourceUrl: string;
    if (offlineBlob) {
      this.activeObjectUrl = URL.createObjectURL(offlineBlob);
      sourceUrl = this.activeObjectUrl;
    } else {
      sourceUrl = getAudioUrl(reciter, surah.id);
    }

    if (!autoPlay) {
      this.updateState({
        isPlaying: false,
        isBuffering: false,
        currentPositionMs: targetSeekMs,
      });
      return;
    }

    this.startPrepareTimeout(requestId);

    const audio = new Audio();
    audio.preload = 'auto';
    audio.src = sourceUrl;
    this.audio = audio;

    audio.addEventListener('loadedmetadata', () => {
      if (requestId !== this.playbackRequestId || this.audio !== audio) return;
      this.clearPrepareTimeout();
      this.isPrepared = true;

      const durationMs =
        Number.isFinite(audio.duration) && audio.duration > 0
          ? Math.round(audio.duration * 1000)
          : savedDur;

      const canSeek =
        targetSeekMs > 1000 && (durationMs <= 0 || targetSeekMs < durationMs - 2000);
      if (canSeek) {
        audio.currentTime = targetSeekMs / 1000;
      }

      audio.playbackRate = this.state.playbackSpeed;
      this.updateState({
        totalDurationMs: durationMs,
        currentPositionMs: canSeek ? targetSeekMs : 0,
      });

      audio
        .play()
        .then(() => {
          if (requestId !== this.playbackRequestId || this.audio !== audio) return;
          this.updateState({
            isPlaying: true,
            isBuffering: false,
            errorMessage: null,
          });
        })
        .catch(() => {
          if (requestId !== this.playbackRequestId || this.audio !== audio) return;
          this.updateState({
            isPlaying: false,
            isBuffering: false,
            errorMessage: 'تعذر بدء التشغيل الصوتي. اضغط على زر التشغيل للمحاولة.',
          });
        });
    });

    audio.addEventListener('waiting', () => {
      if (requestId !== this.playbackRequestId || this.audio !== audio) return;
      this.updateState({ isBuffering: true });
      this.startPrepareTimeout(requestId);
    });

    audio.addEventListener('playing', () => {
      if (requestId !== this.playbackRequestId || this.audio !== audio) return;
      this.clearPrepareTimeout();
      this.updateState({ isPlaying: true, isBuffering: false, errorMessage: null });
    });

    audio.addEventListener('timeupdate', () => {
      if (requestId !== this.playbackRequestId || this.audio !== audio) return;
      if (!this.isPrepared || audio.paused) return;
      const currentMs = Math.round(audio.currentTime * 1000);
      const durationMs =
        Number.isFinite(audio.duration) && audio.duration > 0
          ? Math.round(audio.duration * 1000)
          : this.state.totalDurationMs;

      if (currentMs > 500) {
        this.updateState({
          currentPositionMs: currentMs,
          totalDurationMs: durationMs,
        });
        this.savePosition(reciter.id, surah.id, currentMs, durationMs);
      }
    });

    audio.addEventListener('ended', () => {
      if (requestId !== this.playbackRequestId || this.audio !== audio) return;
      this.handlePlaybackCompletion();
    });

    audio.addEventListener('error', () => {
      if (requestId !== this.playbackRequestId || this.audio !== audio) return;
      this.clearPrepareTimeout();
      this.isPrepared = false;
      const lastPos = getSavedPosition(reciter.id, surah.id) || this.state.currentPositionMs;
      this.releaseAudio();

      const online = typeof navigator === 'undefined' ? true : navigator.onLine !== false;
      if (!isOffline && this.reconnectAttemptCount < MAX_RECONNECT_ATTEMPTS && online) {
        this.reconnectAttemptCount++;
        this.reconnectTimer = window.setTimeout(() => {
          if (requestId === this.playbackRequestId) {
            void this.playSurahInternal(surah, lastPos, false, true, true);
          }
        }, 500);
        return;
      }

      const msg = !online
        ? 'انقطع الاتصال بالإنترنت. يرجى التحقق من الشبكة أو الاستماع للسور المنزلة.'
        : 'تعذر الاتصال بخادم التلاوة. يرجى التحقق من اتصال الإنترنت أو الاستماع دون إنترنت.';

      this.updateState({
        isBuffering: false,
        isPlaying: false,
        errorMessage: msg,
      });
    });

    audio.load();
  }

  private startPrepareTimeout(requestId: number): void {
    this.clearPrepareTimeout();
    this.prepareTimeoutTimer = window.setTimeout(() => {
      if (requestId === this.playbackRequestId && this.state.isBuffering) {
        this.playbackRequestId++;
        this.releaseAudio();
        this.updateState({
          isBuffering: false,
          isPlaying: false,
          errorMessage: 'انتهت مهلة تحميل التلاوة. يرجى التحقق من سرعة الإنترنت أو المحاولة مجدداً.',
        });
      }
    }, PREPARE_TIMEOUT_MS);
  }

  private clearPrepareTimeout(): void {
    if (this.prepareTimeoutTimer !== null) {
      window.clearTimeout(this.prepareTimeoutTimer);
      this.prepareTimeoutTimer = null;
    }
  }

  togglePlayPause(): void {
    if (this.state.isPlaying || this.state.isBuffering) {
      this.pause();
    } else {
      this.resume();
    }
  }

  resume(): void {
    const current = this.state.currentSurah ?? ALL_SURAHS[0];
    const reciter = this.state.currentReciter ?? DEFAULT_RECITER;
    const savedPos = getSavedPosition(reciter.id, current.id) || this.state.currentPositionMs;

    const timeSincePause = this.lastPausedTimeMs > 0 ? Date.now() - this.lastPausedTimeMs : 0;
    if (this.audio && !this.state.isPlayingOffline && timeSincePause > 20_000) {
      this.releaseAudio();
    }

    if (this.audio && this.isPrepared) {
      this.audio.playbackRate = this.state.playbackSpeed;
      this.audio
        .play()
        .then(() => {
          this.updateState({ isPlaying: true, isBuffering: false, errorMessage: null });
        })
        .catch(() => {
          this.releaseAudio();
          void this.playSurah(current, { seekToMs: savedPos, autoPlay: true });
        });
      return;
    }

    void this.playSurah(current, { seekToMs: savedPos, autoPlay: true });
  }

  pause(): void {
    this.lastPausedTimeMs = Date.now();
    let currentPos = this.state.currentPositionMs;
    let duration = this.state.totalDurationMs;
    const surah = this.state.currentSurah;
    const reciter = this.state.currentReciter ?? DEFAULT_RECITER;

    if (this.audio && this.isPrepared) {
      this.audio.pause();
      const pos = Math.round(this.audio.currentTime * 1000);
      const dur = Number.isFinite(this.audio.duration)
        ? Math.round(this.audio.duration * 1000)
        : 0;
      if (pos > 0) currentPos = pos;
      if (dur > 0) duration = dur;
    } else if (!this.isPrepared) {
      this.playbackRequestId++;
      this.releaseAudio();
    }

    if (surah && currentPos > 0) {
      this.savePosition(reciter.id, surah.id, currentPos, duration);
    }

    this.clearPrepareTimeout();
    this.updateState({
      isPlaying: false,
      isBuffering: false,
      currentPositionMs: currentPos,
      totalDurationMs: duration,
    });
  }

  stop(): void {
    const current = this.state.currentSurah;
    const reciter = this.state.currentReciter ?? DEFAULT_RECITER;
    const currentPos = this.state.currentPositionMs;
    const duration = this.state.totalDurationMs;

    this.playbackRequestId++;
    if (this.reconnectTimer !== null) {
      window.clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (current && currentPos > 0) {
      this.savePosition(reciter.id, current.id, currentPos, duration);
    }
    this.releaseAudio();
    this.updateState({ isPlaying: false, isBuffering: false });
  }

  seekTo(positionMs: number): void {
    const surah = this.state.currentSurah;
    const reciter = this.state.currentReciter ?? DEFAULT_RECITER;
    const currentDur = this.state.totalDurationMs;
    const safePos =
      currentDur > 0 ? Math.min(currentDur, Math.max(0, positionMs)) : Math.max(0, positionMs);

    if (this.audio && this.isPrepared) {
      this.audio.currentTime = safePos / 1000;
    }

    this.updateState({ currentPositionMs: safePos });
    if (surah) {
      this.savePosition(reciter.id, surah.id, safePos, currentDur, true);
    }
  }

  playNextSurah(): void {
    const current = this.state.currentSurah;
    if (!current) return;
    const nextId = current.id < 114 ? current.id + 1 : 1;
    const nextSurah = getSurahById(nextId);
    if (nextSurah) {
      void this.playSurah(nextSurah, { startFromBeginning: true, autoPlay: true });
    }
  }

  playPreviousSurah(): void {
    const current = this.state.currentSurah;
    if (!current) return;
    const prevId = current.id > 1 ? current.id - 1 : 114;
    const prevSurah = getSurahById(prevId);
    if (prevSurah) {
      void this.playSurah(prevSurah, { startFromBeginning: true, autoPlay: true });
    }
  }

  private handlePlaybackCompletion(): void {
    const surah = this.state.currentSurah;
    const reciter = this.state.currentReciter ?? DEFAULT_RECITER;

    if (surah) {
      this.savePosition(reciter.id, surah.id, 0, this.state.totalDurationMs, true);
    }

    if (this.state.sleepTimerOption === SleepTimerOption.END_OF_SURAH) {
      setPlayerPrefs({}, ['sleep_timer_option', 'sleep_timer_end_ms']);
      this.updateState({
        sleepTimerOption: SleepTimerOption.OFF,
        sleepTimerSecondsRemaining: 0,
        currentPositionMs: 0,
      });
      this.stop();
      return;
    }

    switch (this.state.repeatMode) {
      case RepeatMode.NONE:
        this.stop();
        break;
      case RepeatMode.ONE:
        if (surah) {
          void this.playSurah(surah, { startFromBeginning: true, autoPlay: true });
        }
        break;
      case RepeatMode.ALL:
        this.playNextSurah();
        break;
    }
  }

  private releaseAudio(): void {
    this.isPrepared = false;
    this.clearPrepareTimeout();
    if (this.audio) {
      this.audio.pause();
      this.audio.removeAttribute('src');
      this.audio.load();
      this.audio = null;
    }
    if (this.activeObjectUrl) {
      URL.revokeObjectURL(this.activeObjectUrl);
      this.activeObjectUrl = null;
    }
  }
}

export const playerService = new PlayerService();
