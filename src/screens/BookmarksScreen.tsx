import { Bookmark as BookmarkIcon, Play, Trash2 } from 'lucide-react';
import { useQuranApp } from '../context/QuranContext';
import type { Bookmark } from '../types/quran';
import { formatMs } from '../utils/formatters';

export function BookmarksScreen({ onOpenPlayer }: { onOpenPlayer?: () => void }) {
  const { bookmarks, playFromBookmark, deleteBookmark } = useQuranApp();

  return (
    <div className="mx-auto w-full max-w-5xl px-4 pb-36 pt-3">
      {/* Top Banner */}
      <div className="mb-3.5 flex items-center gap-3 rounded-2xl bg-[#FEF3C7]/60 p-3.5 dark:bg-[#92400E]/25">
        <BookmarkIcon
          className="h-6 w-6 shrink-0 text-[#D97706] dark:text-[#FBBF24]"
          aria-hidden="true"
        />
        <div>
          <h2 className="text-sm font-bold text-[#92400E] dark:text-[#FBBF24]">
            الإشارات المرجعية المحفوظة
          </h2>
          <p className="mt-0.5 text-xs text-[#92400E]/85 dark:text-[#ECFDF5]/80">
            يمكنك حفظ مواضع الاستماع بدقة مع إضافة ملاحظاتك والرجوع إليها مباشرة بنقرة واحدة.
          </p>
        </div>
      </div>

      {bookmarks.length === 0 ? (
        <div className="flex flex-col items-center justify-center px-6 py-20 text-center">
          <BookmarkIcon
            className="h-16 w-16 text-slate-400 dark:text-emerald-700"
            aria-hidden="true"
          />
          <p className="mt-4 text-base font-bold text-[#475569] dark:text-[#9CA3AF]">
            لا توجد إشارات مرجعية محفوظة بعد
          </p>
          <p className="mt-1.5 max-w-md text-xs text-slate-500 dark:text-[#9CA3AF]/75">
            أثناء الاستماع إلى أي سورة، اضغط على زر الإشارة المرجعية لحفظ الموضع الحالي مع ملاحظة
            اختيارية.
          </p>
        </div>
      ) : (
        <div
          data-testid="bookmarks_lazy_column"
          className="grid grid-cols-1 gap-2.5 md:grid-cols-2"
        >
          {bookmarks.map((bookmark) => (
            <BookmarkCard
              key={bookmark.id}
              bookmark={bookmark}
              onPlayClick={() => {
                void playFromBookmark(bookmark);
                onOpenPlayer?.();
              }}
              onDeleteClick={() => void deleteBookmark(bookmark)}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function BookmarkCard({
  bookmark,
  onPlayClick,
  onDeleteClick,
}: {
  bookmark: Bookmark;
  onPlayClick: () => void;
  onDeleteClick: () => void;
}) {
  return (
    <div
      role="button"
      tabIndex={0}
      onClick={onPlayClick}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onPlayClick();
        }
      }}
      data-testid={`bookmark_card_${bookmark.id}`}
      className="flex cursor-pointer items-center gap-3.5 rounded-2xl border border-slate-200/80 bg-white p-3.5 text-right shadow-xs transition-colors hover:border-emerald-600/40 dark:border-emerald-900/50 dark:bg-[#0B2920] dark:hover:border-emerald-500/40"
    >
      {/* Position Badge */}
      <div className="font-mono-tabular flex h-12 w-14 shrink-0 items-center justify-center rounded-xl bg-[#D97706]/15 text-xs font-bold text-[#D97706] dark:bg-[#FBBF24]/15 dark:text-[#FBBF24]">
        {formatMs(bookmark.positionMs)}
      </div>

      {/* Details */}
      <div className="min-w-0 flex-1">
        <h3 className="font-quran truncate text-base font-bold text-[#0F172A] dark:text-[#ECFDF5]">
          سورة {bookmark.surahName}
        </h3>
        <p className="truncate text-xs text-[#475569] dark:text-[#9CA3AF]">
          {bookmark.reciterName} · سورة رقم {bookmark.surahNumber}
        </p>
        {bookmark.note && (
          <p className="mt-1 truncate text-xs font-medium text-[#065F46] dark:text-[#10B981]">
            «{bookmark.note}»
          </p>
        )}
      </div>

      {/* Actions */}
      <div
        className="flex shrink-0 items-center gap-1"
        onClick={(e) => e.stopPropagation()}
        onKeyDown={(e) => e.stopPropagation()}
      >
        <button
          type="button"
          onClick={onPlayClick}
          aria-label="استئناف التلاوة من هذا الموضع"
          data-testid={`play_bookmark_${bookmark.id}`}
          className="flex h-10 w-10 items-center justify-center rounded-full bg-[#065F46] text-white transition-colors hover:bg-[#064E3B] dark:bg-[#10B981] dark:text-[#061A14]"
        >
          <Play className="h-5 w-5 fill-current" />
        </button>

        <button
          type="button"
          onClick={onDeleteClick}
          aria-label="حذف الإشارة"
          data-testid={`delete_bookmark_${bookmark.id}`}
          className="flex h-10 w-10 items-center justify-center rounded-full text-[#DC2626] transition-colors hover:bg-red-500/10 dark:text-red-400"
        >
          <Trash2 className="h-5 w-5" />
        </button>
      </div>
    </div>
  );
}
