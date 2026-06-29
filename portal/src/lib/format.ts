// QC R2 M5 — lock locale to en-IN so number grouping uses the Indian
// lakh/crore convention (1,23,456 not 123,456) and date formatting is
// stable across operator devices regardless of browser locale. Defers
// to the user's timezone for the time portion via the unspecified
// timeZone option (intentional — operator sees their wall clock).
const LOCALE = 'en-IN';

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return new Intl.DateTimeFormat(LOCALE, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(d);
}

export function formatRelativeTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return iso;
  const now = Date.now();
  const diffMs = now - then;
  const seconds = Math.round(diffMs / 1000);
  if (Math.abs(seconds) < 60) return seconds <= 1 ? 'just now' : `${seconds}s ago`;
  const minutes = Math.round(seconds / 60);
  if (Math.abs(minutes) < 60) return `${minutes}m ago`;
  const hours = Math.round(minutes / 60);
  if (Math.abs(hours) < 24) return `${hours}h ago`;
  const days = Math.round(hours / 24);
  if (Math.abs(days) < 30) return `${days}d ago`;
  return formatDateTime(iso);
}

export function formatNumber(n: number): string {
  return new Intl.NumberFormat(LOCALE).format(n);
}
