import { supabase } from './supabase';
import { fetchActivityFeed, maskRdNumber, requireOwnerId } from './queries';
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
 * Range semantics: see [DashboardRange]. All time-boundary computations
 * use UTC to match the way the phone stamps `end_time` and
 * `scanned_at`. The dashboard tolerates a small DST/timezone drift on
 * the boundary day for "this month" semantics.
 */

/**
 * Range type — extensible algebraic union so the dashboard can support
 * preset ranges, the current calendar month, and arbitrary custom
 * ranges (paste in for reporting) without callers having to thread
 * separate parameters. Defense-in-depth: any new branch added here
 * MUST be handled in [resolveDateBounds] AND [resolveMonthSequence]
 * (TypeScript exhaustiveness via the `never` default catches it).
 */
export type DashboardRange =
  | 3
  | 6
  | 12
  | null
  | { kind: 'current-month' }
  | { kind: 'custom'; fromIso: string; toIso: string };

export interface MonthlyMoneyPoint {
  month: string;
  /** Sum of monthly_amount across all rd_numbers scans in the month, weighted by months_paid. */
  amount: number;
  /** Same but only for defaulter rows (months_paid > 1). */
  defaulterAmount: number;
}

export interface CurrentVsDefaultBreakdown {
  /** Active accounts paid up to or beyond current month. */
  currentCount: number;
  /** Active accounts whose last_paid_through is null OR < current month. */
  defaultCount: number;
  /** Sum of monthly_amount across currentCount accounts. */
  currentAmount: number;
  /** Sum of monthly_amount across defaultCount accounts. */
  defaultAmount: number;
}

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
  /** Sum of monthly_amount across ALL active accounts. */
  totalAccountAmount: number;
  /** Real average — sum(monthly_amount) / count(active accounts). 0 when no active accounts. */
  averageMonthlyAmount: number;
  currentVsDefault: CurrentVsDefaultBreakdown;
  recentActivity: ActivityRow[];
  monthlySessionCounts: Array<{ month: string; count: number }>;
  monthlyScansCollected: Array<{ month: string; collected: number; defaulters: number }>;
  monthlyMoneyCollected: MonthlyMoneyPoint[];
  sourceBreakdown: Array<{ source: 'MANUAL' | 'CSV'; count: number }>;
  topDefaulters: Array<{ name: string; rdNumber: string; monthsOverdue: number; maskedRdNumber: string }>;
  amountHistogram: Array<{ bucket: string; count: number }>;
  /** Earliest scan session end_time seen, used by the dashboard caption + PDF report header. ISO or null. */
  earliestSessionAt: string | null;
}

interface DateBounds {
  /** Lower bound ISO (inclusive). null = "all time". */
  startIso: string | null;
  /** Upper bound ISO (inclusive). null = "now". */
  endIso: string | null;
}

/**
 * Resolves a DashboardRange into ISO timestamp bounds. Encoded as a
 * pure function so unit tests (future) can hit all 5 branches without
 * stubbing supabase. The function NEVER hits the network.
 */
function resolveDateBounds(range: DashboardRange): DateBounds {
  if (range === null) return { startIso: null, endIso: null };
  const now = new Date();
  if (range === 3 || range === 6 || range === 12) {
    const start = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - (range - 1), 1));
    return { startIso: start.toISOString(), endIso: null };
  }
  if (range.kind === 'current-month') {
    const start = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1));
    return { startIso: start.toISOString(), endIso: null };
  }
  // 'custom' — operator-typed YYYY-MM-DD strings, already validated by
  // the dialog. We pin to start-of-day UTC for "from" and end-of-day
  // UTC for "to" so the bounds INCLUDE both endpoints rather than
  // silently excluding the last day.
  const startIso = new Date(`${range.fromIso}T00:00:00.000Z`).toISOString();
  const endIso = new Date(`${range.toIso}T23:59:59.999Z`).toISOString();
  return { startIso, endIso };
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
 * Generates the complete month-key sequence (ascending) the dashboard
 * charts must render. The sequence is the union of:
 *   1. Months in the explicit range bounds.
 *   2. For range==null ("all time"), the span from `earliestIso` to
 *      now — bug fix from QC R1 oracle bg_089962d5: the previous
 *      version capped All-Time at 12 months which silently truncated
 *      history for any project older than a year. If `earliestIso` is
 *      null (no sessions yet), falls back to current month only so the
 *      chart still renders an empty bucket instead of vanishing.
 *
 * Guard: caps at 60 months (5 years) so a malformed earliestIso can't
 * produce a 10,000-bucket chart. Realistic operator scale is sub-decade.
 */
function resolveMonthSequence(range: DashboardRange, earliestIso: string | null): string[] {
  const now = new Date();
  const nowKey = monthKey(now.toISOString());
  let span: number;
  if (range === null) {
    if (earliestIso == null) return [nowKey];
    const e = new Date(earliestIso);
    span = (now.getUTCFullYear() - e.getUTCFullYear()) * 12 + (now.getUTCMonth() - e.getUTCMonth()) + 1;
    if (span < 1) span = 1;
    if (span > 60) span = 60;
  } else if (range === 3 || range === 6 || range === 12) {
    span = range;
  } else if (range.kind === 'current-month') {
    span = 1;
  } else {
    const from = new Date(`${range.fromIso}T00:00:00.000Z`);
    const to = new Date(`${range.toIso}T00:00:00.000Z`);
    span = (to.getUTCFullYear() - from.getUTCFullYear()) * 12 + (to.getUTCMonth() - from.getUTCMonth()) + 1;
    if (span < 1) span = 1;
    if (span > 60) span = 60;
  }
  const seq: string[] = [];
  // For custom range we anchor on `to`, else on `now`.
  const anchor = (range !== null && typeof range === 'object' && range.kind === 'custom')
    ? new Date(`${range.toIso}T00:00:00.000Z`)
    : now;
  for (let i = span - 1; i >= 0; i -= 1) {
    const d = new Date(Date.UTC(anchor.getUTCFullYear(), anchor.getUTCMonth() - i, 1));
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
  const ownerId = await requireOwnerId();
  const { startIso, endIso } = resolveDateBounds(range);

  // Fire every independent read in parallel so the dashboard's
  // perceived latency is the slowest single query, not the sum.
  // Caller-side realtime invalidation re-runs all of these together,
  // so they share a single cache-bust unit. RLS already restricts to
  // the owner but `.eq('owner_id', ownerId)` is defense-in-depth per
  // QC R1 M1 — protects against any future RLS misconfiguration.
  const sessionsQuery = supabase
    .from('scan_sessions')
    .select('end_time')
    .eq('owner_id', ownerId)
    .is('deleted_at', null);
  if (startIso != null) sessionsQuery.gte('end_time', startIso);
  if (endIso != null) sessionsQuery.lte('end_time', endIso);

  const rdQuery = supabase
    .from('rd_numbers')
    .select('scanned_at, months_paid, number')
    .eq('owner_id', ownerId)
    .is('deleted_at', null);
  if (startIso != null) rdQuery.gte('scanned_at', startIso);
  if (endIso != null) rdQuery.lte('scanned_at', endIso);

  const earliestQuery = supabase
    .from('scan_sessions')
    .select('end_time')
    .eq('owner_id', ownerId)
    .is('deleted_at', null)
    .order('end_time', { ascending: true })
    .limit(1);

  const [
    accountsRes,
    devicesRes,
    sessionsRes,
    rdRes,
    earliestRes,
    activityRes,
  ] = await Promise.all([
    supabase
      .from('rd_accounts')
      .select('rd_number, name, monthly_amount, last_paid_through, is_active, source')
      .eq('owner_id', ownerId)
      .is('deleted_at', null),
    supabase
      .from('devices')
      .select('last_seen_at')
      .eq('owner_id', ownerId)
      .is('deleted_at', null),
    sessionsQuery,
    rdQuery,
    earliestQuery,
    fetchActivityFeed({ limit: 8 }),
  ]);

  if (accountsRes.error) throw accountsRes.error;
  if (devicesRes.error) throw devicesRes.error;
  if (sessionsRes.error) throw sessionsRes.error;
  if (rdRes.error) throw rdRes.error;
  if (earliestRes.error) throw earliestRes.error;

  const accounts = (accountsRes.data ?? []) as RdAccountStub[];
  const devices = (devicesRes.data ?? []) as DeviceStub[];
  const sessions = (sessionsRes.data ?? []) as SessionStub[];
  const rdNumbers = (rdRes.data ?? []) as RdNumberStub[];
  const earliestSessionAt = ((earliestRes.data ?? []) as SessionStub[])[0]?.end_time ?? null;

  const seq = resolveMonthSequence(range, earliestSessionAt);

  // Account-derived headline numbers.
  const totalAccounts = accounts.length;
  const activeAccounts = accounts.filter((a) => a.is_active).length;
  const inactiveAccounts = totalAccounts - activeAccounts;
  const activeAccountList = accounts.filter((a) => a.is_active);
  const totalAccountAmount = activeAccountList.reduce((s, a) => s + a.monthly_amount, 0);
  const averageMonthlyAmount = activeAccountList.length === 0
    ? 0
    : Math.round(totalAccountAmount / activeAccountList.length);

  // Defaulter count from rd_numbers months_paid > 1 — same definition
  // the phone bell and the Activity feed use. Counted as DISTINCT
  // rd_number because the same defaulter scanned across multiple
  // sessions should not multi-count toward the dashboard headline.
  const defaulterSet = new Set<string>();
  for (const r of rdNumbers) {
    if (r.months_paid > 1) defaulterSet.add(r.number);
  }
  const defaulterCount = defaulterSet.size;

  const currentMonth = monthKey(new Date().toISOString());

  // Current vs default breakdown — uses last_paid_through across the
  // ACTIVE account roster, not the rd_numbers scans. "current" means
  // operator has acknowledged payment up to (or beyond) this month per
  // spec D22 'paper book is truth'. "default" means either no payment
  // recorded yet (null) or last_paid_through < current month.
  let currentCount = 0;
  let defaultCount = 0;
  let currentAmount = 0;
  let defaultAmount = 0;
  for (const a of activeAccountList) {
    if (a.last_paid_through != null && a.last_paid_through >= currentMonth) {
      currentCount += 1;
      currentAmount += a.monthly_amount;
    } else {
      defaultCount += 1;
      defaultAmount += a.monthly_amount;
    }
  }
  const currentVsDefault: CurrentVsDefaultBreakdown = {
    currentCount,
    defaultCount,
    currentAmount,
    defaultAmount,
  };

  // Active devices = last_seen_at within 5 minutes. 5 min matches the
  // online-pill threshold used on the Devices page (spec §15.4).
  const FIVE_MIN_MS = 5 * 60 * 1000;
  const nowMs = Date.now();
  let activeDevices = 0;
  for (const d of devices) {
    if (d.last_seen_at != null) {
      const last = Date.parse(d.last_seen_at);
      if (!Number.isNaN(last) && nowMs - last <= FIVE_MIN_MS) activeDevices += 1;
    }
  }
  const totalDevices = devices.length;

  // Build per-month maps so we walk the data once and look up by key.
  const sessionMonthCounts = new Map<string, number>();
  const scanMonthCollected = new Map<string, number>();
  const scanMonthDefaulters = new Map<string, number>();
  const moneyMonthTotal = new Map<string, number>();
  const moneyMonthDefaulter = new Map<string, number>();
  for (const m of seq) {
    sessionMonthCounts.set(m, 0);
    scanMonthCollected.set(m, 0);
    scanMonthDefaulters.set(m, 0);
    moneyMonthTotal.set(m, 0);
    moneyMonthDefaulter.set(m, 0);
  }
  for (const s of sessions) {
    const key = monthKey(s.end_time);
    if (sessionMonthCounts.has(key)) {
      sessionMonthCounts.set(key, (sessionMonthCounts.get(key) ?? 0) + 1);
    }
  }

  // monthly_amount lookup by rd_number for money-weighted aggregates.
  // Built from accounts (active + inactive — the row may have been
  // toggled inactive after the scan but the money still landed).
  const amountByRdNumber = new Map<string, number>();
  for (const a of accounts) amountByRdNumber.set(a.rd_number, a.monthly_amount);

  for (const r of rdNumbers) {
    const key = monthKey(r.scanned_at);
    if (!scanMonthCollected.has(key)) continue;
    scanMonthCollected.set(key, (scanMonthCollected.get(key) ?? 0) + 1);
    const amt = amountByRdNumber.get(r.number) ?? 0;
    const weighted = amt * r.months_paid;
    moneyMonthTotal.set(key, (moneyMonthTotal.get(key) ?? 0) + weighted);
    if (r.months_paid > 1) {
      scanMonthDefaulters.set(key, (scanMonthDefaulters.get(key) ?? 0) + 1);
      moneyMonthDefaulter.set(key, (moneyMonthDefaulter.get(key) ?? 0) + weighted);
    }
  }

  // QC R2 H1 — weighted money this month, not just monthly_amount of
  // paid-up accounts. A defaulter catching up multiple months in a
  // single session lands `monthly_amount × months_paid` in the cloud
  // total for this period; the old formula would have under-counted
  // those catch-ups and over-stated current-period collections only.
  // Reads from the already-populated map; if currentMonth isn't in
  // `seq` (range excludes current period), the lookup returns 0.
  const totalCollectedThisMonth = moneyMonthTotal.get(currentMonth) ?? 0;

  const monthlySessionCounts = seq.map((m) => ({
    month: m,
    count: sessionMonthCounts.get(m) ?? 0,
  }));
  const monthlyScansCollected = seq.map((m) => ({
    month: m,
    collected: scanMonthCollected.get(m) ?? 0,
    defaulters: scanMonthDefaulters.get(m) ?? 0,
  }));
  const monthlyMoneyCollected: MonthlyMoneyPoint[] = seq.map((m) => ({
    month: m,
    amount: moneyMonthTotal.get(m) ?? 0,
    defaulterAmount: moneyMonthDefaulter.get(m) ?? 0,
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

  // Top defaulters by months_overdue across the ACTIVE roster only.
  // null last_paid_through is excluded — operator hasn't set a baseline
  // yet, so we can't measure overdue. Numeric guards on monthsBetween
  // log a warn on malformed input rather than silently returning 0
  // (QC R1 H3 — silent-zero made bad data look like clean data).
  const monthsBetween = (from: string, to: string): number => {
    const [fy, fm] = from.split('-').map(Number);
    const [ty, tm] = to.split('-').map(Number);
    if (!fy || !fm || !ty || !tm) {
      // console.warn instead of throw so a single bad row doesn't
      // crash the whole dashboard render; surfaces the bug in
      // operator devtools.
      console.warn('[dashboard] malformed last_paid_through detected', { from, to });
      return 0;
    }
    return (ty - fy) * 12 + (tm - fm);
  };
  const topDefaulters = activeAccountList
    .filter((a) => a.last_paid_through != null)
    .map((a) => {
      const months = Math.max(0, monthsBetween(a.last_paid_through ?? '', currentMonth));
      return {
        name: a.name,
        rdNumber: a.rd_number,
        maskedRdNumber: maskRdNumber(a.rd_number),
        monthsOverdue: months,
      };
    })
    .filter((a) => a.monthsOverdue > 0)
    .sort((a, b) => b.monthsOverdue - a.monthsOverdue)
    .slice(0, 10);

  // Amount histogram. Buckets are clamped to a small set of human
  // labels so the chart legend stays readable; widening later is a
  // single-array edit. Inclusive lo/hi on integer amounts → exact
  // equivalent to `< 500 / 500..999 / 1000..1999 / 2000..4999 / >= 5000`.
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
    totalAccountAmount,
    averageMonthlyAmount,
    currentVsDefault,
    recentActivity: activityRes,
    monthlySessionCounts,
    monthlyScansCollected,
    monthlyMoneyCollected,
    sourceBreakdown,
    topDefaulters,
    amountHistogram,
    earliestSessionAt,
  };
}
