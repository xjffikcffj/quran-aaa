import {
  AlertCircle,
  DownloadCloud,
  FileCheck2,
  FolderX,
  Loader2,
  Play,
  RotateCcw,
  Trash2,
  X,
} from 'lucide-react';
import { useQuranApp } from '../context/QuranContext';
import { DEFAULT_RECITERS } from '../data/defaultReciters';
import { getSurahById } from '../data/quranData';
import { DownloadStatus, type DownloadItem } from '../types/quran';
import { formatBytesMb } from '../utils/formatters';

export function DownloadsScreen({ onOpenPlayer }: { onOpenPlayer?: () => void }) {
  const {
    downloads,
    reciters,
    selectReciter,
    playSurah,
    cancelDownload,
    deleteDownload,
    startDownload,
  } = useQuranApp();

  return (
    <div className="mx-auto w-full max-w-5xl px-4 pb-36 pt-3">
      {/* Info Banner */}
      <div className="mb-3.5 flex items-center gap-3 rounded-2xl bg-[#F0FDF4] p-3.5 dark:bg-[#133E31]/80">
        <FileCheck2
          className="h-6 w-6 shrink-0 text-[#065F46] dark:text-[#10B981]"
          aria-hidden="true"
        />
        <div>
          <h2 className="text-sm font-bold text-[#064E3B] dark:text-[#ECFDF5]">
            التنزيلات والاستماع دون اتصال
          </h2>
          <p className="mt-0.5 text-xs text-[#064E3B]/80 dark:text-[#9CA3AF]">
            تعرض هذه الصفحة السور المنزلة أو الجاري تنزيلها فقط، ويمكن تشغيلها دون إنترنت.
          </p>
        </div>
      </div>

      {downloads.length === 0 ? (
        <div className="flex flex-col items-center justify-center px-6 py-20 text-center">
          <FolderX className="h-16 w-16 text-slate-400 dark:text-emerald-700" aria-hidden="true" />
          <p className="mt-4 text-base font-bold text-[#475569] dark:text-[#9CA3AF]">
            لم تقم بتنزيل أي سور بعد
          </p>
          <p className="mt-1.5 max-w-md text-xs text-slate-500 dark:text-[#9CA3AF]/75">
            اضغط على زر التنزيل بجانب أي سورة لحفظها والاستماع إليها في أي وقت دون اتصال بالإنترنت.
          </p>
        </div>
      ) : (
        <div
          data-testid="downloads_lazy_column"
          className="grid grid-cols-1 gap-2.5 md:grid-cols-2"
        >
          {downloads.map((item) => {
            const itemReciter =
              reciters.find((r) => r.id === item.reciterId) ??
              DEFAULT_RECITERS.find((r) => r.id === item.reciterId);

            return (
              <DownloadCard
                key={item.id}
                item={item}
                onPlayClick={() => {
                  const surah = getSurahById(item.surahNumber);
                  if (!surah) return;
                  const resolvedReciter = itemReciter ?? {
                    id: item.reciterId,
                    name: item.reciterName,
                    serverUrl: '',
                    availableSurahsCount: 114,
                    isCustom: true,
                  };
                  selectReciter(resolvedReciter);
                  void playSurah(surah);
                  onOpenPlayer?.();
                }}
                onCancelClick={() => void cancelDownload(item.id)}
                onDeleteClick={() => void deleteDownload(item.id)}
                onRetryClick={() => {
                  const surah = getSurahById(item.surahNumber);
                  if (surah && itemReciter) {
                    void startDownload(surah, itemReciter);
                  }
                }}
              />
            );
          })}
        </div>
      )}
    </div>
  );
}

function DownloadCard({
  item,
  onPlayClick,
  onCancelClick,
  onDeleteClick,
  onRetryClick,
}: {
  item: DownloadItem;
  onPlayClick: () => void;
  onCancelClick: () => void;
  onDeleteClick: () => void;
  onRetryClick: () => void;
}) {
  return (
    <div
      data-testid={`download_card_${item.id}`}
      className="rounded-2xl border border-slate-200/80 bg-white p-3.5 shadow-xs dark:border-emerald-900/50 dark:bg-[#0B2920]"
    >
      <div className="flex items-center gap-3">
        {/* Status Icon */}
        <div
          className={`flex h-11 w-11 shrink-0 items-center justify-center rounded-full ${
            item.status === DownloadStatus.COMPLETED
              ? 'bg-[#10B981]/15 text-[#059669] dark:text-[#10B981]'
              : item.status === DownloadStatus.DOWNLOADING
                ? 'bg-[#D97706]/15 text-[#D97706] dark:text-[#FBBF24]'
                : item.status === DownloadStatus.FAILED
                  ? 'bg-red-500/15 text-[#DC2626] dark:text-red-400'
                  : 'bg-slate-100 text-slate-500 dark:bg-[#133E31]'
          }`}
        >
          {item.status === DownloadStatus.COMPLETED ? (
            <FileCheck2 className="h-5 w-5" />
          ) : item.status === DownloadStatus.DOWNLOADING ? (
            <Loader2 className="h-5 w-5 animate-spin" />
          ) : item.status === DownloadStatus.FAILED ? (
            <AlertCircle className="h-5 w-5" />
          ) : (
            <DownloadCloud className="h-5 w-5" />
          )}
        </div>

        {/* Info */}
        <div className="min-w-0 flex-1">
          <h3 className="font-quran truncate text-base font-bold text-[#0F172A] dark:text-[#ECFDF5]">
            سورة {item.surahName}
          </h3>
          <p className="truncate text-xs text-[#475569] dark:text-[#9CA3AF]">
            {item.reciterName} · سورة رقم {item.surahNumber}
          </p>
        </div>

        {/* Actions */}
        <div className="flex shrink-0 items-center gap-1">
          {item.status === DownloadStatus.COMPLETED && (
            <>
              <button
                type="button"
                onClick={onPlayClick}
                aria-label="تشغيل دون إنترنت"
                data-testid={`play_download_${item.id}`}
                className="flex h-10 w-10 items-center justify-center rounded-full bg-[#065F46] text-white transition-colors hover:bg-[#064E3B] dark:bg-[#10B981] dark:text-[#061A14]"
              >
                <Play className="h-5 w-5 fill-current" />
              </button>
              <button
                type="button"
                onClick={onDeleteClick}
                aria-label="حذف الملف المنزّل"
                data-testid={`delete_download_${item.id}`}
                className="flex h-10 w-10 items-center justify-center rounded-full text-[#DC2626] transition-colors hover:bg-red-500/10 dark:text-red-400"
              >
                <Trash2 className="h-5 w-5" />
              </button>
            </>
          )}

          {item.status === DownloadStatus.DOWNLOADING && (
            <button
              type="button"
              onClick={onCancelClick}
              aria-label="إلغاء التنزيل"
              data-testid={`cancel_download_${item.id}`}
              className="flex h-10 w-10 items-center justify-center rounded-full text-[#DC2626] transition-colors hover:bg-red-500/10 dark:text-red-400"
            >
              <X className="h-5 w-5" />
            </button>
          )}

          {item.status === DownloadStatus.FAILED && (
            <>
              <button
                type="button"
                onClick={onRetryClick}
                aria-label="إعادة المحاولة"
                data-testid={`retry_download_${item.id}`}
                className="flex h-10 w-10 items-center justify-center rounded-full text-[#065F46] transition-colors hover:bg-emerald-600/10 dark:text-[#10B981]"
              >
                <RotateCcw className="h-5 w-5" />
              </button>
              <button
                type="button"
                onClick={onDeleteClick}
                aria-label="حذف"
                className="flex h-10 w-10 items-center justify-center rounded-full text-slate-500 transition-colors hover:bg-slate-100 dark:text-[#9CA3AF] dark:hover:bg-[#133E31]"
              >
                <Trash2 className="h-5 w-5" />
              </button>
            </>
          )}
        </div>
      </div>

      {/* Footer / Progress */}
      <div className="mt-2.5">
        {item.status === DownloadStatus.DOWNLOADING && (
          <div>
            <div className="h-1.5 w-full overflow-hidden rounded-full bg-slate-200 dark:bg-emerald-950">
              <div
                className="h-full bg-[#D97706] transition-all duration-200 dark:bg-[#FBBF24]"
                style={{ width: `${item.progress}%` }}
              />
            </div>
            <div className="mt-1.5 flex items-center justify-between text-[11px]">
              <span className="font-semibold text-[#D97706] dark:text-[#FBBF24]">
                جاري التنزيل… <span className="font-mono-tabular">{item.progress}%</span>
              </span>
              {item.fileSizeBytes > 0 && (
                <span className="font-mono-tabular text-[#475569] dark:text-[#9CA3AF]">
                  {formatBytesMb(item.fileSizeBytes, 1)}
                </span>
              )}
            </div>
          </div>
        )}

        {item.status === DownloadStatus.COMPLETED && (
          <div className="flex items-center justify-between text-[11px]">
            <span className="font-semibold text-[#059669] dark:text-[#10B981]">
              جاهزة للاستماع دون اتصال بالإنترنت
            </span>
            {item.fileSizeBytes > 0 && (
              <span className="font-mono-tabular text-[#475569] dark:text-[#9CA3AF]">
                {formatBytesMb(item.fileSizeBytes, 2)}
              </span>
            )}
          </div>
        )}

        {item.status === DownloadStatus.FAILED && (
          <p className="text-[11px] font-medium text-[#DC2626] dark:text-red-400">
            {item.errorMessage ?? 'حدث خطأ أثناء التنزيل، تحقق من الاتصال بالشبكة'}
          </p>
        )}
      </div>
    </div>
  );
}
