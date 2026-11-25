import { useState } from 'react';
import {
  BookmarkPlus,
  CheckCircle2,
  Download,
  FastForward,
  Gauge,
  Loader2,
  Moon,
  Pause,
  Play,
  Repeat,
  Repeat1,
  Rewind,
  SkipBack,
  SkipForward,
  WifiOff,
  X,
} from 'lucide-react';
import {
  DownloadStatus,
  RepeatMode,
  SleepTimerOption,
  type DownloadItem,
  type PlayerState,
} from '../types/quran';
import { formatMs, formatSpeed } from '../utils/formatters';

interface FullPlayerBottomSheetProps {
  playerState: PlayerState;
  isDownloaded: boolean;
  downloadItem?: DownloadItem;
  onDismiss: () => void;
  onPlayPauseClick: () => void;
  onNextClick: () => void;
  onPrevClick: () => void;
  onSeek: (positionMs: number) => void;
  onToggleRepeat: () => void;
  onCycleSpeed: () => void;
  onOpenSleepTimer: () => void;
  onOpenAddBookmark: () => void;
  onDownloadClick: () => void;
}

export function FullPlayerBottomSheet({
  playerState,
  isDownloaded,
  downloadItem,
  onDismiss,
  onPlayPauseClick,
  onNextClick,
  onPrevClick,
  onSeek,
  onToggleRepeat,
  onCycleSpeed,
  onOpenSleepTimer,
  onOpenAddBookmark,
  onDownloadClick,
}: FullPlayerBottomSheetProps) {
  const surah = playerState.currentSurah;
  const [isDragging, setIsDragging] = useState(false);
  const [dragValue, setDragValue] = useState(0);
  const [bannerLoadError, setBannerLoadError] = useState(false);

  if (!surah) return null;

  const progressRatio = isDragging
    ? dragValue
    : playerState.totalDurationMs > 0
      ? Math.min(1, Math.max(0, playerState.currentPositionMs / playerState.totalDurationMs))
      : 0;

  const displayedCurrentPos = isDragging
    ? Math.max(0, Math.round(dragValue * playerState.totalDurationMs))
    : playerState.currentPositionMs;

  const isDownloading = downloadItem?.status === DownloadStatus.DOWNLOADING;

  const sleepMins = Math.floor(playerState.sleepTimerSecondsRemaining / 60);
  const sleepSecs = playerState.sleepTimerSecondsRemaining % 60;

  return (
    <div
      className="fixed inset-0 z-50 flex items-end justify-center bg-black/55 backdrop-blur-sm md:items-center md:p-4"
      onClick={onDismiss}
      role="dialog"
      aria-modal="true"
      aria-label="مشغل القرآن الكريم"
      data-testid="full_player_sheet"
    >
      <div
        className="w-full max-w-lg rounded-t-3xl border border-slate-200/70 bg-white px-6 pb-7 pt-3 shadow-2xl dark:border-emerald-800/50 dark:bg-[#0B2920] md:rounded-3xl"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Drag handle on mobile */}
        <div className="mx-auto mb-3 h-1.5 w-10 rounded-full bg-slate-300 dark:bg-emerald-800 md:hidden" />

        {/* Header Top Bar */}
        <div className="flex items-center justify-between">
          <button
            type="button"
            onClick={onDismiss}
            aria-label="إغلاق"
            data-testid="close_player_sheet"
            className="flex h-11 w-11 items-center justify-center rounded-full text-[#475569] transition-colors hover:bg-slate-100 dark:text-[#9CA3AF] dark:hover:bg-[#133E31]"
          >
            <X className="h-5 w-5" />
          </button>

          <h2 className="text-base font-bold text-[#0F172A] dark:text-[#ECFDF5]">
            مشغل القرآن الكريم
          </h2>

          <div className="relative">
            <button
              type="button"
              onClick={onOpenSleepTimer}
              aria-label="مؤقت النوم"
              data-testid="full_player_sleep_timer"
              className={`flex h-11 w-11 items-center justify-center rounded-full transition-colors hover:bg-slate-100 dark:hover:bg-[#133E31] ${
                playerState.sleepTimerOption !== SleepTimerOption.OFF
                  ? 'text-[#D97706] dark:text-[#FBBF24]'
                  : 'text-[#475569] dark:text-[#9CA3AF]'
              }`}
            >
              <Moon className="h-5 w-5" />
            </button>
            {playerState.sleepTimerSecondsRemaining > 0 && (
              <span className="font-mono-tabular pointer-events-none absolute -top-0.5 right-0 rounded-full bg-[#D97706] px-1.5 py-0.5 text-[9px] font-bold text-white dark:bg-[#FBBF24] dark:text-black">
                {String(sleepMins).padStart(2, '0')}:{String(sleepSecs).padStart(2, '0')}
              </span>
            )}
          </div>
        </div>

        {/* Decorative Quran Hero Art Card */}
        <div className="relative mt-2.5 h-40 w-full overflow-hidden rounded-2xl border border-[#D97706]/50 bg-gradient-to-br from-[#064E3B] via-[#065F46] to-[#0B2920] shadow-lg">
          {!bannerLoadError && (
            <img
              src="/assets/quran_hero_banner.jpg"
              alt="زخرفة القرآن الكريم"
              referrerPolicy="no-referrer"
              onError={() => setBannerLoadError(true)}
              className="h-full w-full object-cover"
            />
          )}
          <div className="absolute inset-0 bg-gradient-to-t from-[#064E3B]/90 via-[#064E3B]/25 to-transparent" />
          <div className="absolute inset-x-0 bottom-0 flex items-center justify-between px-4 py-3 text-xs font-bold">
            <span className="text-[#FBBF24]">سورة رقم {surah.id}</span>
            <span className="tabular-nums text-white">{surah.versesCount} آية</span>
          </div>
        </div>

        {/* Surah & Reciter Titles */}
        <div className="mt-4 text-center">
          <h3 className="font-quran text-3xl font-extrabold text-[#065F46] dark:text-[#10B981]">
            سورة {surah.name}
          </h3>
          <div className="mt-1 flex items-center justify-center gap-2 text-sm font-medium text-[#475569] dark:text-[#9CA3AF]">
            <span>{playerState.currentReciter?.name ?? 'القارئ'}</span>
            {playerState.isPlayingOffline && (
              <span className="inline-flex items-center gap-1 rounded-md bg-[#10B981]/15 px-2 py-0.5 text-[11px] font-semibold text-[#059669] dark:text-[#10B981]">
                <WifiOff className="h-3 w-3" aria-hidden="true" />
                <span>بدون إنترنت</span>
              </span>
            )}
          </div>
        </div>

        {/* Audio Seeker Slider */}
        <div className="mt-4">
          <input
            type="range"
            min={0}
            max={1}
            step={0.001}
            value={progressRatio}
            dir="ltr"
            aria-label="شريط التقديم والتأخير"
            data-testid="player_seek_slider"
            onChange={(e) => {
              setIsDragging(true);
              setDragValue(Number.parseFloat(e.target.value));
            }}
            onMouseUp={() => {
              if (playerState.totalDurationMs > 0) {
                onSeek(Math.round(dragValue * playerState.totalDurationMs));
              }
              setIsDragging(false);
            }}
            onTouchEnd={() => {
              if (playerState.totalDurationMs > 0) {
                onSeek(Math.round(dragValue * playerState.totalDurationMs));
              }
              setIsDragging(false);
            }}
            onKeyUp={() => {
              if (isDragging && playerState.totalDurationMs > 0) {
                onSeek(Math.round(dragValue * playerState.totalDurationMs));
              }
              setIsDragging(false);
            }}
            style={{
              background: `linear-gradient(to right, #065F46 ${progressRatio * 100}%, rgba(148,163,184,0.35) ${progressRatio * 100}%)`,
            }}
            className="quran-slider w-full"
          />

          <div
            dir="ltr"
            className="font-mono-tabular mt-1.5 flex items-center justify-between text-xs text-[#475569] dark:text-[#9CA3AF]"
          >
            <span>{formatMs(displayedCurrentPos)}</span>
            <span>{formatMs(playerState.totalDurationMs)}</span>
          </div>
        </div>

        {/* Primary Transport Controls in fixed physical LTR layout */}
        <div dir="ltr" className="mt-4 flex items-center justify-evenly">
          <button
            type="button"
            onClick={onPrevClick}
            aria-label="السورة السابقة"
            data-testid="player_prev_surah"
            className="flex h-12 w-12 items-center justify-center rounded-full text-[#0F172A] transition-colors hover:bg-slate-100 dark:text-[#ECFDF5] dark:hover:bg-[#133E31]"
          >
            <SkipBack className="h-7 w-7 fill-current" />
          </button>

          <button
            type="button"
            onClick={() => onSeek(Math.max(0, playerState.currentPositionMs - 10_000))}
            aria-label="التأخير داخل التسجيل 10 ثواني"
            data-testid="player_rewind_10s"
            className="flex h-11 w-11 items-center justify-center rounded-full text-[#475569] transition-colors hover:bg-slate-100 dark:text-[#9CA3AF] dark:hover:bg-[#133E31]"
          >
            <Rewind className="h-6 w-6 fill-current" />
          </button>

          <button
            type="button"
            onClick={onPlayPauseClick}
            aria-label={
              playerState.isBuffering
                ? 'إيقاف التحميل'
                : playerState.isPlaying
                  ? 'إيقاف مؤقت'
                  : 'تشغيل'
            }
            data-testid="player_play_pause_fab"
            className="flex h-16 w-16 items-center justify-center rounded-full bg-[#065F46] text-white shadow-lg transition-transform active:scale-95 dark:bg-[#10B981] dark:text-[#061A14]"
          >
            {playerState.isBuffering ? (
              <Loader2 className="h-7 w-7 animate-spin" />
            ) : playerState.isPlaying ? (
              <Pause className="h-8 w-8 fill-current" />
            ) : (
              <Play className="h-8 w-8 fill-current" />
            )}
          </button>

          <button
            type="button"
            onClick={() => {
              const maxDur =
                playerState.totalDurationMs > 0
                  ? playerState.totalDurationMs
                  : Number.MAX_SAFE_INTEGER;
              onSeek(Math.min(maxDur, playerState.currentPositionMs + 10_000));
            }}
            aria-label="التقديم داخل التسجيل 10 ثواني"
            data-testid="player_forward_10s"
            className="flex h-11 w-11 items-center justify-center rounded-full text-[#475569] transition-colors hover:bg-slate-100 dark:text-[#9CA3AF] dark:hover:bg-[#133E31]"
          >
            <FastForward className="h-6 w-6 fill-current" />
          </button>

          <button
            type="button"
            onClick={onNextClick}
            aria-label="السورة التالية"
            data-testid="player_next_surah"
            className="flex h-12 w-12 items-center justify-center rounded-full text-[#0F172A] transition-colors hover:bg-slate-100 dark:text-[#ECFDF5] dark:hover:bg-[#133E31]"
          >
            <SkipForward className="h-7 w-7 fill-current" />
          </button>
        </div>

        {/* Secondary Action Strip */}
        <div className="mt-3 flex items-center justify-evenly">
          <button
            type="button"
            onClick={onToggleRepeat}
            aria-label="نمط التكرار"
            data-testid="toggle_repeat_mode"
            className={`flex h-11 min-w-[44px] items-center justify-center gap-1.5 rounded-xl px-3 text-xs font-semibold transition-colors hover:bg-slate-100 dark:hover:bg-[#133E31] ${
              playerState.repeatMode === RepeatMode.NONE
                ? 'text-slate-400 dark:text-slate-500'
                : playerState.repeatMode === RepeatMode.ONE
                  ? 'text-[#D97706] dark:text-[#FBBF24]'
                  : 'text-[#065F46] dark:text-[#10B981]'
            }`}
          >
            {playerState.repeatMode === RepeatMode.ONE ? (
              <Repeat1 className="h-5 w-5" />
            ) : (
              <Repeat className="h-5 w-5" />
            )}
            <span>
              {playerState.repeatMode === RepeatMode.ALL
                ? 'متتالي'
                : playerState.repeatMode === RepeatMode.ONE
                  ? 'تكرار'
                  : 'بلا تكرار'}
            </span>
          </button>

          <button
            type="button"
            onClick={onCycleSpeed}
            aria-label="سرعة القراءة"
            data-testid="player_speed_btn"
            className={`flex h-11 min-w-[44px] items-center justify-center gap-1.5 rounded-xl px-3 text-xs font-bold transition-colors hover:bg-slate-100 dark:hover:bg-[#133E31] ${
              playerState.playbackSpeed !== 1.0
                ? 'text-[#D97706] dark:text-[#FBBF24]'
                : 'text-[#0F172A] dark:text-[#ECFDF5]'
            }`}
          >
            <Gauge className="h-4 w-4" />
            <span className="font-mono-tabular">{formatSpeed(playerState.playbackSpeed)}x</span>
          </button>

          <button
            type="button"
            onClick={onOpenAddBookmark}
            aria-label="إضافة إشارة مرجعية"
            data-testid="player_add_bookmark"
            className="flex h-11 min-w-[44px] items-center justify-center gap-1.5 rounded-xl px-3 text-xs font-semibold text-[#065F46] transition-colors hover:bg-emerald-600/10 dark:text-[#10B981]"
          >
            <BookmarkPlus className="h-5 w-5" />
            <span>إشارة</span>
          </button>
        </div>

        {/* Download Status / Trigger Row */}
        <div className="mt-3 flex items-center justify-between rounded-xl bg-[#F0FDF4] px-4 py-2.5 dark:bg-[#133E31]">
          <div className="flex items-center gap-2 text-xs font-medium text-[#0F172A] dark:text-[#ECFDF5]">
            {isDownloaded ? (
              <CheckCircle2 className="h-5 w-5 shrink-0 text-[#059669] dark:text-[#10B981]" />
            ) : isDownloading ? (
              <Loader2 className="h-5 w-5 shrink-0 animate-spin text-[#D97706]" />
            ) : (
              <Download className="h-5 w-5 shrink-0 text-[#D97706] dark:text-[#FBBF24]" />
            )}
            <span>
              {isDownloaded
                ? 'السورة منزلة وجاهزة للاستماع دون إنترنت'
                : isDownloading
                  ? `جاري التنزيل… ${downloadItem?.progress ?? 0}%`
                  : 'تنزيل السورة للاستماع بدون اتصال'}
            </span>
          </div>

          {!isDownloaded && !isDownloading && (
            <button
              type="button"
              onClick={onDownloadClick}
              aria-label="تنزيل"
              data-testid="sheet_download_button"
              className="flex h-10 w-10 items-center justify-center rounded-full text-[#065F46] transition-colors hover:bg-emerald-600/15 dark:text-[#10B981]"
            >
              <Download className="h-5 w-5" />
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
