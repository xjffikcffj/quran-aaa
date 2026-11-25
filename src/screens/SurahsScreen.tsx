import { Heart, Search, SearchX, X } from 'lucide-react';
import { SurahItemCard } from '../components/SurahItemCard';
import { useQuranApp } from '../context/QuranContext';
import { isSurahAvailable } from '../data/defaultReciters';
import type { Surah } from '../types/quran';

interface SurahsScreenProps {
  onSurahSelected: (surah: Surah) => void;
  onOpenAddBookmark: (surah: Surah) => void;
}

export function SurahsScreen({ onSurahSelected, onOpenAddBookmark }: SurahsScreenProps) {
  const {
    displayedSurahs,
    searchQuery,
    setSearchQuery,
    showFavoritesOnly,
    setShowFavoritesOnly,
    favoriteSurahNumbers,
    playerState,
    togglePlayPause,
    toggleFavorite,
    startDownload,
    isSurahDownloaded,
    getSurahDownloadItem,
  } = useQuranApp();

  const currentReciter = playerState.currentReciter;

  return (
    <div className="mx-auto w-full max-w-5xl px-4 pb-36 pt-3">
      {/* Search Bar & Filter Controls */}
      <div className="space-y-2.5 pb-3">
        <div className="relative flex items-center">
          <Search
            className="pointer-events-none absolute right-3.5 h-5 w-5 text-[#065F46] dark:text-[#10B981]"
            aria-hidden="true"
          />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="ابحث باسم السورة أو رقمها (مثال: الكهف أو ١٨)…"
            data-testid="surah_search_input"
            className="h-12 w-full rounded-2xl border border-slate-300/80 bg-white pl-11 pr-11 text-sm text-[#0F172A] placeholder:text-slate-400 focus:border-[#065F46] focus:outline-none dark:border-emerald-800/60 dark:bg-[#0B2920] dark:text-[#ECFDF5] dark:placeholder:text-[#9CA3AF]/70 dark:focus:border-[#10B981]"
          />
          {searchQuery.length > 0 && (
            <button
              type="button"
              onClick={() => setSearchQuery('')}
              aria-label="مسح البحث"
              data-testid="clear_search_button"
              className="absolute left-2 flex h-9 w-9 items-center justify-center rounded-full text-slate-500 transition-colors hover:bg-slate-100 dark:text-[#9CA3AF] dark:hover:bg-[#133E31]"
            >
              <X className="h-4 w-4" />
            </button>
          )}
        </div>

        {/* Filter Buttons */}
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={() => setShowFavoritesOnly(false)}
            data-testid="filter_all_surahs"
            className={`min-h-[40px] shrink-0 whitespace-nowrap rounded-xl border px-4 py-1.5 text-xs font-semibold transition-colors ${
              !showFavoritesOnly
                ? 'border-[#065F46] bg-[#065F46] text-white dark:border-[#10B981] dark:bg-[#10B981] dark:text-[#061A14]'
                : 'border-slate-300/80 bg-white text-[#475569] hover:bg-slate-50 dark:border-emerald-800/60 dark:bg-[#0B2920] dark:text-[#9CA3AF] dark:hover:bg-[#133E31]'
            }`}
          >
            جميع السور (114)
          </button>

          <button
            type="button"
            onClick={() => setShowFavoritesOnly(true)}
            data-testid="filter_favorite_surahs"
            className={`inline-flex min-h-[40px] shrink-0 items-center gap-1.5 whitespace-nowrap rounded-xl border px-4 py-1.5 text-xs font-semibold transition-colors ${
              showFavoritesOnly
                ? 'border-[#E11D48] bg-[#E11D48] text-white'
                : 'border-slate-300/80 bg-white text-[#475569] hover:bg-slate-50 dark:border-emerald-800/60 dark:bg-[#0B2920] dark:text-[#9CA3AF] dark:hover:bg-[#133E31]'
            }`}
          >
            <Heart
              className={`h-4 w-4 ${showFavoritesOnly ? 'fill-white' : ''}`}
              aria-hidden="true"
            />
            <span className="tabular-nums">المفضلة ({favoriteSurahNumbers.length})</span>
          </button>
        </div>
      </div>

      {/* Surahs List / Empty State */}
      {displayedSurahs.length === 0 ? (
        <div className="flex flex-col items-center justify-center px-6 py-20 text-center">
          <SearchX className="h-16 w-16 text-slate-400 dark:text-emerald-700" aria-hidden="true" />
          <p className="mt-4 text-base font-bold text-[#475569] dark:text-[#9CA3AF]">
            {showFavoritesOnly
              ? 'لا توجد سور في قائمة المفضلة بعد'
              : 'لا توجد نتائج مطابقة لبحثك'}
          </p>
          <p className="mt-1.5 text-xs text-slate-500 dark:text-[#9CA3AF]/75">
            {showFavoritesOnly
              ? 'اضغط على أيقونة القلب بجانب أي سورة لإضافتها إلى المفضلة'
              : 'تأكد من كتابة اسم السورة أو رقمها بصورة صحيحة'}
          </p>
        </div>
      ) : (
        <div
          data-testid="surahs_lazy_column"
          className="grid grid-cols-1 gap-2.5 md:grid-cols-2"
        >
          {displayedSurahs.map((surah) => {
            const isCurrent = playerState.currentSurah?.id === surah.id;
            const isPlaying = isCurrent && playerState.isPlaying;
            const isFav = favoriteSurahNumbers.includes(surah.id);
            const isDownloaded = isSurahDownloaded(surah.id);
            const downloadItem = getSurahDownloadItem(surah.id);
            const isAvailable = currentReciter
              ? isSurahAvailable(currentReciter, surah.id)
              : true;

            return (
              <SurahItemCard
                key={surah.id}
                surah={surah}
                isPlaying={isPlaying}
                isCurrent={isCurrent}
                isFavorite={isFav}
                isDownloaded={isDownloaded}
                downloadItem={downloadItem}
                isAvailableForReciter={isAvailable}
                onCardClick={() => onSurahSelected(surah)}
                onPlayClick={() => {
                  if (isCurrent) {
                    togglePlayPause();
                  } else {
                    onSurahSelected(surah);
                  }
                }}
                onFavoriteClick={() => void toggleFavorite(surah.id)}
                onDownloadClick={() => void startDownload(surah)}
                onBookmarkClick={() => onOpenAddBookmark(surah)}
              />
            );
          })}
        </div>
      )}
    </div>
  );
}
