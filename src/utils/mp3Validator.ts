const MIN_VALID_AUDIO_BYTES = 16_384;
const MAX_MPEG_SYNC_SEARCH_BYTES = 16_384;

function isNonAudioTextPrefix(bytes: Uint8Array): boolean {
  const sampleLen = Math.min(bytes.length, 32);
  const decoder = new TextDecoder('utf-8');
  const prefix = decoder.decode(bytes.subarray(0, sampleLen)).trimStart().toLowerCase();
  return (
    prefix.startsWith('<html') ||
    prefix.startsWith('<!doc') ||
    prefix.startsWith('<?xml') ||
    prefix.startsWith('{')
  );
}

function parseId3v2Header(bytes: Uint8Array): { audioOffset: number } | null {
  if (bytes.length < 10) return null;
  if (bytes[0] !== 0x49 || bytes[1] !== 0x44 || bytes[2] !== 0x33) {
    return null;
  }
  const majorVersion = bytes[3] & 0xff;
  const minorVersion = bytes[4] & 0xff;
  const flags = bytes[5] & 0xff;
  if (majorVersion < 2 || majorVersion > 4 || minorVersion === 0xff) return null;

  const b6 = bytes[6] & 0xff;
  const b7 = bytes[7] & 0xff;
  const b8 = bytes[8] & 0xff;
  const b9 = bytes[9] & 0xff;
  const validSynchsafe =
    (b6 & 0x80) === 0 &&
    (b7 & 0x80) === 0 &&
    (b8 & 0x80) === 0 &&
    (b9 & 0x80) === 0;
  if (!validSynchsafe) return null;

  const tagSize =
    ((b6 & 0x7f) << 21) | ((b7 & 0x7f) << 14) | ((b8 & 0x7f) << 7) | (b9 & 0x7f);
  const footerSize = majorVersion >= 4 && (flags & 0x10) !== 0 ? 10 : 0;
  return { audioOffset: 10 + tagSize + footerSize };
}

function isValidMpegLayer3FrameHeader(b0: number, b1: number, b2: number): boolean {
  if (b0 !== 0xff || (b1 & 0xe0) !== 0xe0) return false;
  const mpegVersionBits = (b1 >>> 3) & 0x03;
  const layerBits = (b1 >>> 1) & 0x03;
  const bitrateIndex = (b2 >>> 4) & 0x0f;
  const sampleRateIndex = (b2 >>> 2) & 0x03;
  return (
    mpegVersionBits !== 0x01 &&
    layerBits === 0x01 &&
    bitrateIndex >= 1 &&
    bitrateIndex <= 14 &&
    sampleRateIndex >= 0 &&
    sampleRateIndex <= 2
  );
}

function findValidMpegFrameOffset(bytes: Uint8Array, startIndex: number): number {
  if (startIndex < 0 || bytes.length - startIndex < 4) return -1;
  const searchLimit = Math.min(bytes.length - 4, startIndex + MAX_MPEG_SYNC_SEARCH_BYTES);
  for (let i = startIndex; i <= searchLimit; i++) {
    if (isValidMpegLayer3FrameHeader(bytes[i], bytes[i + 1], bytes[i + 2])) {
      return i;
    }
  }
  return -1;
}

/**
 * Verifies that a downloaded Blob is a non-empty valid MP3 audio file.
 * Mirrors DownloadManager.isValidMp3File from Android.
 */
export async function isValidMp3Blob(blob: Blob): Promise<boolean> {
  if (blob.size < MIN_VALID_AUDIO_BYTES) {
    return false;
  }

  try {
    const initialSize = Math.min(blob.size, MAX_MPEG_SYNC_SEARCH_BYTES);
    const initialBuf = new Uint8Array(await blob.slice(0, initialSize).arrayBuffer());
    if (initialBuf.length < 4 || isNonAudioTextPrefix(initialBuf)) {
      return false;
    }

    if (initialBuf[0] === 0x49 && initialBuf[1] === 0x44 && initialBuf[2] === 0x33) {
      const id3 = parseId3v2Header(initialBuf);
      if (!id3) return false;
      if (blob.size < id3.audioOffset + 4) return false;
      if (id3.audioOffset + MAX_MPEG_SYNC_SEARCH_BYTES <= initialBuf.length) {
        return findValidMpegFrameOffset(initialBuf, id3.audioOffset) >= 0;
      }
      const postEnd = Math.min(blob.size, id3.audioOffset + MAX_MPEG_SYNC_SEARCH_BYTES);
      const postBuf = new Uint8Array(
        await blob.slice(id3.audioOffset, postEnd).arrayBuffer()
      );
      return findValidMpegFrameOffset(postBuf, 0) >= 0;
    }

    return findValidMpegFrameOffset(initialBuf, 0) >= 0;
  } catch {
    return false;
  }
}
