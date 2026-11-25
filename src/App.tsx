import { useState } from 'react';
import {
  ArrowRight,
  Bookmark,
  BookOpen,
  HardDriveDownload,
  Mic,
  Moon,
  Settings,
} from 'lucide-react';
import { AddBookmarkDialog } from './components/AddBookmarkDialog';
import { FullPlayerBottomSheet } from './components/FullPlayerBottomSheet';
import { MiniPlayerBar } from './components/MiniPlayerBar';
import { SleepTimerDialog } from './components/SleepTimerDialog';
import { ToastContainer } from './components/ToastContainer';
import { QuranProvider, useQuranApp } from './context/QuranContext';
import { getSurahById } from './data/quranData';
import { BookmarksScreen } from './screens/BookmarksScreen';
import { DeveloperScreen } from './screens/DeveloperScreen';
import { DownloadsScreen } from './screens/DownloadsScreen';
import { RecitersScreen } from './screens/RecitersScreen';
import { SurahsScreen } from './screens/SurahsScreen';
import {
  RepeatMode,
  SLEEP_TIMER_OPTIONS,
  SleepTimerOption,
  type QuranNavTab,
  type Surah,
} from './types/quran';
import { formatSpeed } from './utils/formatters';

const NAV_TABS: { id: QuranNavTab; title: string; icon: typeof BookOpen; testId: string }[] = [
  { id: 'surahs', title: 'السور', icon: BookOpen, testId: 'nav_surahs' },
  { id: 'reciters', title: 'القراء', icon: Mic, testId: 'nav_reciters' },
  { id: 'downloads', title: 'التنزيلات', icon: HardDriveDownload, testId: 'nav_downloads' },
  { id: 'bookmarks', title: 'الإشارات', icon: Bookmark, testId: 'nav_bookmarks' },
];

function QuranMainShell() {
  const {
    playerState,
    playSurah,
    resume,
    togglePlayPause,
    playNext,
    playPrevious,
    seekTo,
    setRepeatMode,
    setPlaybackSpeed,
    setSleepTimer,
    addBookmark,
    startDownload,
    isSurahDownloaded,
    getSurahDownloadItem,
    showToast,
  } = useQuranApp();

  const [selectedTab, setSelectedTab] = useState<QuranNavTab>('surahs');
  const [showDeveloperScreen, setShowDeveloperScreen] = useState(false);
  const [showFullPlayer, setShowFullPlayer] = useState(false);
  const [showSleepTimerDialog, setShowSleepTimerDialog] = useState(false);
  const [bookmarkSurahTarget, setBookmarkSurahTarget] = useState<Surah | null>(null);

  const sleepMins = Math.floor(playerState.sleepTimerSecondsRemaining / 60);
  const sleepSecs = playerState.sleepTimerSecondsRemaining % 60;

  const handleSurahSelected = (surah: Surah) => {
    const isNewSurah = playerState.currentSurah?.id !== surah.id;
    if (isNewSurah) {
      void playSurah(surah);
    } else if (!playerState.isPlaying) {
      resume();
    }
    setShowFullPlayer(true);
  };

  const handleToggleRepeat = () => {
    const nextMode =
      playerState.repeatMode === RepeatMode.ALL
        ? RepeatMode.ONE
        : playerState.repeatMode === RepeatMode.ONE
          ? RepeatMode.NONE
          : RepeatMode.ALL;
    setRepeatMode(nextMode);
    const msg =
      nextMode === RepeatMode.ALL
        ? 'تشغيل تلقائي متتالي للسور'
        : nextMode === RepeatMode.ONE
          ? 'تكرار السورة الحالية'
          : 'إيقاف التكرار عند نهاية السورة';
    showToast(msg, 'info');
  };

  const handleCycleSpeed = () => {
    const speeds = [0.75, 1.0, 1.25, 1.5, 2.0];
    const currentIdx = speeds.findIndex((s) => Math.abs(s - playerState.playbackSpeed) < 0.05);
    const nextSpeed = speeds[(currentIdx + 1) % speeds.length];
    setPlaybackSpeed(nextSpeed);
    showToast(`سرعة القراءة: ${formatSpeed(nextSpeed)}x`, 'info');
  };

  return (
    <div className="min-h-screen bg-[#F9FAF8] text-[#0F172A] dark:bg-[#061A14] dark:text-[#ECFDF5]">
      <ToastContainer />

      {/* Top App Bar */}
      <header className="sticky top-0 z-30 border-b border-slate-200/80 bg-white/90 backdrop-blur-md dark:border-emerald-900/50 dark:bg-[#0B2920]/90">
        <div className="mx-auto flex h-14 max-w-5xl items-center justify-between gap-4 px-4">
          <div className="flex min-w-0 items-center gap-2.5">
            {showDeveloperScreen && (
              <button
                type="button"
                onClick={() => setShowDeveloperScreen(false)}
                aria-label="العودة"
                data-testid="developer_back_btn"
                className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full text-[#0F172A] transition-colors hover:bg-slate-100 dark:text-[#ECFDF5] dark:hover:bg-[#133E31]"
              >
                <ArrowRight className="h-5 w-5" />
              </button>
            )}
            <div className="min-w-0">
              <h1 className="truncate text-lg font-bold text-[#065F46] dark:text-[#10B981]">
                {showDeveloperScreen ? 'الإعدادات والإدارة' : 'القرآن الكريم'}
              </h1>
              <p className="truncate text-[11px] text-[#475569] dark:text-[#9CA3AF]">
                القارئ: {playerState.currentReciter?.name ?? 'مشاري العفاسي'}
              </p>
            </div>
          </div>

          <div className="flex shrink-0 items-center gap-1">
            <div className="relative">
              <button
                type="button"
                onClick={() => setShowSleepTimerDialog(true)}
                aria-label="مؤقت النوم"
                data-testid="topbar_sleep_timer"
                className={`flex h-11 w-11 items-center justify-center rounded-full transition-colors hover:bg-slate-100 dark:hover:bg-[#133E31] ${
                  playerState.sleepTimerOption !== SleepTimerOption.OFF
                    ? 'text-[#D97706] dark:text-[#FBBF24]'
                    : 'text-[#475569] dark:text-[#9CA3AF]'
                }`}
              >
                <Moon className="h-5 w-5" />
              </button>
              {playerState.sleepTimerSecondsRemaining > 0 && (
                <span className="font-mono-tabular pointer-events-none absolute right-0 top-0.5 rounded-full bg-[#D97706] px-1.5 py-0.5 text-[9px] font-bold text-white dark:bg-[#FBBF24] dark:text-black">
                  {String(sleepMins).padStart(2, '0')}:{String(sleepSecs).padStart(2, '0')}
                </span>
              )}
            </div>

            <button
              type="button"
              onClick={() => setShowDeveloperScreen((prev) => !prev)}
              aria-label="لوحة المطور والإعدادات"
              data-testid="topbar_developer_settings"
              className={`flex h-11 w-11 items-center justify-center rounded-full transition-colors hover:bg-slate-100 dark:hover:bg-[#133E31] ${
                showDeveloperScreen
                  ? 'bg-[#F0FDF4] text-[#065F46] dark:bg-[#133E31] dark:text-[#10B981]'
                  : 'text-[#475569] dark:text-[#9CA3AF]'
              }`}
            >
              <Settings className="h-5 w-5" />
            </button>
          </div>
        </div>
      </header>

      {/* Main Content */}
      <main>
        {showDeveloperScreen ? (
          <DeveloperScreen
            onNavigateToKahf={() => {
              const kahf = getSurahById(18);
              if (kahf) {
                setShowDeveloperScreen(false);
                setSelectedTab('surahs');
                void playSurah(kahf, { autoPlay: false });
              }
            }}
          />
        ) : selectedTab === 'surahs' ? (
          <SurahsScreen
            onSurahSelected={handleSurahSelected}
            onOpenAddBookmark={(surah) => setBookmarkSurahTarget(surah)}
          />
        ) : selectedTab === 'reciters' ? (
          <RecitersScreen />
        ) : selectedTab === 'downloads' ? (
          <DownloadsScreen onOpenPlayer={() => setShowFullPlayer(true)} />
        ) : (
          <BookmarksScreen onOpenPlayer={() => setShowFullPlayer(true)} />
        )}
      </main>

      {/* Sticky Bottom Dock: MiniPlayerBar + Bottom Navigation */}
      <div className="fixed inset-x-0 bottom-0 z-40">
        <MiniPlayerBar
          playerState={playerState}
          onBarClick={() => setShowFullPlayer(true)}
          onPlayPauseClick={togglePlayPause}
          onNextClick={playNext}
          onPrevClick={playPrevious}
        />

        <nav
          aria-label="التنقل الرئيسي"
          className="border-t border-slate-200/80 bg-white/95 backdrop-blur-md dark:border-emerald-900/50 dark:bg-[#0B2920]/95"
        >
          <div className="mx-auto grid h-16 max-w-5xl grid-cols-4 items-center px-2">
            {NAV_TABS.map((tab) => {
              const Icon = tab.icon;
              const isSelected = !showDeveloperScreen && selectedTab === tab.id;
              return (
                <button
                  key={tab.id}
                  type="button"
                  onClick={() => {
                    setShowDeveloperScreen(false);
                    setSelectedTab(tab.id);
                  }}
                  data-testid={tab.testId}
                  className="flex h-full flex-col items-center justify-center gap-1 transition-colors"
                >
                  <span
                    className={`flex h-8 w-14 items-center justify-center rounded-full transition-colors ${
                      isSelected
                        ? 'bg-[#065F46] text-white dark:bg-[#10B981] dark:text-[#061A14]'
                        : 'text-[#475569] dark:text-[#9CA3AF]'
                    }`}
                  >
                    <Icon className="h-5 w-5" aria-hidden="true" />
                  </span>
                  <span
                    className={`whitespace-nowrap text-[11px] ${
                      isSelected
                        ? 'font-bold text-[#065F46] dark:text-[#10B981]'
                        : 'font-medium text-[#475569] dark:text-[#9CA3AF]'
                    }`}
                  >
                    {tab.title}
                  </span>
                </button>
              );
            })}
          </div>
        </nav>
      </div>

      {/* Full Player Bottom Sheet */}
      {showFullPlayer && playerState.currentSurah && (
        <FullPlayerBottomSheet
          playerState={playerState}
          isDownloaded={isSurahDownloaded(playerState.currentSurah.id)}
          downloadItem={getSurahDownloadItem(playerState.currentSurah.id)}
          onDismiss={() => setShowFullPlayer(false)}
          onPlayPauseClick={togglePlayPause}
          onNextClick={playNext}
          onPrevClick={playPrevious}
          onSeek={seekTo}
          onToggleRepeat={handleToggleRepeat}
          onCycleSpeed={handleCycleSpeed}
          onOpenSleepTimer={() => setShowSleepTimerDialog(true)}
          onOpenAddBookmark={() => setBookmarkSurahTarget(playerState.currentSurah)}
          onDownloadClick={() => {
            if (playerState.currentSurah) {
              void startDownload(playerState.currentSurah);
            }
          }}
        />
      )}

      {/* Sleep Timer Dialog */}
      {showSleepTimerDialog && (
        <SleepTimerDialog
          currentOption={playerState.sleepTimerOption}
          onOptionSelected={(option) => {
            setSleepTimer(option);
            const meta = SLEEP_TIMER_OPTIONS[option];
            if (meta.totalSeconds !== 0) {
              showToast(`تم ضبط مؤقت النوم: ${meta.label}`, 'info');
            } else {
              showToast('تم إلغاء مؤقت النوم', 'info');
            }
          }}
          onDismiss={() => setShowSleepTimerDialog(false)}
        />
      )}

      {/* Add Bookmark Dialog */}
      {bookmarkSurahTarget && (
        <AddBookmarkDialog
          surah={bookmarkSurahTarget}
          positionMs={
            playerState.currentSurah?.id === bookmarkSurahTarget.id
              ? playerState.currentPositionMs
              : 0
          }
          onSave={(note) => {
            void addBookmark(bookmarkSurahTarget, note);
            showToast(`تم حفظ الإشارة المرجعية لسورة ${bookmarkSurahTarget.name}`, 'success');
          }}
          onDismiss={() => setBookmarkSurahTarget(null)}
        />
      )}
    </div>
  );
}

export default function App() {
  return (
    <QuranProvider>
      <QuranMainShell />
    </QuranProvider>
  );
}
