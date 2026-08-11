/**
 * Formats a number as Indian Rupees: ₹1,23,456.00
 */
export function formatINR(amount: number | null | undefined): string {
  if (amount == null) return '₹0';
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    minimumFractionDigits: 0,
    maximumFractionDigits: 0,
  }).format(amount);
}

/**
 * Formats an ISO datetime string to a short local format.
 */
export function formatTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleTimeString('en-IN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  });
}

/**
 * Formats an ISO datetime to date + time.
 */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleString('en-IN', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  });
}

/**
 * Truncates a UUID to the first 8 chars for display.
 */
export function shortId(uuid: string): string {
  return uuid.substring(0, 8);
}

/**
 * Computes remaining seconds until a deadline.
 * Returns negative values if past deadline.
 */
export function secondsUntil(deadline: string | null | undefined): number | null {
  if (!deadline) return null;
  const target = new Date(deadline).getTime();
  const now = Date.now();
  return Math.floor((target - now) / 1000);
}

/**
 * Formats seconds into a MM:SS or HH:MM:SS countdown string.
 */
export function formatCountdown(totalSeconds: number): string {
  const abs = Math.abs(totalSeconds);
  const sign = totalSeconds < 0 ? '-' : '';
  const h = Math.floor(abs / 3600);
  const m = Math.floor((abs % 3600) / 60);
  const s = abs % 60;
  const pad = (n: number) => String(n).padStart(2, '0');

  if (h > 0) {
    return `${sign}${h}:${pad(m)}:${pad(s)}`;
  }
  return `${sign}${pad(m)}:${pad(s)}`;
}
