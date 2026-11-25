export function formatMs(ms: number): string {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (hours > 0) {
    return `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
  }
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
}

export function formatSpeed(speed: number): string {
  if (speed % 1 === 0) {
    return speed.toFixed(1);
  }
  return speed.toFixed(2).replace(/0+$/, '');
}

export function formatBytesMb(bytes: number, decimals = 2): string {
  if (bytes <= 0) return '0 ميغابايت';
  return `${(bytes / (1024 * 1024)).toFixed(decimals)} ميغابايت`;
}
