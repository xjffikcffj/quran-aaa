import {
  DEFAULT_RECITER,
  DEFAULT_RECITERS,
  isValidHttpsServerUrl,
  normalizeServerUrl,
} from '../data/defaultReciters';
import { getSurahById } from '../data/quranData';
import {
  RepeatMode,
  type BackupImportResult,
  type Bookmark,
  type Reciter,
  type SurahState,
} from '../types/quran';
import { playerService } from './playerService';
import {
  getAllBookmarks,
  getAllCustomReciters,
  getAllSurahStates,
  getPlayerPrefs,
  getSurahState,
  insertBookmark,
  setPlayerPrefs,
  upsertCustomReciter,
  upsertSurahState,
} from './storageService';

export async function exportBackupJson(): Promise<string> {
  const bookmarks = await getAllBookmarks();
  const customReciters = await getAllCustomReciters();
  const allStates = await getAllSurahStates();
  const playerPrefs = getPlayerPrefs();

  const favorites: number[] = [];
  const surahStates = allStates.map((s) => {
    if (s.isFavorite) {
      favorites.push(s.surahNumber);
    }
    return {
      surahNumber: s.surahNumber,
      isFavorite: s.isFavorite,
      lastPositionMs: s.lastPositionMs,
      lastPlayedTimestamp: s.lastPlayedTimestamp,
    };
  });

  const reciterPositions: Record<string, number> = {};
  for (const [key, value] of Object.entries(playerPrefs)) {
    if ((key.startsWith('pos_') || key.startsWith('dur_')) && typeof value === 'number') {
      reciterPositions[key] = value;
    }
  }

  const root = {
    version: 2,
    exportedAt: Date.now(),
    bookmarks: bookmarks.map((b) => ({
      surahNumber: b.surahNumber,
      surahName: b.surahName,
      reciterId: b.reciterId,
      reciterName: b.reciterName,
      positionMs: b.positionMs,
      note: b.note,
      timestamp: b.timestamp,
    })),
    customReciters: customReciters.map((r) => ({
      id: r.id,
      name: r.name,
      serverUrl: r.serverUrl,
      availableSurahsCount: r.availableSurahsCount,
      availableSurahsCsv: (r.availableSurahsList ?? []).join(','),
      subInfo: r.subInfo ?? '',
    })),
    favorites,
    surahStates,
    reciterPositions,
    playerSettings: {
      savedReciterId:
        typeof playerPrefs.saved_reciter_id === 'string'
          ? playerPrefs.saved_reciter_id
          : DEFAULT_RECITER.id,
      lastSurahId:
        typeof playerPrefs.last_surah_id === 'number' ? playerPrefs.last_surah_id : 1,
      savedRepeatMode:
        typeof playerPrefs.saved_repeat_mode === 'string'
          ? playerPrefs.saved_repeat_mode
          : RepeatMode.ALL,
      savedPlaybackSpeed:
        typeof playerPrefs.saved_playback_speed === 'number'
          ? playerPrefs.saved_playback_speed
          : 1.0,
    },
  };

  return JSON.stringify(root, null, 2);
}

function remapPositionKeyIfNeeded(key: string, idMapping: Map<string, string>): string {
  if (idMapping.size === 0) return key;
  const prefix = key.startsWith('pos_') ? 'pos_' : key.startsWith('dur_') ? 'dur_' : null;
  if (!prefix) return key;
  const remainder = key.slice(prefix.length);
  const lastUnderscore = remainder.lastIndexOf('_');
  if (lastUnderscore <= 0) return key;
  const rawReciterId = remainder.slice(0, lastUnderscore);
  const surahPart = remainder.slice(lastUnderscore + 1);
  const mapped = idMapping.get(rawReciterId);
  return mapped ? `${prefix}${mapped}_${surahPart}` : key;
}

export async function importBackupJsonWithResult(jsonString: string): Promise<BackupImportResult> {
  if (!jsonString.trim()) {
    return { success: false, message: 'محتوى النسخة الاحتياطية فارغ.' };
  }

  try {
    const root = JSON.parse(jsonString.trim()) as Record<string, unknown>;
    if (!root || typeof root !== 'object' || Array.isArray(root)) {
      return { success: false, message: 'ملف JSON لا يحتوي على بيانات صالحة للتطبيق.' };
    }

    if ('version' in root) {
      const v = root.version;
      if (typeof v !== 'number' || !Number.isInteger(v) || v < 1 || v > 2) {
        return { success: false, message: 'إصدار النسخة الاحتياطية غير مدعوم.' };
      }
    }

    const hasRecognized =
      'bookmarks' in root ||
      'customReciters' in root ||
      'favorites' in root ||
      'surahStates' in root ||
      'reciterPositions' in root ||
      'playerSettings' in root;

    if (!hasRecognized) {
      return { success: false, message: 'ملف JSON لا يحتوي على بيانات صالحة للتطبيق.' };
    }

    const existingCustom = await getAllCustomReciters();
    const existingCustomById = new Map(existingCustom.map((r) => [r.id, r]));
    const usedIds = new Set<string>([
      ...existingCustom.map((r) => r.id),
      ...DEFAULT_RECITERS.map((r) => r.id),
    ]);

    const parsedRecitersToInsert: Reciter[] = [];
    const resolvedRecitersById = new Map<string, Reciter>();
    const idMapping = new Map<string, string>();
    const batchSeenIds = new Map<string, { name: string; url: string }>();

    if ('customReciters' in root) {
      if (!Array.isArray(root.customReciters)) {
        return { success: false, message: 'بيانات القراء المخصصين غير صالحة.' };
      }
      for (const rawItem of root.customReciters) {
        const obj = rawItem as Record<string, unknown>;
        const rawId = typeof obj?.id === 'string' ? obj.id.trim() : '';
        const name = typeof obj?.name === 'string' ? obj.name.trim() : '';
        const rawServerUrl = typeof obj?.serverUrl === 'string' ? obj.serverUrl.trim() : '';
        if (!rawId || !name || !rawServerUrl) {
          return {
            success: false,
            message: 'بيانات أحد القراء المخصصين ناقصة في النسخة الاحتياطية.',
          };
        }
        if (!isValidHttpsServerUrl(rawServerUrl)) {
          return {
            success: false,
            message: 'تم رفض الاستيراد: روابط القراء المخصصين يجب أن تستخدم بروتوكول HTTPS فقط.',
          };
        }

        const rawCount = 'availableSurahsCount' in obj ? obj.availableSurahsCount : 114;
        if (
          typeof rawCount !== 'number' ||
          !Number.isInteger(rawCount) ||
          rawCount < 1 ||
          rawCount > 114
        ) {
          return { success: false, message: 'عدد السور لأحد القراء المخصصين غير صالح.' };
        }

        const csv = typeof obj.availableSurahsCsv === 'string' ? obj.availableSurahsCsv.trim() : '';
        const surahList: number[] = [];
        if (csv) {
          for (const token of csv.split(',')) {
            const num = Number.parseInt(token.trim(), 10);
            if (Number.isNaN(num) || num < 1 || num > 114) {
              return { success: false, message: 'قائمة السور لأحد القراء المخصصين غير صالحة.' };
            }
            surahList.push(num);
          }
        }

        const normalizedUrl = normalizeServerUrl(rawServerUrl);
        const prevSame = batchSeenIds.get(rawId);
        if (prevSame) {
          if (
            prevSame.name === name &&
            prevSame.url.toLowerCase() === normalizedUrl.toLowerCase()
          ) {
            continue;
          }
          return {
            success: false,
            message: `تم رفض الاستيراد: تعارض في معرّف القارئ المخصص (${rawId}) داخل الملف.`,
          };
        }
        batchSeenIds.set(rawId, { name, url: normalizedUrl });

        const matchingExisting = existingCustom.find(
          (r) => r.name === name && r.serverUrl.toLowerCase() === normalizedUrl.toLowerCase()
        );
        const matchingBatch = parsedRecitersToInsert.find(
          (r) => r.name === name && r.serverUrl.toLowerCase() === normalizedUrl.toLowerCase()
        );

        if (matchingExisting) {
          idMapping.set(rawId, matchingExisting.id);
          resolvedRecitersById.set(matchingExisting.id, matchingExisting);
        } else if (matchingBatch) {
          idMapping.set(rawId, matchingBatch.id);
          resolvedRecitersById.set(matchingBatch.id, matchingBatch);
        } else {
          let resolvedId = rawId;
          if (usedIds.has(resolvedId)) {
            let suffix = 1;
            resolvedId = `custom_${rawId}_${suffix}`;
            while (usedIds.has(resolvedId)) {
              suffix++;
              resolvedId = `custom_${rawId}_${suffix}`;
            }
          }
          usedIds.add(resolvedId);
          idMapping.set(rawId, resolvedId);

          const subInfo =
            typeof obj.subInfo === 'string' && obj.subInfo.trim()
              ? obj.subInfo.trim()
              : 'قارئ مخصص مستعاد من النسخة الاحتياطية';

          const entity: Reciter = {
            id: resolvedId,
            name,
            serverUrl: normalizedUrl,
            availableSurahsCount: rawCount,
            availableSurahsList: surahList,
            isCustom: true,
            subInfo,
          };
          parsedRecitersToInsert.push(entity);
          resolvedRecitersById.set(resolvedId, entity);
        }
      }
    }

    const parsedBookmarks: Omit<Bookmark, 'id'>[] = [];
    if ('bookmarks' in root) {
      if (!Array.isArray(root.bookmarks)) {
        return { success: false, message: 'بيانات الإشارات المرجعية غير صالحة.' };
      }
      for (const rawItem of root.bookmarks) {
        const obj = rawItem as Record<string, unknown>;
        const surahNumber = obj?.surahNumber;
        if (
          typeof surahNumber !== 'number' ||
          !Number.isInteger(surahNumber) ||
          surahNumber < 1 ||
          surahNumber > 114
        ) {
          return { success: false, message: 'رقم السورة في أحد الإشارات المرجعية غير صالح.' };
        }
        const positionMs = obj?.positionMs;
        if (typeof positionMs !== 'number' || !Number.isInteger(positionMs) || positionMs < 0) {
          return { success: false, message: 'موضع الإشارة المرجعية غير صالح.' };
        }
        const rawReciterId = typeof obj?.reciterId === 'string' ? obj.reciterId.trim() : '';
        const reciterName = typeof obj?.reciterName === 'string' ? obj.reciterName.trim() : '';
        if (!rawReciterId || !reciterName) {
          return { success: false, message: 'بيانات القارئ في الإشارة المرجعية غير صالحة.' };
        }
        const rawNote = 'note' in obj ? obj.note : '';
        if (typeof rawNote !== 'string') {
          return { success: false, message: 'ملاحظة الإشارة المرجعية غير صالحة.' };
        }
        const rawTs = 'timestamp' in obj ? obj.timestamp : Date.now();
        if (typeof rawTs !== 'number' || rawTs < 0) {
          return { success: false, message: 'تاريخ الإشارة المرجعية غير صالح.' };
        }
        const surahName =
          typeof obj.surahName === 'string' && obj.surahName.trim()
            ? obj.surahName.trim()
            : getSurahById(surahNumber)?.name;
        if (!surahName) {
          return { success: false, message: 'اسم السورة غير صالح.' };
        }
        parsedBookmarks.push({
          surahNumber,
          surahName,
          reciterId: idMapping.get(rawReciterId) ?? rawReciterId,
          reciterName,
          positionMs,
          note: rawNote.trim(),
          timestamp: rawTs,
        });
      }
    }

    const parsedFavorites = new Set<number>();
    if ('favorites' in root) {
      if (!Array.isArray(root.favorites)) {
        return { success: false, message: 'قائمة المفضلة غير صالحة.' };
      }
      for (const rawNum of root.favorites) {
        if (typeof rawNum !== 'number' || !Number.isInteger(rawNum) || rawNum < 1 || rawNum > 114) {
          return { success: false, message: 'قائمة المفضلة تحتوي على رقم سورة غير صالح.' };
        }
        parsedFavorites.add(rawNum);
      }
    }

    const parsedStates: SurahState[] = [];
    if ('surahStates' in root) {
      if (!Array.isArray(root.surahStates)) {
        return { success: false, message: 'حالات السور غير صالحة.' };
      }
      for (const rawItem of root.surahStates) {
        const obj = rawItem as Record<string, unknown>;
        const surahNum = obj?.surahNumber;
        if (
          typeof surahNum !== 'number' ||
          !Number.isInteger(surahNum) ||
          surahNum < 1 ||
          surahNum > 114
        ) {
          return { success: false, message: 'حالة إحدى السور تحتوي على رقم سورة غير صالح.' };
        }
        const lastPos = 'lastPositionMs' in obj ? obj.lastPositionMs : 0;
        if (typeof lastPos !== 'number' || lastPos < 0) {
          return { success: false, message: 'موضع السورة المحفوظ غير صالح.' };
        }
        const lastTs = 'lastPlayedTimestamp' in obj ? obj.lastPlayedTimestamp : 0;
        if (typeof lastTs !== 'number' || lastTs < 0) {
          return { success: false, message: 'تاريخ تشغيل السورة غير صالح.' };
        }
        const isFav = 'isFavorite' in obj ? obj.isFavorite : false;
        if (typeof isFav !== 'boolean') {
          return { success: false, message: 'قيمة المفضلة لإحدى السور غير صالحة.' };
        }
        parsedStates.push({
          surahNumber: surahNum,
          lastPositionMs: lastPos,
          lastPlayedTimestamp: lastTs,
          isFavorite: isFav,
        });
      }
    }

    const parsedPositions = new Map<string, number>();
    if ('reciterPositions' in root) {
      const posObj = root.reciterPositions as Record<string, unknown>;
      if (!posObj || typeof posObj !== 'object' || Array.isArray(posObj)) {
        return { success: false, message: 'قيم مواضع الاستماع في النسخة غير صالحة.' };
      }
      for (const [key, val] of Object.entries(posObj)) {
        if (!key.startsWith('pos_') && !key.startsWith('dur_')) {
          return { success: false, message: 'مفتاح غير معروف في قسم مواضع الاستماع.' };
        }
        if (typeof val !== 'number' || val < 0) {
          return { success: false, message: 'قيم مواضع الاستماع في النسخة غير صالحة.' };
        }
        parsedPositions.set(remapPositionKeyIfNeeded(key, idMapping), val);
      }
    }

    let validatedReciterId: string | undefined;
    let validatedLastSurahId: number | undefined;
    let validatedRepeatMode: RepeatMode | undefined;
    let validatedPlaybackSpeed: number | undefined;

    if ('playerSettings' in root) {
      const settingsObj = root.playerSettings as Record<string, unknown>;
      if (!settingsObj || typeof settingsObj !== 'object') {
        return { success: false, message: 'إعدادات المشغل غير صالحة.' };
      }
      if ('savedRepeatMode' in settingsObj) {
        const rawRepeat = settingsObj.savedRepeatMode;
        if (rawRepeat !== 'NONE' && rawRepeat !== 'ONE' && rawRepeat !== 'ALL') {
          return { success: false, message: 'نمط التكرار في إعدادات المشغل غير صالح.' };
        }
        validatedRepeatMode = rawRepeat as RepeatMode;
      }
      if ('savedPlaybackSpeed' in settingsObj) {
        const rawSpeed = settingsObj.savedPlaybackSpeed;
        if (
          typeof rawSpeed !== 'number' ||
          !Number.isFinite(rawSpeed) ||
          rawSpeed < 0.5 ||
          rawSpeed > 2.0
        ) {
          return { success: false, message: 'سرعة القراءة في إعدادات المشغل غير صالحة.' };
        }
        validatedPlaybackSpeed = rawSpeed;
      }
      if ('lastSurahId' in settingsObj) {
        const rawLastSurah = settingsObj.lastSurahId;
        if (
          typeof rawLastSurah !== 'number' ||
          !Number.isInteger(rawLastSurah) ||
          rawLastSurah < 1 ||
          rawLastSurah > 114
        ) {
          return { success: false, message: 'رقم السورة الأخيرة في إعدادات المشغل غير صالح.' };
        }
        validatedLastSurahId = rawLastSurah;
      }
      if ('savedReciterId' in settingsObj) {
        const rawReciterId = settingsObj.savedReciterId;
        if (typeof rawReciterId !== 'string' || !rawReciterId.trim()) {
          return { success: false, message: 'معرّف القارئ المحفوظ في إعدادات المشغل غير صالح.' };
        }
        const candidateId = idMapping.get(rawReciterId.trim()) ?? rawReciterId.trim();
        const exists =
          DEFAULT_RECITERS.some((r) => r.id === candidateId) ||
          existingCustomById.has(candidateId) ||
          resolvedRecitersById.has(candidateId);
        if (!exists) {
          return {
            success: false,
            message: `تم رفض الاستيراد: القارئ المحفوظ (${candidateId}) غير موجود.`,
          };
        }
        validatedReciterId = candidateId;
      }
    }

    // Apply validated mutations
    const existingBookmarks = await getAllBookmarks();
    for (const incoming of parsedBookmarks) {
      const alreadyExists = existingBookmarks.some(
        (b) =>
          b.surahNumber === incoming.surahNumber &&
          b.reciterId === incoming.reciterId &&
          b.positionMs === incoming.positionMs &&
          b.note === incoming.note
      );
      if (!alreadyExists) {
        await insertBookmark(incoming);
      }
    }

    for (const reciter of parsedRecitersToInsert) {
      await upsertCustomReciter(reciter);
    }

    const stateMap = new Map(parsedStates.map((s) => [s.surahNumber, s]));
    const mergedSurahNumbers = new Set<number>([
      ...parsedStates.map((s) => s.surahNumber),
      ...parsedFavorites,
    ]);

    for (const surahNum of mergedSurahNumbers) {
      const existing = await getSurahState(surahNum);
      const incoming = stateMap.get(surahNum);
      const mergedFav =
        (existing?.isFavorite ?? false) ||
        (incoming?.isFavorite ?? false) ||
        parsedFavorites.has(surahNum);
      const useIncomingPos =
        incoming !== undefined &&
        incoming.lastPositionMs > 0 &&
        (!existing || incoming.lastPlayedTimestamp >= existing.lastPlayedTimestamp);
      const mergedPos = useIncomingPos ? incoming.lastPositionMs : (existing?.lastPositionMs ?? 0);
      const mergedTs = Math.max(
        existing?.lastPlayedTimestamp ?? 0,
        incoming?.lastPlayedTimestamp ?? 0
      );
      await upsertSurahState({
        surahNumber: surahNum,
        lastPositionMs: mergedPos,
        lastPlayedTimestamp: mergedTs,
        isFavorite: mergedFav,
      });
    }

    const currentPrefs = getPlayerPrefs();
    const updates: Record<string, unknown> = {};
    const removals: string[] = [];

    for (const [key, value] of parsedPositions.entries()) {
      const currentVal = typeof currentPrefs[key] === 'number' ? (currentPrefs[key] as number) : 0;
      if (value > 0 || currentVal === 0) {
        updates[key] = value;
      }
    }
    if (validatedRepeatMode) updates.saved_repeat_mode = validatedRepeatMode;
    if (validatedPlaybackSpeed !== undefined) updates.saved_playback_speed = validatedPlaybackSpeed;
    if (validatedLastSurahId !== undefined) updates.last_surah_id = validatedLastSurahId;
    if (validatedReciterId) {
      updates.saved_reciter_id = validatedReciterId;
      const customEntity =
        resolvedRecitersById.get(validatedReciterId) ?? existingCustomById.get(validatedReciterId);
      if (customEntity) {
        updates.saved_custom_reciter_json = JSON.stringify({
          id: customEntity.id,
          name: customEntity.name,
          serverUrl: customEntity.serverUrl,
          availableSurahsCount: customEntity.availableSurahsCount,
          availableSurahsCsv: (customEntity.availableSurahsList ?? []).join(','),
          subInfo: customEntity.subInfo ?? '',
        });
      } else {
        removals.push('saved_custom_reciter_json');
      }
    }

    if (Object.keys(updates).length > 0 || removals.length > 0) {
      setPlayerPrefs(updates, removals);
    }

    playerService.reloadPersistedStateIfIdle();

    return { success: true, message: 'تمت استعادة البيانات والإعدادات بنجاح.' };
  } catch {
    return { success: false, message: 'فشل استيراد النسخة: تنسيق JSON أو الحقول غير صالحة.' };
  }
}
