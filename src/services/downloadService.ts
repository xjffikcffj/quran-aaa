import { getAudioUrl, isSurahAvailable } from '../data/defaultReciters';
import { DownloadStatus, type DownloadItem, type Reciter, type Surah } from '../types/quran';
import { isValidMp3Blob } from '../utils/mp3Validator';
import {
  deleteAudioBlob,
  deleteDownloadRecord,
  getAllDownloads,
  getAudioBlob,
  getDownloadById,
  markStuckDownloadsFailed,
  saveAudioBlob,
  upsertDownload,
} from './storageService';

type DownloadsListener = (items: DownloadItem[]) => void;

interface ActiveTask {
  taskId: string;
  generation: number;
  controller: AbortController;
  cancelled: boolean;
}

class DownloadService {
  private activeTasks = new Map<string, ActiveTask>();
  private generations = new Map<string, number>();
  private listeners = new Set<DownloadsListener>();
  private cachedItems: DownloadItem[] = [];
  private initialized = false;

  async init(): Promise<void> {
    if (this.initialized) return;
    this.initialized = true;
    await markStuckDownloadsFailed();
    await this.refresh();
  }

  subscribe(listener: DownloadsListener): () => void {
    this.listeners.add(listener);
    listener(this.cachedItems);
    return () => {
      this.listeners.delete(listener);
    };
  }

  private emit(): void {
    for (const listener of this.listeners) {
      listener(this.cachedItems);
    }
  }

  async refresh(): Promise<void> {
    this.cachedItems = await getAllDownloads();
    this.emit();
  }

  getDownloadsSnapshot(): DownloadItem[] {
    return this.cachedItems;
  }

  isDownloaded(reciterId: string, surahNumber: number): boolean {
    const id = `${reciterId}_${surahNumber}`;
    return this.cachedItems.some(
      (item) => item.id === id && item.status === DownloadStatus.COMPLETED
    );
  }

  async getOfflineAudioBlob(reciterId: string, surahNumber: number): Promise<Blob | null> {
    const id = `${reciterId}_${surahNumber}`;
    const record = await getDownloadById(id);
    if (!record || record.status !== DownloadStatus.COMPLETED) {
      return null;
    }
    const blob = await getAudioBlob(id);
    if (!blob) return null;
    const valid = await isValidMp3Blob(blob);
    return valid ? blob : null;
  }

  private nextGeneration(downloadId: string): number {
    const next = (this.generations.get(downloadId) ?? 0) + 1;
    this.generations.set(downloadId, next);
    return next;
  }

  private currentGeneration(downloadId: string): number {
    return this.generations.get(downloadId) ?? 0;
  }

  async startDownload(reciter: Reciter, surah: Surah): Promise<void> {
    const downloadId = `${reciter.id}_${surah.id}`;

    if (!isSurahAvailable(reciter, surah.id)) {
      await upsertDownload({
        id: downloadId,
        surahNumber: surah.id,
        surahName: surah.name,
        reciterId: reciter.id,
        reciterName: reciter.name,
        fileSizeBytes: 0,
        status: DownloadStatus.FAILED,
        progress: 0,
        errorMessage: `سورة ${surah.name} غير متوفرة لتسجيل ${reciter.name}`,
      });
      await this.refresh();
      return;
    }

    const existingBlob = await getAudioBlob(downloadId);
    if (existingBlob && (await isValidMp3Blob(existingBlob))) {
      await upsertDownload({
        id: downloadId,
        surahNumber: surah.id,
        surahName: surah.name,
        reciterId: reciter.id,
        reciterName: reciter.name,
        fileSizeBytes: existingBlob.size,
        status: DownloadStatus.COMPLETED,
        progress: 100,
      });
      await this.refresh();
      return;
    }

    const taskId = crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}_${Math.random()}`;
    const generation = this.nextGeneration(downloadId);

    const prev = this.activeTasks.get(downloadId);
    if (prev) {
      prev.cancelled = true;
      prev.controller.abort();
      this.activeTasks.delete(downloadId);
    }

    const controller = new AbortController();
    const task: ActiveTask = { taskId, generation, controller, cancelled: false };
    this.activeTasks.set(downloadId, task);

    const isStillCurrent = () =>
      !task.cancelled &&
      this.activeTasks.get(downloadId)?.taskId === taskId &&
      this.currentGeneration(downloadId) === generation;

    const baseItem: DownloadItem = {
      id: downloadId,
      surahNumber: surah.id,
      surahName: surah.name,
      reciterId: reciter.id,
      reciterName: reciter.name,
      fileSizeBytes: 0,
      status: DownloadStatus.DOWNLOADING,
      progress: 0,
    };

    await upsertDownload(baseItem);
    await this.refresh();

    try {
      const primaryUrl = getAudioUrl(reciter, surah.id);
      const blob = await this.downloadWithRetryAndFallback(
        primaryUrl,
        task,
        isStillCurrent,
        async (progress, bytes) => {
          if (!isStillCurrent()) return;
          await upsertDownload({
            ...baseItem,
            progress,
            fileSizeBytes: bytes,
          });
          await this.refresh();
        }
      );

      if (!isStillCurrent()) return;

      const valid = await isValidMp3Blob(blob);
      if (!valid) {
        throw new Error('الملف الذي تم تنزيله غير صالح أو تالف');
      }

      await saveAudioBlob(downloadId, blob);
      if (!isStillCurrent()) return;

      await upsertDownload({
        ...baseItem,
        status: DownloadStatus.COMPLETED,
        progress: 100,
        fileSizeBytes: blob.size,
      });
      await this.refresh();
    } catch (err) {
      if (task.cancelled || !isStillCurrent()) {
        return;
      }
      const errorMessage = this.mapErrorMessage(err);
      await upsertDownload({
        ...baseItem,
        status: DownloadStatus.FAILED,
        errorMessage,
      });
      await this.refresh();
    } finally {
      if (this.activeTasks.get(downloadId)?.taskId === taskId) {
        this.activeTasks.delete(downloadId);
      }
    }
  }

  private async downloadWithRetryAndFallback(
    primaryUrl: string,
    task: ActiveTask,
    isStillCurrent: () => boolean,
    onProgress: (progress: number, bytes: number) => Promise<void>
  ): Promise<Blob> {
    const candidateUrls = [primaryUrl];
    let resolvedMirrors = false;
    let lastError: unknown = null;
    const maxAttempts = 3;

    for (let attempt = 0; attempt < maxAttempts; attempt++) {
      if (!isStillCurrent()) {
        throw new Error('Cancelled');
      }

      const targetUrl = candidateUrls[attempt] ?? candidateUrls[0];
      try {
        const response = await fetch(targetUrl, {
          signal: task.controller.signal,
          headers: {
            Accept: 'audio/mpeg, audio/*;q=0.9, */*;q=0.8',
          },
        });

        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`);
        }

        const contentType = (response.headers.get('content-type') ?? '').toLowerCase();
        if (
          contentType.startsWith('text/html') ||
          contentType.startsWith('application/json') ||
          contentType.startsWith('text/plain')
        ) {
          throw new Error(`استجابة غير صوتية من الخادم (${contentType})`);
        }

        const contentLengthHeader = response.headers.get('content-length');
        const totalBytes = contentLengthHeader ? Number.parseInt(contentLengthHeader, 10) : -1;

        if (!response.body) {
          const blob = await response.blob();
          await onProgress(100, blob.size);
          return blob;
        }

        const reader = response.body.getReader();
        const chunks: BlobPart[] = [];
        let downloadedBytes = 0;
        let lastReportedPercent = -1;
        let lastReportedChunk = 0;

        while (true) {
          if (!isStillCurrent()) {
            await reader.cancel();
            throw new Error('Cancelled');
          }
          const { done, value } = await reader.read();
          if (done) break;
          if (value) {
            chunks.push(value);
            downloadedBytes += value.byteLength;

            if (totalBytes > 0) {
              const pct = Math.min(100, Math.max(0, Math.floor((downloadedBytes * 100) / totalBytes)));
              if (pct >= lastReportedPercent + 5 || pct === 100) {
                lastReportedPercent = pct;
                await onProgress(pct, downloadedBytes);
              }
            } else if (downloadedBytes - lastReportedChunk >= 131_072) {
              lastReportedChunk = downloadedBytes;
              await onProgress(0, downloadedBytes);
            }
          }
        }

        if (totalBytes > 0 && downloadedBytes < totalBytes) {
          throw new Error(`انقطع التنزيل قبل اكتمال الملف (${downloadedBytes} / ${totalBytes})`);
        }

        return new Blob(chunks, { type: 'audio/mpeg' });
      } catch (err) {
        if (task.cancelled || (err instanceof DOMException && err.name === 'AbortError')) {
          throw err;
        }
        lastError = err;
        if (!resolvedMirrors && primaryUrl.toLowerCase().includes('archive.org/download/')) {
          resolvedMirrors = true;
          const mirrors = await this.resolveArchiveOrgDirectMirrors(primaryUrl, task.controller.signal);
          candidateUrls.push(...mirrors);
        }
        if (attempt + 1 < maxAttempts && isStillCurrent()) {
          await new Promise((r) => setTimeout(r, 750 * (attempt + 1)));
        }
      }
    }

    throw lastError instanceof Error ? lastError : new Error('تعذر إتمام التنزيل');
  }

  private async resolveArchiveOrgDirectMirrors(
    downloadUrl: string,
    signal: AbortSignal
  ): Promise<string[]> {
    try {
      const marker = 'archive.org/download/';
      const idx = downloadUrl.toLowerCase().indexOf(marker);
      if (idx === -1) return [];
      const after = downloadUrl.slice(idx + marker.length);
      const slashIdx = after.indexOf('/');
      if (slashIdx <= 0) return [];
      const identifier = after.slice(0, slashIdx);
      const filePath = after.slice(slashIdx + 1);
      if (!identifier || !filePath) return [];

      const resp = await fetch(`https://archive.org/metadata/${identifier}`, { signal });
      if (!resp.ok) return [];
      const json = await resp.json();
      const dir = typeof json.dir === 'string' ? json.dir : '';
      if (!dir) return [];

      const mirrors = new Set<string>();
      if (typeof json.d1 === 'string' && json.d1) {
        mirrors.add(`https://${json.d1}${dir}/${filePath}`);
      }
      if (typeof json.d2 === 'string' && json.d2) {
        mirrors.add(`https://${json.d2}${dir}/${filePath}`);
      }
      if (Array.isArray(json.workable_servers)) {
        for (const host of json.workable_servers) {
          if (typeof host === 'string' && host) {
            mirrors.add(`https://${host}${dir}/${filePath}`);
          }
        }
      }
      return Array.from(mirrors);
    } catch {
      return [];
    }
  }

  private mapErrorMessage(err: unknown): string {
    const msg = err instanceof Error ? err.message : '';
    if (msg.includes('HTTP 404')) return 'الملف الصوتي غير موجود على الخادم (404)';
    if (msg.includes('HTTP 503') || msg.includes('HTTP 429'))
      return 'الخادم مزدحم مؤقتاً، يرجى إعادة المحاولة';
    if (msg.toLowerCase().includes('failed to fetch') || msg.toLowerCase().includes('network'))
      return 'تعذر الاتصال بالإنترنت، تحقق من الشبكة';
    if (msg) return msg;
    return 'فشل التنزيل';
  }

  async cancelDownload(downloadId: string): Promise<void> {
    this.nextGeneration(downloadId);
    const task = this.activeTasks.get(downloadId);
    if (task) {
      task.cancelled = true;
      task.controller.abort();
      this.activeTasks.delete(downloadId);
    }
    const existingBlob = await getAudioBlob(downloadId);
    if (!existingBlob || !(await isValidMp3Blob(existingBlob))) {
      await deleteDownloadRecord(downloadId);
    }
    await this.refresh();
  }

  async deleteDownload(downloadId: string): Promise<void> {
    this.nextGeneration(downloadId);
    const task = this.activeTasks.get(downloadId);
    if (task) {
      task.cancelled = true;
      task.controller.abort();
      this.activeTasks.delete(downloadId);
    }
    await deleteAudioBlob(downloadId);
    await deleteDownloadRecord(downloadId);
    await this.refresh();
  }
}

export const downloadService = new DownloadService();
