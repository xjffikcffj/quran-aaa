import { Loader2, Pause, Play, SkipBack, SkipForward, WifiOff } from 'lucide-react';
import { SleepTimerOption, type PlayerState } from '../types/quran';
import { formatMs } from '../utils/formatters';

interface MiniPlayerBarProps {
  playerState: PlayerState;
  onBarClick: () => void;
  onPlayPauseClick: () => void;
  onNextClick: () => void;
  onPrevClick: () => void;
}

export function MiniPlayerBar({
  playerState,
  onBarClick,
  onPlayPauseClick,
  onNextClick,
  onPrevClick,
}: MiniPlayerBarProps) {
  const currentSurah = playerState.currentSurah;
  if (!currentSurah) return null;

  const progressPct =
    playerState.totalDurationMs > 0
      ? Math.min(
          100,
          Math.max(0, (playerState.currentPositionMs / playerState.totalDurationMs) * 100)
        )
      : 0;

  let sleepLabel: string | null = null;
  if (playerState.sleepTimerOption === SleepTimerOption.END_OF_SURAH) {
    sleepLabel = 'نهاية السورة';
  } else if (playerState.sleepTimerSecondsRemaining > 0) {
    const mins = Math.floor(playerState.sleepTimerSecondsRemaining / 60);
    const secs = playerState.sleepTimerSecondsRemaining % 60;
    sleepLabel = `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}`;
  }

  return (
    <div className="mx-auto w-full max-w-5xl px-2.5 pb-1">
      <div
        role="button"
        tabIndex={0}
        onClick={onBarClick}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            onBarClick();
          }
        }}
        data-testid="mini_player_bar"
        className="overflow-hidden rounded-xl border border-[#065F46]/25 bg-[#F0FDF4]/95 shadow-md backdrop-blur-md transition-colors hover:bg-[#F0FDF4] dark:border-[#10B981]/30 dark:bg-[#133E31]/95 dark:hover:bg-[#133E31]"
      >
        {/* Top Progress Bar */}
        <div className="h-[3px] w-full bg-slate-200/70 dark:bg-emerald-950">
          <div
            className="h-full bg-[#D97706] transition-all duration-300 dark:bg-[#FBBF24]"
            style={{ width: `${progressPct}%` }}
          />
        </div>

        <div className="flex items-center gap-2.5 px-3 py-1.5">
          {/* Surah Number Badge */}
          <div className="font-mono-tabular flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-[#065F46] text-xs font-bold text-white dark:bg-[#10B981] dark:text-[#061A14]">
            {currentSurah.id}
          </div>

          {/* Title, Reciter, Time & Sleep Timer */}
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-1.5">
              <span className="font-quran truncate text-sm font-bold text-[#0F172A] dark:text-[#ECFDF5]">
                سورة {currentSurah.name}
              </span>
              {playerState.isPlayingOffline && (
                <span
                  title="تشغيل دون إنترنت"
                  className="inline-flex shrink-0 items-center text-[#059669] dark:text-[#10B981]"
                >
                  <WifiOff className="h-3.5 w-3.5" aria-hidden="true" />
                </span>
              )}
              {sleepLabel && (
                <span
                  data-testid="mini_player_sleep_timer"
                  className="font-mono-tabular shrink-0 rounded-md bg-[#D97706] px-1.5 py-0.5 text-[10px] font-bold text-white dark:bg-[#FBBF24] dark:text-black"
                >
                  {sleepLabel}
                </span>
              )}
            </div>

            <div className="flex items-center gap-1.5 text-[11px] text-[#475569] dark:text-[#9CA3AF]">
              <span className="truncate">{playerState.currentReciter?.name ?? 'القارئ'}</span>
              {playerState.totalDurationMs > 0 && (
                <>
                  <span aria-hidden="true">·</span>
                  <span className="font-mono-tabular shrink-0">
                    {formatMs(playerState.currentPositionMs)} / {formatMs(playerState.totalDurationMs)}
                  </span>
                </>
              )}
            </div>
          </div>

          {/* 3 transport controls in fixed LTR layout matching MiniPlayerBar.kt */}
          <div
            dir="ltr"
            className="flex shrink-0 items-center gap-1"
            onClick={(e) => e.stopPropagation()}
            onKeyDown={(e) => e.stopPropagation()}
          >
            <button
              type="button"
              onClick={onPrevClick}
              aria-label="السورة السابقة"
              data-testid="mini_player_prev"
              className="flex h-10 w-10 items-center justify-center rounded-full text-[#475569] transition-colors hover:bg-black/5 dark:text-[#9CA3AF] dark:hover:bg-white/10"
            >
              <SkipBack className="h-4 w-4 fill-current" />
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
              data-testid="mini_player_toggle"
              className="flex h-10 w-10 items-center justify-center rounded-full bg-[#065F46] text-white shadow-sm transition-transform active:scale-95 dark:bg-[#10B981] dark:text-[#061A14]"
            >
              {playerState.isBuffering ? (
                <Loader2 className="h-5 w-5 animate-spin" />
              ) : playerState.isPlaying ? (
                <Pause className="h-5 w-5 fill-current" />
              ) : (
                <Play className="h-5 w-5 fill-current" />
              )}
            </button>

            <button
              type="button"
              onClick={onNextClick}
              aria-label="السورة التالية"
              data-testid="mini_player_next"
              className="flex h-10 w-10 items-center justify-center rounded-full text-[#475569] transition-colors hover:bg-black/5 dark:text-[#9CA3AF] dark:hover:bg-white/10"
            >
              <SkipForward className="h-4 w-4 fill-current" />
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
