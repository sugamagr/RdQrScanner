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

// Compact rupee formatter for KPI tiles where the full ₹1,23,45,678
// notation truncates on 320px mobile screens with 2-col grids and
// loses the most-significant digits (e.g. "₹99,99,99..." hides whether
// it's ₹99 crore or ₹99 lakh — critical financial ambiguity).
// Threshold of 1 lakh chosen because:
//   - Sub-lakh values (≤ 99,999) fit comfortably in 9 chars (`₹99,999`).
//   - Above 1 lakh, full notation needs 12+ chars (`₹1,00,000`) and
//     keeps growing — compact stays at 6-7 chars (`₹1L`, `₹99.99Cr`).
// Indian convention uses L/Cr not K/M (1L = 100,000 ; 1Cr = 10,000,000).
// Two decimals preserved up to crore so ₹1.23Cr stays distinguishable
// from ₹1.24Cr at a glance. R5 oracle bg_78192f17 F6 verified.
export function formatCompactCurrency(n: number): string {
  if (!Number.isFinite(n)) return '\u20B90';
  const abs = Math.abs(n);
  const sign = n < 0 ? '-' : '';
  if (abs < 100_000) return `${sign}\u20B9${formatNumber(abs)}`;
  if (abs < 10_000_000) {
    const lakhs = abs / 100_000;
    const fixed = lakhs >= 100 ? lakhs.toFixed(0) : lakhs.toFixed(2).replace(/\.?0+$/, '');
    return `${sign}\u20B9${fixed}L`;
  }
  const crores = abs / 10_000_000;
  const fixed = crores >= 100 ? crores.toFixed(0) : crores.toFixed(2).replace(/\.?0+$/, '');
  return `${sign}\u20B9${fixed}Cr`;
}
