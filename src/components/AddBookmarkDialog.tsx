import { useState } from 'react';
import type { Surah } from '../types/quran';
import { formatMs } from '../utils/formatters';

interface AddBookmarkDialogProps {
  surah: Surah;
  positionMs: number;
  onSave: (note: string) => void;
  onDismiss: () => void;
}

export function AddBookmarkDialog({
  surah,
  positionMs,
  onSave,
  onDismiss,
}: AddBookmarkDialogProps) {
  const [note, setNote] = useState('');

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/55 p-4 backdrop-blur-sm"
      onClick={onDismiss}
      role="dialog"
      aria-modal="true"
      aria-label="إضافة إشارة مرجعية"
    >
      <div
        className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-6 shadow-2xl dark:border-emerald-800/50 dark:bg-[#0B2920]"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="text-xl font-bold text-[#0F172A] dark:text-[#ECFDF5]">
          إضافة إشارة مرجعية
        </h2>
        <p className="mt-2 text-sm font-semibold text-[#065F46] dark:text-[#10B981]">
          سورة {surah.name} · الموضع:{' '}
          <span className="font-mono-tabular">{formatMs(positionMs)}</span>
        </p>

        <div className="mt-4">
          <label
            htmlFor="bookmark-note-input"
            className="block text-xs font-medium text-[#475569] dark:text-[#9CA3AF]"
          >
            ملاحظة اختيارية (مثل: موضع الحفظ أو التدبر)
          </label>
          <textarea
            id="bookmark-note-input"
            rows={3}
            value={note}
            onChange={(e) => setNote(e.target.value)}
            placeholder="اكتب ملاحظتك هنا…"
            data-testid="bookmark_note_input"
            className="mt-1.5 w-full rounded-xl border border-slate-300 bg-white px-3.5 py-2.5 text-sm text-[#0F172A] placeholder:text-slate-400 focus:border-[#065F46] focus:outline-none dark:border-emerald-800 dark:bg-[#061A14] dark:text-[#ECFDF5] dark:focus:border-[#10B981]"
          />
        </div>

        <div className="mt-5 flex items-center justify-end gap-2">
          <button
            type="button"
            onClick={onDismiss}
            data-testid="cancel_bookmark_dialog"
            className="min-h-[44px] rounded-xl px-4 py-2 text-sm font-semibold text-[#475569] transition-colors hover:bg-slate-100 dark:text-[#9CA3AF] dark:hover:bg-[#133E31]"
          >
            إلغاء
          </button>
          <button
            type="button"
            onClick={() => {
              onSave(note);
              onDismiss();
            }}
            data-testid="save_bookmark_confirm"
            className="min-h-[44px] rounded-xl bg-[#065F46] px-5 py-2 text-sm font-semibold text-white transition-colors hover:bg-[#064E3B] dark:bg-[#10B981] dark:text-[#061A14] dark:hover:bg-[#34D399]"
          >
            حفظ الإشارة
          </button>
        </div>
      </div>
    </div>
  );
}
