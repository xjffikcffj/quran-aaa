import { useState } from 'react';
import { isValidHttpsServerUrl } from '../data/defaultReciters';

interface AddCustomReciterDialogProps {
  onAdd: (name: string, serverUrl: string, count: number) => Promise<void> | void;
  onDismiss: () => void;
}

export function AddCustomReciterDialog({ onAdd, onDismiss }: AddCustomReciterDialogProps) {
  const [name, setName] = useState('');
  const [serverUrl, setServerUrl] = useState('https://serverX.mp3quran.net/slug/');
  const [countText, setCountText] = useState('114');
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const cleanName = name.trim();
    const cleanUrl = serverUrl.trim();
    if (!cleanName || !cleanUrl) {
      setErrorMsg('يرجى تعبئة جميع الحقول المطلوبة');
      return;
    }
    if (!isValidHttpsServerUrl(cleanUrl)) {
      setErrorMsg('يرجى إدخال رابط خادم صحيح يبدأ بـ https:// لضمان الاتصال الآمن');
      return;
    }
    const parsed = Number.parseInt(countText, 10);
    const count = Number.isNaN(parsed) ? 114 : Math.min(114, Math.max(1, parsed));
    await onAdd(cleanName, cleanUrl, count);
    onDismiss();
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/55 p-4 backdrop-blur-sm"
      onClick={onDismiss}
      role="dialog"
      aria-modal="true"
      aria-label="إضافة قارئ مخصص"
    >
      <form
        onSubmit={handleSubmit}
        className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-6 shadow-2xl dark:border-emerald-800/50 dark:bg-[#0B2920]"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="text-xl font-bold text-[#0F172A] dark:text-[#ECFDF5]">
          إضافة قارئ مخصص
        </h2>
        <p className="mt-1.5 text-xs text-[#475569] dark:text-[#9CA3AF]">
          أدخل بيانات القارئ ورابط خادم الصوت (ينتهي بـ / ويقبل 001.mp3 إلى 114.mp3):
        </p>

        <div className="mt-4 space-y-3">
          <div>
            <label
              htmlFor="custom-reciter-name"
              className="block text-xs font-medium text-[#475569] dark:text-[#9CA3AF]"
            >
              اسم القارئ
            </label>
            <input
              id="custom-reciter-name"
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="مثال: الشيخ ماهر المعيقلي"
              data-testid="custom_reciter_name"
              className="mt-1 w-full rounded-xl border border-slate-300 bg-white px-3.5 py-2.5 text-sm text-[#0F172A] focus:border-[#065F46] focus:outline-none dark:border-emerald-800 dark:bg-[#061A14] dark:text-[#ECFDF5]"
            />
          </div>

          <div>
            <label
              htmlFor="custom-reciter-url"
              className="block text-xs font-medium text-[#475569] dark:text-[#9CA3AF]"
            >
              رابط الخادم المباشر (Base Audio URL)
            </label>
            <input
              id="custom-reciter-url"
              type="url"
              dir="ltr"
              value={serverUrl}
              onChange={(e) => setServerUrl(e.target.value)}
              placeholder="https://example.com/audio/"
              data-testid="custom_reciter_url"
              className="mt-1 w-full rounded-xl border border-slate-300 bg-white px-3.5 py-2.5 text-left text-sm text-[#0F172A] focus:border-[#065F46] focus:outline-none dark:border-emerald-800 dark:bg-[#061A14] dark:text-[#ECFDF5]"
            />
          </div>

          <div>
            <label
              htmlFor="custom-reciter-count"
              className="block text-xs font-medium text-[#475569] dark:text-[#9CA3AF]"
            >
              عدد السور المتوفرة (1 - 114)
            </label>
            <input
              id="custom-reciter-count"
              type="number"
              min={1}
              max={114}
              value={countText}
              onChange={(e) => setCountText(e.target.value)}
              data-testid="custom_reciter_count"
              className="font-mono-tabular mt-1 w-full rounded-xl border border-slate-300 bg-white px-3.5 py-2.5 text-sm text-[#0F172A] focus:border-[#065F46] focus:outline-none dark:border-emerald-800 dark:bg-[#061A14] dark:text-[#ECFDF5]"
            />
          </div>

          {errorMsg && (
            <p className="text-xs font-semibold text-[#DC2626] dark:text-red-400">{errorMsg}</p>
          )}
        </div>

        <div className="mt-5 flex items-center justify-end gap-2">
          <button
            type="button"
            onClick={onDismiss}
            data-testid="cancel_custom_reciter"
            className="min-h-[44px] rounded-xl px-4 py-2 text-sm font-semibold text-[#475569] transition-colors hover:bg-slate-100 dark:text-[#9CA3AF] dark:hover:bg-[#133E31]"
          >
            إلغاء
          </button>
          <button
            type="submit"
            data-testid="save_custom_reciter"
            className="min-h-[44px] rounded-xl bg-[#065F46] px-5 py-2 text-sm font-semibold text-white transition-colors hover:bg-[#064E3B] dark:bg-[#10B981] dark:text-[#061A14] dark:hover:bg-[#34D399]"
          >
            إضافة وحفظ
          </button>
        </div>
      </form>
    </div>
  );
}
