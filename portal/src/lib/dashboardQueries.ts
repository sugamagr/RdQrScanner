import { supabase } from './supabase';
import { fetchActivityFeed } from './queries';
import type { ActivityRow } from '../types/db';

/**
 * Dashboard aggregation contract. Every field is computed by fan-out
 * queries here rather than a materialized cloud view because:
 *   1. The scale is small enough that 6-8 parallel reads finish under
 *      400ms on a warm connection (verified empirically against the
 *      live Supabase project — 4 phones, ~30 sessions/yr).
 *   2. Adding a server-side view would require another migration the
 *      operator has to paste, which the migration playbook avoids when
 *      a client roll-up is good enough.
 *   3. Realtime invalidation of a materialized view is harder than
 *      invalidating a query key list.
 *
 * Range semantics: `null` means "all time"; a positive integer N means
 * "last N calendar months including the current one". The `rangeStart`
 * cut-off is computed against the user's local clock (toISOString)
 * which is consistent with how the phone stamps `end_time` (UTC) — a
 * small DST/timezone drift on the boundary day is acceptable for a
 * dashboard summary.
 */
export type DashboardRange = 3 | 6 | 12 | null;

export interface DashboardStats {
  totalAccounts: number;
  activeAccounts: number;
  inactiveAccounts: number;
  defaulterCount: number;
  totalSessions: number;
  totalRdScans: number;
  totalCollectedThisMonth: number;
  activeDevices: number;
  totalDevices: number;
  recentActivity: ActivityRow[];
  monthlySessionCounts: Array<{ month: string; count: number }>;
  monthlyScansCollected: Array<{ month: string; collected: number; defaulters: number }>;
  sourceBreakdown: Array<{ source: 'MANUAL' | 'CSV'; count: number }>;
  topDefaulters: Array<{ name: string; rdNumber: string; monthsOverdue: number }>;
  amountHistogram: Array<{ bucket: string; count: number }>;
}

/**
 * Returns ISO timestamp string for "N months ago, start of month",
 * computed in UTC. Matches the boundary semantics of phone-stamped
 * times in `scan_sessions.end_time` (also UTC).
 */
function rangeStartIso(months: number | null): string | null {
  if (months == null) return null;
  const now = new Date();
  const start = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - (months - 1), 1));
  return start.toISOString();
}

/**
 * Builds a YYYY-MM bucket key from an ISO timestamp. Lexical order
 * equals chronological order — matches the phone-side MonthYear
 * contract documented in csvParser.ts.
 */
function monthKey(iso: string): string {
  const d = new Date(iso);
  const m = `${d.getUTCMonth() + 1}`.padStart(2, '0');
  return `${d.getUTCFullYear()}-${m}`;
}

/**
 * Generates the full month-key sequence between `start` (inclusive)
 * and now (inclusive) in ascending order. Used so empty months show
 * up as 0 on the chart instead of disappearing entirely.
 */
function monthSequence(months: number | null): string[] {
  const seq: string[] = [];
  const now = new Date();
  const span = months ?? 12;
  for (let i = span - 1; i >= 0; i -= 1) {
    const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - i, 1));
    seq.push(monthKey(d.toISOString()));
  }
  return seq;
}

interface SessionStub {
  end_time: string;
}

interface RdNumberStub {
  scanned_at: string;
  months_paid: number;
  number: string;
}

interface RdAccountStub {
  rd_number: string;
  name: string;
  monthly_amount: number;
  last_paid_through: string | null;
  is_active: boolean;
  source: 'MANUAL' | 'CSV';
}

interface DeviceStub {
  last_seen_at: string | null;
}

export async function fetchDashboardStats(range: DashboardRange): Promise<DashboardStats> {
  const startIso = rangeStartIso(range);
  const seq = monthSequence(range);

  // Fire every independent read in parallel so the dashboard's
  // perceived latency is the slowest single query, not the sum.
  // Caller-side realtime invalidation re-runs all of these together,
  // so they share a single cache-bust unit.
  const [
    accountsRes,
    devicesRes,
    sessionsRes,
    rdRes,
    activityRes,
  ] = await Promise.all([
    supabase
      .from('rd_accounts')
      .select('rd_number, name, monthly_amount, last_paid_through, is_active, source')
      .is('deleted_at', null),
    supabase
      .from('devices')
      .select('last_seen_at')
      .is('deleted_at', null),
    startIso
      ? supabase
          .from('scan_sessions')
          .select('end_time')
          .is('deleted_at', null)
          .gte('end_time', startIso)
      : supabase
          .from('scan_sessions')
          .select('end_time')
          .is('deleted_at', null),
    startIso
      ? supabase
          .from('rd_numbers')
          .select('scanned_at, months_paid, number')
          .is('deleted_at', null)
          .gte('scanned_at', startIso)
      : supabase
          .from('rd_numbers')
          .select('scanned_at, months_paid, number')
          .is('deleted_at', null),
    fetchActivityFeed({ limit: 8 }),
  ]);

  if (accountsRes.error) throw accountsRes.error;
  if (devicesRes.error) throw devicesRes.error;
  if (sessionsRes.error) throw sessionsRes.error;
  if (rdRes.error) throw rdRes.error;

  const accounts = (accountsRes.data ?? []) as RdAccountStub[];
  const devices = (devicesRes.data ?? []) as DeviceStub[];
  const sessions = (sessionsRes.data ?? []) as SessionStub[];
  const rdNumbers = (rdRes.data ?? []) as RdNumberStub[];

  // Accounts metrics.
  const totalAccounts = accounts.length;
  const activeAccounts = accounts.filter((a) => a.is_active).length;
  const inactiveAccounts = totalAccounts - activeAccounts;

  // Defaulter count from rd_numbers months_paid > 1 — same definition
  // the phone bell and the Activity feed use. Counted as DISTINCT
  // rd_number because the same defaulter scanned across multiple
  // sessions should not multi-count toward the dashboard headline.
  const defaulterSet = new Set<string>();
  for (const r of rdNumbers) {
    if (r.months_paid > 1) defaulterSet.add(r.number);
  }
  const defaulterCount = defaulterSet.size;

  // "Collected this month" = sum of monthly_amount for accounts whose
  // last_paid_through ≥ current YYYY-MM. Lexical compare equals
  // chronological compare per the MonthYear invariant.
  const currentMonth = monthKey(new Date().toISOString());
  const totalCollectedThisMonth = accounts.reduce((sum, a) => {
    if (a.last_paid_through != null && a.last_paid_through >= currentMonth) {
      return sum + a.monthly_amount;
    }
    return sum;
  }, 0);

  // Active devices = last_seen_at within 5 minutes. 5 min matches the
  // online-pill threshold used on the Devices page (spec §15.4).
  const FIVE_MIN_MS = 5 * 60 * 1000;
  const now = Date.now();
  let activeDevices = 0;
  for (const d of devices) {
    if (d.last_seen_at != null) {
      const last = Date.parse(d.last_seen_at);
      if (!Number.isNaN(last) && now - last <= FIVE_MIN_MS) activeDevices += 1;
    }
  }
  const totalDevices = devices.length;

  // Monthly session counts. Empty months explicitly land as zero
  // (chart prefers contiguous data).
  const sessionMonthCounts = new Map<string, number>();
  for (const m of seq) sessionMonthCounts.set(m, 0);
  for (const s of sessions) {
    const key = monthKey(s.end_time);
    if (sessionMonthCounts.has(key)) {
      sessionMonthCounts.set(key, (sessionMonthCounts.get(key) ?? 0) + 1);
    }
  }
  const monthlySessionCounts = seq.map((m) => ({
    month: m,
    count: sessionMonthCounts.get(m) ?? 0,
  }));

  // Per-month scans/defaulters. Defaulters here are scan-level
  // occurrences (months_paid > 1 within the month), not distinct
  // rd_numbers — matches what the user sees per session.
  const scanMonthCollected = new Map<string, number>();
  const scanMonthDefaulters = new Map<string, number>();
  for (const m of seq) {
    scanMonthCollected.set(m, 0);
    scanMonthDefaulters.set(m, 0);
  }
  for (const r of rdNumbers) {
    const key = monthKey(r.scanned_at);
    if (scanMonthCollected.has(key)) {
      scanMonthCollected.set(key, (scanMonthCollected.get(key) ?? 0) + 1);
      if (r.months_paid > 1) {
        scanMonthDefaulters.set(key, (scanMonthDefaulters.get(key) ?? 0) + 1);
      }
    }
  }
  const monthlyScansCollected = seq.map((m) => ({
    month: m,
    collected: scanMonthCollected.get(m) ?? 0,
    defaulters: scanMonthDefaulters.get(m) ?? 0,
  }));

  // Source breakdown of the account master list.
  let manualCount = 0;
  let csvCount = 0;
  for (const a of accounts) {
    if (a.source === 'CSV') csvCount += 1;
    else manualCount += 1;
  }
  const sourceBreakdown: Array<{ source: 'MANUAL' | 'CSV'; count: number }> = [
    { source: 'MANUAL', count: manualCount },
    { source: 'CSV', count: csvCount },
  ];

  // Top defaulters by months_overdue. months_overdue = current month -
  // last_paid_through (in months), only counted for active accounts;
  // null last_paid_through is treated as "max" by sorting it last so
  // operator-set null doesn't dominate the top-10.
  const monthsBetween = (from: string, to: string): number => {
    const [fy, fm] = from.split('-').map(Number);
    const [ty, tm] = to.split('-').map(Number);
    if (!fy || !fm || !ty || !tm) return 0;
    return (ty - fy) * 12 + (tm - fm);
  };
  const topDefaulters = accounts
    .filter((a) => a.is_active && a.last_paid_through != null)
    .map((a) => ({
      name: a.name,
      rdNumber: a.rd_number,
      monthsOverdue: Math.max(0, monthsBetween(a.last_paid_through ?? currentMonth, currentMonth)),
    }))
    .filter((a) => a.monthsOverdue > 0)
    .sort((a, b) => b.monthsOverdue - a.monthsOverdue)
    .slice(0, 10);

  // Amount histogram. Buckets are clamped to a small set of human
  // labels so the chart legend stays readable; widening later is a
  // single-array edit.
  const buckets: Array<{ label: string; lo: number; hi: number }> = [
    { label: '< 500', lo: 0, hi: 499 },
    { label: '500–999', lo: 500, hi: 999 },
    { label: '1k–2k', lo: 1000, hi: 1999 },
    { label: '2k–5k', lo: 2000, hi: 4999 },
    { label: '5k+', lo: 5000, hi: Number.POSITIVE_INFINITY },
  ];
  const amountHistogram = buckets.map((b) => ({
    bucket: b.label,
    count: accounts.filter((a) => a.monthly_amount >= b.lo && a.monthly_amount <= b.hi).length,
  }));

  return {
    totalAccounts,
    activeAccounts,
    inactiveAccounts,
    defaulterCount,
    totalSessions: sessions.length,
    totalRdScans: rdNumbers.length,
    totalCollectedThisMonth,
    activeDevices,
    totalDevices,
    recentActivity: activityRes,
    monthlySessionCounts,
    monthlyScansCollected,
    sourceBreakdown,
    topDefaulters,
    amountHistogram,
  };
}
