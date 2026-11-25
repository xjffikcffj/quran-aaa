import { BadgeCheck, Check, User } from 'lucide-react';
import { useQuranApp } from '../context/QuranContext';
import type { Reciter } from '../types/quran';

export function RecitersScreen() {
  const { reciters, playerState, selectReciter } = useQuranApp();
  const selectedReciter = playerState.currentReciter;

  return (
    <div className="mx-auto w-full max-w-5xl px-4 pb-36 pt-3">
      {/* Top Notice Banner */}
      <div className="mb-3.5 flex items-center gap-3 rounded-2xl bg-[#F0FDF4] p-3.5 dark:bg-[#133E31]/80">
        <BadgeCheck
          className="h-6 w-6 shrink-0 text-[#065F46] dark:text-[#10B981]"
          aria-hidden="true"
        />
        <div>
          <h2 className="text-sm font-bold text-[#064E3B] dark:text-[#ECFDF5]">
            قائمة القراء المعتمدين والموثقين
          </h2>
          <p className="mt-0.5 text-xs text-[#064E3B]/80 dark:text-[#9CA3AF]">
            تم فحص روابط التسجيلات وتوثيق عدد السور المتوفرة لكل قارئ بدقة تامة.
          </p>
        </div>
      </div>

      {/* Reciters List */}
      <div
        data-testid="reciters_lazy_column"
        className="grid grid-cols-1 gap-2.5 md:grid-cols-2"
      >
        {reciters.map((reciter) => {
          const isSelected = selectedReciter?.id === reciter.id;
          return (
            <ReciterCard
              key={reciter.id}
              reciter={reciter}
              isSelected={isSelected}
              onSelect={() => selectReciter(reciter)}
            />
          );
        })}
      </div>
    </div>
  );
}

function ReciterCard({
  reciter,
  isSelected,
  onSelect,
}: {
  reciter: Reciter;
  isSelected: boolean;
  onSelect: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onSelect}
      data-testid={`reciter_card_${reciter.id}`}
      className={`flex w-full items-center gap-3.5 rounded-2xl border p-4 text-right transition-all duration-150 focus-visible:outline-2 focus-visible:outline-emerald-600 ${
        isSelected
          ? 'border-[#065F46] bg-[#F0FDF4] shadow-sm dark:border-[#10B981] dark:bg-[#133E31]/85'
          : 'border-slate-200/80 bg-white hover:border-emerald-600/40 dark:border-emerald-900/50 dark:bg-[#0B2920] dark:hover:border-emerald-500/40'
      }`}
    >
      {/* Avatar */}
      <div
        className={`flex h-12 w-12 shrink-0 items-center justify-center rounded-full ${
          isSelected
            ? 'bg-[#065F46] text-white dark:bg-[#10B981] dark:text-[#061A14]'
            : 'bg-[#F0FDF4] text-[#475569] dark:bg-[#133E31] dark:text-[#9CA3AF]'
        }`}
      >
        <User className="h-6 w-6" aria-hidden="true" />
      </div>

      {/* Details */}
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span
            className={`text-base font-bold ${
              isSelected
                ? 'text-[#065F46] dark:text-[#10B981]'
                : 'text-[#0F172A] dark:text-[#ECFDF5]'
            }`}
          >
            {reciter.name}
          </span>
          {reciter.isCustom && (
            <span className="text-[11px] font-bold text-[#D97706] dark:text-[#FBBF24]">
              · مخصص
            </span>
          )}
        </div>

        <p className="mt-0.5 truncate text-xs text-[#475569] dark:text-[#9CA3AF]">
          {reciter.subInfo || 'رواية حفص عن عاصم'}
        </p>

        <p
          className={`mt-1 text-xs font-semibold ${
            reciter.availableSurahsCount === 114
              ? 'text-[#059669] dark:text-[#10B981]'
              : 'text-[#D97706] dark:text-[#FBBF24]'
          }`}
        >
          {reciter.availableSurahsCount === 114
            ? 'المصحف كاملاً (114 سورة)'
            : `متوفر ${reciter.availableSurahsCount} سورة مسجلة`}
        </p>
      </div>

      {/* Checkmark */}
      {isSelected && (
        <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-[#065F46] text-white dark:bg-[#10B981] dark:text-[#061A14]">
          <Check className="h-4 w-4" aria-label="تم الاختيار" />
        </div>
      )}
    </button>
  );
}
