import { SLEEP_TIMER_OPTIONS, SleepTimerOption } from '../types/quran';

interface SleepTimerDialogProps {
  currentOption: SleepTimerOption;
  onOptionSelected: (option: SleepTimerOption) => void;
  onDismiss: () => void;
}

const ORDERED_OPTIONS: SleepTimerOption[] = [
  SleepTimerOption.END_OF_SURAH,
  SleepTimerOption.MINUTES_5,
  SleepTimerOption.MINUTES_10,
  SleepTimerOption.MINUTES_15,
  SleepTimerOption.MINUTES_20,
  SleepTimerOption.MINUTES_30,
  SleepTimerOption.MINUTES_45,
  SleepTimerOption.HOUR_1,
  ...(import.meta.env.DEV ? [SleepTimerOption.SECONDS_20_TEST] : []),
  SleepTimerOption.OFF,
];

export function SleepTimerDialog({
  currentOption,
  onOptionSelected,
  onDismiss,
}: SleepTimerDialogProps) {
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/55 p-4 backdrop-blur-sm"
      onClick={onDismiss}
      role="dialog"
      aria-modal="true"
      aria-label="مؤقت النوم"
    >
      <div
        className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-6 shadow-2xl dark:border-emerald-800/50 dark:bg-[#0B2920]"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="text-xl font-bold text-[#0F172A] dark:text-[#ECFDF5]">مؤقت النوم</h2>
        <p className="mt-1.5 text-sm text-[#475569] dark:text-[#9CA3AF]">
          حدد متى ترغب في إيقاف التلاوة تلقائياً لمساعدتك على الاسترخاء والنوم:
        </p>

        <div className="mt-4 max-h-80 space-y-1 overflow-y-auto">
          {ORDERED_OPTIONS.map((opt) => {
            const meta = SLEEP_TIMER_OPTIONS[opt];
            const isSelected = currentOption === opt;
            return (
              <button
                key={opt}
                type="button"
                onClick={() => {
                  onOptionSelected(opt);
                  onDismiss();
                }}
                data-testid={`sleep_timer_${opt}`}
                className={`flex min-h-[44px] w-full items-center gap-3 rounded-xl px-3 py-2 text-right transition-colors ${
                  isSelected
                    ? 'bg-[#F0FDF4] font-bold text-[#065F46] dark:bg-[#133E31] dark:text-[#10B981]'
                    : 'text-[#0F172A] hover:bg-slate-100 dark:text-[#ECFDF5] dark:hover:bg-[#133E31]/60'
                }`}
              >
                <span
                  className={`flex h-5 w-5 shrink-0 items-center justify-center rounded-full border-2 ${
                    isSelected
                      ? 'border-[#065F46] dark:border-[#10B981]'
                      : 'border-slate-400 dark:border-slate-500'
                  }`}
                >
                  {isSelected && (
                    <span className="h-2.5 w-2.5 rounded-full bg-[#065F46] dark:bg-[#10B981]" />
                  )}
                </span>
                <span className="text-sm">{meta.label}</span>
              </button>
            );
          })}
        </div>

        <div className="mt-5 flex justify-end">
          <button
            type="button"
            onClick={onDismiss}
            data-testid="close_sleep_dialog"
            className="min-h-[44px] rounded-xl px-5 py-2 text-sm font-semibold text-[#065F46] transition-colors hover:bg-emerald-600/10 dark:text-[#10B981]"
          >
            إغلاق
          </button>
        </div>
      </div>
    </div>
  );
}
