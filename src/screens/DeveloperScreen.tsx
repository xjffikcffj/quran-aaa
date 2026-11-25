import { useRef, useState } from 'react';
import {
  BellRing,
  Copy,
  DatabaseBackup,
  FileUp,
  Info,
  Monitor,
  Moon,
  Plus,
  Save,
  Send,
  Settings,
  Share2,
  Sun,
  Trash2,
  UserPlus,
} from 'lucide-react';
import { AddCustomReciterDialog } from '../components/AddCustomReciterDialog';
import { useQuranApp } from '../context/QuranContext';
import type { ThemeMode } from '../types/quran';

export function DeveloperScreen({ onNavigateToKahf }: { onNavigateToKahf?: () => void }) {
  const {
    reciters,
    fridayReminderEnabled,
    themeMode,
    setThemeMode,
    toggleFridayReminder,
    triggerFridayReminderTest,
    addCustomReciter,
    deleteCustomReciter,
    exportBackup,
    importBackup,
    showToast,
  } = useQuranApp();

  const customReciters = reciters.filter((r) => r.isCustom);

  const [showAddDialog, setShowAddDialog] = useState(false);
  const [showImportField, setShowImportField] = useState(false);
  const [importJsonText, setImportJsonText] = useState('');
  const [importFeedback, setImportFeedback] = useState<{ success: boolean; message: string } | null>(
    null
  );
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const handleCopyBackup = async () => {
    try {
      const json = await exportBackup();
      await navigator.clipboard.writeText(json);
      showToast('تم نسخ النسخة الاحتياطية إلى الحافظة', 'success');
    } catch {
      showToast('تعذر النسخ إلى الحافظة، استخدم حفظ كملف', 'error');
    }
  };

  const handleSaveBackupFile = async () => {
    try {
      const json = await exportBackup();
      const blob = new Blob([json], { type: 'application/json;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'quran_audio_backup.json';
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
      showToast('تم حفظ ملف النسخة الاحتياطية بنجاح', 'success');
    } catch {
      showToast('تعذر حفظ ملف النسخة الاحتياطية', 'error');
    }
  };

  const handleShareBackup = async () => {
    try {
      const json = await exportBackup();
      if (navigator.share) {
        await navigator.share({
          title: 'نسخة احتياطية - القرآن الصوتي',
          text: json,
        });
      } else {
        await navigator.clipboard.writeText(json);
        showToast('تم نسخ النسخة الاحتياطية إلى الحافظة للمشاركة', 'info');
      }
    } catch {
      // user cancelled share
    }
  };

  const handleFileImport = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    try {
      const text = await file.text();
      const result = await importBackup(text);
      setImportFeedback(result);
      showToast(result.message, result.success ? 'success' : 'error');
    } catch {
      const err = { success: false, message: 'تعذر قراءة الملف المحدد.' };
      setImportFeedback(err);
      showToast(err.message, 'error');
    } finally {
      e.target.value = '';
    }
  };

  const handleTextImport = async () => {
    const result = await importBackup(importJsonText);
    setImportFeedback(result);
    showToast(result.message, result.success ? 'success' : 'error');
    if (result.success) {
      setShowImportField(false);
      setImportJsonText('');
    }
  };

  return (
    <div
      data-testid="developer_screen"
      className="mx-auto w-full max-w-5xl space-y-4 px-4 pb-36 pt-3"
    >
      {/* Header Banner */}
      <div className="rounded-2xl bg-[#065F46] p-5 text-white dark:bg-[#0B2920] dark:border dark:border-emerald-800/60">
        <div className="flex items-center gap-2.5">
          <Settings className="h-7 w-7 text-[#FBBF24]" aria-hidden="true" />
          <h2 className="text-xl font-bold">لوحة المطور والإعدادات المتقدمة</h2>
        </div>
        <p className="mt-1.5 text-xs text-white/85 dark:text-[#9CA3AF]">
          إدارة القراء المخصصين، تذكير يوم الجمعة المحلي، مظهر التطبيق، والنسخ الاحتياطي الشامل
          للبيانات على متصفحك.
        </p>
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        {/* Section 1: Custom Reciters Management */}
        <section className="rounded-2xl border border-slate-200/80 bg-white p-4 dark:border-emerald-900/50 dark:bg-[#0B2920]">
          <div className="flex items-center justify-between gap-2">
            <div className="flex items-center gap-2">
              <UserPlus
                className="h-5 w-5 text-[#065F46] dark:text-[#10B981]"
                aria-hidden="true"
              />
              <h3 className="text-base font-bold text-[#0F172A] dark:text-[#ECFDF5]">
                إدارة القراء المخصصين
              </h3>
            </div>

            <button
              type="button"
              onClick={() => setShowAddDialog(true)}
              data-testid="add_custom_reciter_btn"
              className="inline-flex min-h-[40px] shrink-0 items-center gap-1.5 rounded-xl bg-[#065F46] px-3.5 py-2 text-xs font-semibold text-white transition-colors hover:bg-[#064E3B] dark:bg-[#10B981] dark:text-[#061A14]"
            >
              <Plus className="h-4 w-4" />
              <span>إضافة قارئ</span>
            </button>
          </div>

          <p className="mt-2 text-xs leading-relaxed text-[#475569] dark:text-[#9CA3AF]">
            يمكنك إضافة قراء إضافيين بروابط HTTPS لخوادمهم الصوتية، وتُحفظ البيانات محلياً في قاعدة
            IndexedDB وتستعاد تلقائياً بعد إعادة التحميل.
          </p>

          {customReciters.length === 0 ? (
            <p className="mt-3 text-xs text-slate-500 dark:text-[#9CA3AF]/75">
              لا يوجد قراء مخصصون حالياً. القراء الـ8 الأساسيون متوفرون في تبويب القراء.
            </p>
          ) : (
            <div className="mt-3 space-y-2">
              {customReciters.map((reciter) => (
                <div
                  key={reciter.id}
                  className="flex items-center justify-between gap-2 rounded-xl bg-[#F0FDF4] px-3 py-2 dark:bg-[#133E31]"
                >
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm font-bold text-[#0F172A] dark:text-[#ECFDF5]">
                      {reciter.name}
                    </p>
                    <p
                      dir="ltr"
                      className="truncate text-right text-[11px] text-[#475569] dark:text-[#9CA3AF]"
                    >
                      {reciter.availableSurahsCount} سورة · {reciter.serverUrl}
                    </p>
                  </div>

                  <button
                    type="button"
                    onClick={() => void deleteCustomReciter(reciter.id)}
                    aria-label="حذف القارئ"
                    className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-[#DC2626] transition-colors hover:bg-red-500/10 dark:text-red-400"
                  >
                    <Trash2 className="h-4 w-4" />
                  </button>
                </div>
              ))}
            </div>
          )}
        </section>

        {/* Section 2: Friday Reminder + Theme */}
        <div className="space-y-4">
          <section className="rounded-2xl border border-slate-200/80 bg-white p-4 dark:border-emerald-900/50 dark:bg-[#0B2920]">
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <BellRing
                  className="h-5 w-5 text-[#D97706] dark:text-[#FBBF24]"
                  aria-hidden="true"
                />
                <h3 className="text-base font-bold text-[#0F172A] dark:text-[#ECFDF5]">
                  تذكير يوم الجمعة (سورة الكهف)
                </h3>
              </div>

              <button
                type="button"
                role="switch"
                aria-checked={fridayReminderEnabled}
                onClick={() => toggleFridayReminder(!fridayReminderEnabled)}
                data-testid="friday_reminder_switch"
                className={`relative inline-flex h-7 w-12 shrink-0 cursor-pointer items-center rounded-full transition-colors ${
                  fridayReminderEnabled
                    ? 'bg-[#D97706] dark:bg-[#FBBF24]'
                    : 'bg-slate-300 dark:bg-emerald-900'
                }`}
              >
                <span
                  className={`inline-block h-5 w-5 transform rounded-full bg-white shadow transition-transform ${
                    fridayReminderEnabled ? '-translate-x-6' : '-translate-x-1'
                  }`}
                />
              </button>
            </div>

            <p className="mt-2 text-xs leading-relaxed text-[#475569] dark:text-[#9CA3AF]">
              تفعيل إشعار المتصفح للتذكير بسورة الكهف يوم الجمعة دون الحاجة لخادم خارجي.
            </p>

            <button
              type="button"
              onClick={() => void triggerFridayReminderTest(onNavigateToKahf)}
              data-testid="test_friday_notification_btn"
              className="mt-3 inline-flex min-h-[40px] items-center gap-2 rounded-xl border border-slate-300 px-3.5 py-2 text-xs font-semibold text-[#065F46] transition-colors hover:bg-[#F0FDF4] dark:border-emerald-700 dark:text-[#10B981] dark:hover:bg-[#133E31]"
            >
              <Send className="h-4 w-4" />
              <span>تجربة إرسال إشعار التذكير فوراً</span>
            </button>
          </section>

          {/* Theme Switcher */}
          <section className="rounded-2xl border border-slate-200/80 bg-white p-4 dark:border-emerald-900/50 dark:bg-[#0B2920]">
            <h3 className="text-base font-bold text-[#0F172A] dark:text-[#ECFDF5]">
              مظهر التطبيق
            </h3>
            <div className="mt-3 grid grid-cols-3 gap-2">
              {(
                [
                  { id: 'system', label: 'النظام', icon: Monitor },
                  { id: 'light', label: 'فاتح', icon: Sun },
                  { id: 'dark', label: 'داكن', icon: Moon },
                ] as const
              ).map(({ id, label, icon: Icon }) => {
                const active = themeMode === id;
                return (
                  <button
                    key={id}
                    type="button"
                    onClick={() => setThemeMode(id as ThemeMode)}
                    className={`flex min-h-[42px] items-center justify-center gap-1.5 rounded-xl border px-3 py-2 text-xs font-semibold transition-colors ${
                      active
                        ? 'border-[#065F46] bg-[#065F46] text-white dark:border-[#10B981] dark:bg-[#10B981] dark:text-[#061A14]'
                        : 'border-slate-200 bg-slate-50 text-[#475569] hover:bg-slate-100 dark:border-emerald-800/60 dark:bg-[#133E31]/60 dark:text-[#9CA3AF]'
                    }`}
                  >
                    <Icon className="h-4 w-4" />
                    <span>{label}</span>
                  </button>
                );
              })}
            </div>
          </section>
        </div>
      </div>

      {/* Section 3: Backup & Restore (JSON v2) */}
      <section className="rounded-2xl border border-slate-200/80 bg-white p-4 dark:border-emerald-900/50 dark:bg-[#0B2920]">
        <div className="flex items-center gap-2">
          <DatabaseBackup
            className="h-5 w-5 text-[#059669] dark:text-[#10B981]"
            aria-hidden="true"
          />
          <h3 className="text-base font-bold text-[#0F172A] dark:text-[#ECFDF5]">
            النسخ الاحتياطي والاستعادة محلياً
          </h3>
        </div>

        <p className="mt-2 text-xs leading-relaxed text-[#475569] dark:text-[#9CA3AF]">
          يشمل النسخ الاحتياطي (JSON v2): المفضلة، الإشارات المرجعية وملاحظاتها، مواضع الاستماع
          لكل قارئ، والقراء المخصصين، مع الدمج الآمن والتحقق الكامل قبل التطبيق.
        </p>

        <div className="mt-3 flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={() => void handleCopyBackup()}
            data-testid="export_backup_btn"
            className="inline-flex min-h-[40px] items-center gap-1.5 rounded-xl bg-[#065F46] px-4 py-2 text-xs font-semibold text-white transition-colors hover:bg-[#064E3B] dark:bg-[#10B981] dark:text-[#061A14]"
          >
            <Copy className="h-4 w-4" />
            <span>نسخ JSON</span>
          </button>

          <button
            type="button"
            onClick={() => void handleSaveBackupFile()}
            className="inline-flex min-h-[40px] items-center gap-1.5 rounded-xl border border-slate-300 px-3.5 py-2 text-xs font-semibold text-[#0F172A] transition-colors hover:bg-slate-50 dark:border-emerald-700 dark:text-[#ECFDF5] dark:hover:bg-[#133E31]"
          >
            <Save className="h-4 w-4" />
            <span>حفظ كملف</span>
          </button>

          <button
            type="button"
            onClick={() => void handleShareBackup()}
            aria-label="مشاركة النسخة الاحتياطية"
            className="inline-flex min-h-[40px] items-center gap-1.5 rounded-xl border border-slate-300 px-3.5 py-2 text-xs font-semibold text-[#0F172A] transition-colors hover:bg-slate-50 dark:border-emerald-700 dark:text-[#ECFDF5] dark:hover:bg-[#133E31]"
          >
            <Share2 className="h-4 w-4" />
            <span>مشاركة</span>
          </button>

          <button
            type="button"
            onClick={() => setShowImportField((prev) => !prev)}
            data-testid="toggle_import_btn"
            className="inline-flex min-h-[40px] items-center gap-1.5 rounded-xl border border-slate-300 px-3.5 py-2 text-xs font-semibold text-[#0F172A] transition-colors hover:bg-slate-50 dark:border-emerald-700 dark:text-[#ECFDF5] dark:hover:bg-[#133E31]"
          >
            <span>استعادة من نص JSON</span>
          </button>

          <button
            type="button"
            onClick={() => fileInputRef.current?.click()}
            className="inline-flex min-h-[40px] items-center gap-1.5 rounded-xl border border-slate-300 px-3.5 py-2 text-xs font-semibold text-[#0F172A] transition-colors hover:bg-slate-50 dark:border-emerald-700 dark:text-[#ECFDF5] dark:hover:bg-[#133E31]"
          >
            <FileUp className="h-4 w-4" />
            <span>استيراد من ملف</span>
          </button>

          <input
            ref={fileInputRef}
            type="file"
            accept="application/json,.json,text/plain"
            onChange={(e) => void handleFileImport(e)}
            className="hidden"
          />
        </div>

        {importFeedback && (
          <p
            className={`mt-2.5 text-xs font-semibold ${
              importFeedback.success
                ? 'text-[#059669] dark:text-[#10B981]'
                : 'text-[#DC2626] dark:text-red-400'
            }`}
          >
            {importFeedback.message}
          </p>
        )}

        {showImportField && (
          <div className="mt-3 space-y-2.5">
            <textarea
              rows={4}
              dir="ltr"
              value={importJsonText}
              onChange={(e) => setImportJsonText(e.target.value)}
              placeholder="الصق كود JSON للنسخة الاحتياطية هنا"
              data-testid="import_json_input"
              className="font-mono-tabular w-full rounded-xl border border-slate-300 bg-white p-3 text-left text-xs text-[#0F172A] focus:border-[#065F46] focus:outline-none dark:border-emerald-800 dark:bg-[#061A14] dark:text-[#ECFDF5]"
            />
            <button
              type="button"
              onClick={() => void handleTextImport()}
              data-testid="confirm_import_btn"
              className="min-h-[40px] rounded-xl bg-[#065F46] px-4 py-2 text-xs font-semibold text-white transition-colors hover:bg-[#064E3B] dark:bg-[#10B981] dark:text-[#061A14]"
            >
              تأكيد الاستعادة
            </button>
          </div>
        )}
      </section>

      {/* Section 4: Truthful Web Architecture Info */}
      <section className="rounded-2xl border border-slate-200/80 bg-[#F0FDF4]/60 p-4 dark:border-emerald-900/50 dark:bg-[#133E31]/50">
        <div className="flex items-center gap-2">
          <Info className="h-5 w-5 text-[#065F46] dark:text-[#10B981]" aria-hidden="true" />
          <h3 className="text-base font-bold text-[#0F172A] dark:text-[#ECFDF5]">
            البنية التقنية للتطبيق
          </h3>
        </div>
        <ul className="mt-2.5 space-y-1.5 text-xs leading-relaxed text-[#475569] dark:text-[#9CA3AF]">
          <li>
            · التخزين المحلي: يعتمد التطبيق على IndexedDB و localStorage لحفظ المفضلة، الإشارات
            المرجعية، ملفات السور المنزلة، والقراء المخصصين محلياً في المتصفح.
          </li>
          <li>
            · التلاوة والصوتيات: يعمل عبر مشغل HTML5 Audio مع MediaSession API لأزرار التحكم
            والتحقق من سلامة ملفات MP3 عند التنزيل للاستماع دون اتصال.
          </li>
          <li>· معرف التطبيق الأصلي: com.aistudio.quranaudio.yqtnzp.</li>
        </ul>
      </section>

      {showAddDialog && (
        <AddCustomReciterDialog
          onAdd={async (name, url, count) => {
            const added = await addCustomReciter(name, url, count);
            if (added) {
              showToast(`تمت إضافة القارئ ${name} بنجاح`, 'success');
            } else {
              showToast('تعذر الإضافة: يجب استخدام رابط HTTPS صالح', 'error');
            }
          }}
          onDismiss={() => setShowAddDialog(false)}
        />
      )}
    </div>
  );
}
