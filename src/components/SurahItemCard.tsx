import { BookmarkPlus, CheckCircle2, Download, Heart, Loader2, Pause, Play } from 'lucide-react';
import { DownloadStatus, type DownloadItem, type Surah } from '../types/quran';

interface SurahItemCardProps {
  surah: Surah;
  isPlaying: boolean;
  isCurrent: boolean;
  isFavorite: boolean;
  isDownloaded: boolean;
  downloadItem?: DownloadItem;
  isAvailableForReciter: boolean;
  onCardClick: () => void;
  onPlayClick: () => void;
  onFavoriteClick: () => void;
  onDownloadClick: () => void;
  onBookmarkClick: () => void;
}

export function SurahItemCard({
  surah,
  isPlaying,
  isCurrent,
  isFavorite,
  isDownloaded,
  downloadItem,
  isAvailableForReciter,
  onCardClick,
  onPlayClick,
  onFavoriteClick,
  onDownloadClick,
  onBookmarkClick,
}: SurahItemCardProps) {
  const isDownloading = downloadItem?.status === DownloadStatus.DOWNLOADING;

  return (
    <div
      role="button"
      tabIndex={0}
      onClick={onCardClick}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onCardClick();
        }
      }}
      data-testid={`surah_card_${surah.id}`}
      className={`group flex w-full cursor-pointer items-center gap-3.5 rounded-2xl border px-3.5 py-3 text-right transition-all duration-150 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600 ${
        isCurrent
          ? 'border-[#065F46] bg-[#F0FDF4] shadow-sm dark:border-[#10B981] dark:bg-[#133E31]/80'
          : 'border-slate-200/80 bg-white hover:border-emerald-600/40 hover:bg-[#F0FDF4]/50 dark:border-emerald-900/50 dark:bg-[#0B2920] dark:hover:border-emerald-500/40 dark:hover:bg-[#133E31]/50'
      }`}
    >
      {/* Surah Number Badge */}
      <div
        className={`font-mono-tabular flex h-11 w-11 shrink-0 items-center justify-center rounded-xl border text-base font-bold transition-colors ${
          isCurrent
            ? 'border-[#D97706] bg-[#065F46] text-white dark:border-[#FBBF24] dark:bg-[#10B981] dark:text-[#061A14]'
            : 'border-slate-200 bg-[#F0FDF4] text-[#475569] dark:border-emerald-800/60 dark:bg-[#133E31] dark:text-[#9CA3AF]'
        }`}
      >
        {surah.id}
      </div>

      {/* Surah Details */}
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-1.5">
          <h3
            className={`font-quran truncate text-[18px] font-bold leading-snug ${
              isCurrent
                ? 'text-[#065F46] dark:text-[#10B981]'
                : 'text-[#0F172A] dark:text-[#ECFDF5]'
            }`}
          >
            سورة {surah.name}
          </h3>
          {!isAvailableForReciter && (
            <span className="text-[11px] font-medium text-[#DC2626] dark:text-red-400">
              (غير مسجلة للقارئ)
            </span>
          )}
        </div>

        <div className="mt-0.5 flex flex-wrap items-center gap-1.5 text-xs text-[#475569] dark:text-[#9CA3AF]">
          <span className="tabular-nums">{surah.versesCount} آية</span>
          <span aria-hidden="true">·</span>
          <span className="truncate text-[11px] opacity-80">{surah.englishName}</span>
          {isDownloaded && (
            <>
              <span aria-hidden="true">·</span>
              <span className="inline-flex items-center gap-1 text-[11px] font-semibold text-[#059669] dark:text-[#10B981]">
                <CheckCircle2 className="h-3.5 w-3.5" aria-hidden="true" />
                <span>منزلة</span>
              </span>
            </>
          )}
        </div>
      </div>

      {/* Quick Actions */}
      <div
        className="flex shrink-0 items-center gap-0.5"
        onClick={(e) => e.stopPropagation()}
        onKeyDown={(e) => e.stopPropagation()}
      >
        <button
          type="button"
          onClick={onFavoriteClick}
          aria-label={isFavorite ? 'إزالة من المفضلة' : 'إضافة إلى المفضلة'}
          data-testid={`fav_button_${surah.id}`}
          className="flex min-h-[42px] min-w-[40px] items-center justify-center rounded-full transition-colors hover:bg-rose-500/10 focus-visible:outline-2 focus-visible:outline-emerald-600"
        >
          <Heart
            className={`h-5 w-5 transition-colors ${
              isFavorite
                ? 'fill-[#E11D48] text-[#E11D48]'
                : 'text-[#475569]/65 dark:text-[#9CA3AF]/70'
            }`}
          />
        </button>

        <button
          type="button"
          onClick={onBookmarkClick}
          aria-label="إضافة إشارة مرجعية"
          data-testid={`bookmark_button_${surah.id}`}
          className="flex min-h-[42px] min-w-[40px] items-center justify-center rounded-full text-[#475569]/75 transition-colors hover:bg-emerald-600/10 hover:text-[#065F46] focus-visible:outline-2 focus-visible:outline-emerald-600 dark:text-[#9CA3AF]/80 dark:hover:text-[#10B981]"
        >
          <BookmarkPlus className="h-5 w-5" />
        </button>

        {!isDownloaded && isAvailableForReciter && (
          <button
            type="button"
            onClick={onDownloadClick}
            disabled={isDownloading}
            aria-label="تنزيل السورة"
            data-testid={`download_button_${surah.id}`}
            className="flex min-h-[42px] min-w-[40px] items-center justify-center rounded-full text-[#D97706] transition-colors hover:bg-amber-500/10 disabled:opacity-70 focus-visible:outline-2 focus-visible:outline-emerald-600 dark:text-[#FBBF24]"
          >
            {isDownloading ? (
              <Loader2 className="h-5 w-5 animate-spin" />
            ) : (
              <Download className="h-5 w-5" />
            )}
          </button>
        )}

        <button
          type="button"
          onClick={onPlayClick}
          disabled={!isAvailableForReciter}
          aria-label={isCurrent && isPlaying ? 'إيقاف مؤقت' : 'تشغيل'}
          data-testid={`play_button_${surah.id}`}
          className={`flex h-11 w-11 items-center justify-center rounded-full transition-all disabled:cursor-not-allowed disabled:opacity-40 focus-visible:outline-2 focus-visible:outline-emerald-600 ${
            isCurrent && isPlaying
              ? 'bg-[#065F46] text-white shadow-sm dark:bg-[#10B981] dark:text-[#061A14]'
              : 'bg-[#F0FDF4] text-[#065F46] hover:bg-[#065F46]/15 dark:bg-[#133E31] dark:text-[#10B981] dark:hover:bg-[#10B981]/20'
          }`}
        >
          {isCurrent && isPlaying ? (
            <Pause className="h-5 w-5 fill-current" />
          ) : (
            <Play className="h-5 w-5 fill-current" />
          )}
        </button>
      </div>
    </div>
  );
}
