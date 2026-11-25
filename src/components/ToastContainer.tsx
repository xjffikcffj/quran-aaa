import { AlertCircle, CheckCircle2, Info, X } from 'lucide-react';
import { useQuranApp } from '../context/QuranContext';

export function ToastContainer() {
  const { toasts, dismissToast } = useQuranApp();

  if (toasts.length === 0) return null;

  return (
    <div
      aria-live="polite"
      className="pointer-events-none fixed inset-x-0 top-16 z-50 mx-auto flex max-w-md flex-col gap-2 px-4"
    >
      {toasts.map((toast) => (
        <div
          key={toast.id}
          className={`pointer-events-auto flex items-center gap-2.5 rounded-xl border px-4 py-3 text-sm font-medium shadow-lg backdrop-blur-md ${
            toast.variant === 'error'
              ? 'border-red-200 bg-red-50/95 text-red-900 dark:border-red-800 dark:bg-red-950/95 dark:text-red-100'
              : toast.variant === 'success'
                ? 'border-emerald-200 bg-emerald-50/95 text-emerald-900 dark:border-emerald-700 dark:bg-emerald-950/95 dark:text-emerald-100'
                : 'border-slate-200 bg-white/95 text-slate-900 dark:border-emerald-800 dark:bg-[#0B2920]/95 dark:text-[#ECFDF5]'
          }`}
        >
          {toast.variant === 'error' ? (
            <AlertCircle className="h-4 w-4 shrink-0 text-red-600 dark:text-red-400" />
          ) : toast.variant === 'success' ? (
            <CheckCircle2 className="h-4 w-4 shrink-0 text-emerald-600 dark:text-emerald-400" />
          ) : (
            <Info className="h-4 w-4 shrink-0 text-[#065F46] dark:text-[#10B981]" />
          )}
          <span className="flex-1">{toast.text}</span>
          <button
            type="button"
            onClick={() => dismissToast(toast.id)}
            aria-label="إغلاق التنبيه"
            className="rounded-md p-1 opacity-70 hover:opacity-100"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
      ))}
    </div>
  );
}
