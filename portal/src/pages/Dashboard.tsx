import { lazy, Suspense, useCallback, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Line,
  LineChart,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import {
  ArrowDownRight,
  ArrowUpRight,
  Banknote,
  Boxes,
  Cpu,
  Download,
  Inbox,
  IndianRupee,
  Layers,
  ScanLine,
  ShieldCheck,
  ShieldAlert,
  Sparkles,
  UserMinus,
  Users,
  Wallet,
} from 'lucide-react';
import { PageHeader } from '../components/PageHeader';
import { SkeletonCard } from '../components/Loader';
import {
  fetchDashboardStats,
  type DashboardRange,
  type DashboardStats,
} from '../lib/dashboardQueries';
import { formatCompactCurrency, formatDateTime, formatNumber, formatRelativeTime } from '../lib/format';
import { useDocumentTitle } from '../lib/useDocumentTitle';
import type { ActivityKind } from '../types/db';

const ExportPdfDialog = lazy(() =>
  import('../components/ExportPdfDialog').then((m) => ({ default: m.ExportPdfDialog })),
);

/**
 * Operator dashboard — home page of the portal. Composes the full
 * picture from [fetchDashboardStats] into a 14-widget grid:
 *
 *   1. KPI row: total accounts, defaulters, collected this month,
 *      sessions in range, scans in range, active devices, avg ticket,
 *      account source mix, total book amount, current count vs default
 *      count, current amount vs default amount.
 *   2. Money charts: monthly money collected (area), current vs default
 *      (stacked bar).
 *   3. Activity trends: monthly session counts (area), monthly scans
 *      with defaulter overlay (line).
 *   4. Distribution: account source breakdown (donut), monthly amount
 *      histogram (bar).
 *   5. Top defaulters table (top 10, masked).
 *   6. Recent activity strip linking into the Activity feed.
 *
 * Time range selector: 3M / 6M / 12M / All / Current month / Custom.
 * "Now" KPIs (active accounts, active devices, collected this month,
 * book amount) ignore the range; time-series widgets honor it.
 */

interface PresetOption {
  key: '3M' | '6M' | '12M' | 'All' | 'Month';
  label: string;
  range: DashboardRange;
}

const PRESETS: PresetOption[] = [
  { key: 'Month', label: 'This month', range: { kind: 'current-month' } },
  { key: '3M', label: '3M', range: 3 },
  { key: '6M', label: '6M', range: 6 },
  { key: '12M', label: '12M', range: 12 },
  { key: 'All', label: 'All', range: null },
];

function rangeKey(r: DashboardRange): string {
  if (r === null) return 'all';
  if (typeof r === 'number') return `m${r}`;
  if (r.kind === 'current-month') return 'cm';
  return `cu:${r.fromIso}:${r.toIso}`;
}

export function DashboardPage() {
  useDocumentTitle('Dashboard');
  const [range, setRange] = useState<DashboardRange>(12);
  const [exportOpen, setExportOpen] = useState(false);
  const { session } = useDashboardSession();

  const { data, isPending, isError, error, refetch, isFetching } = useQuery({
    queryKey: ['dashboard', rangeKey(range)],
    queryFn: () => fetchDashboardStats(range),
    enabled: !!session,
    staleTime: 30_000,
  });

  if (isPending) return <DashboardSkeleton />;
  if (isError) {
    const msg = error instanceof Error ? error.message : 'Failed to load dashboard.';
    return (
      <div className="space-y-6">
        <PageHeader title="Dashboard" subtitle="Overview" />
        <div className="rounded-2xl border border-danger/20 bg-danger/5 p-6 text-center">
          <p className="text-sm font-medium text-danger">{msg}</p>
          <button
            type="button"
            onClick={() => void refetch()}
            className="mt-3 rounded-pill border border-danger/30 px-3.5 py-1.5 text-xs font-medium text-danger hover:bg-danger/10"
          >
            Retry
          </button>
        </div>
      </div>
    );
  }

  return (
    <>
      <DashboardBody
        stats={data}
        range={range}
        onRangeChange={setRange}
        isFetching={isFetching}
        onExportClick={() => setExportOpen(true)}
      />
      {exportOpen && (
        <Suspense fallback={null}>
          <ExportPdfDialog
            stats={data}
            range={range}
            onClose={() => setExportOpen(false)}
          />
        </Suspense>
      )}
    </>
  );
}

/**
 * Tiny wrapper around supabase.auth.getSession that only reads the
 * cached value the AuthProvider already maintains via onAuthStateChange.
 * Avoids importing the AuthProvider directly (circular). useState lazy
 * init keeps it sync.
 */
function useDashboardSession(): { session: boolean } {
  // Cheap reactive marker — the AuthProvider in main.tsx puts auth into
  // a context that other queries already consume. The dashboard only
  // needs to know "is there an authenticated owner?" because RLS-only
  // queries below also throw via requireOwnerId on missing auth. Treat
  // the existence of a non-empty localStorage `sb-…-auth-token` as
  // session-present so we don't paint the dashboard before login lands.
  const present = typeof window !== 'undefined'
    && Object.keys(window.localStorage).some((k) => k.startsWith('sb-') && k.endsWith('-auth-token')
      && (window.localStorage.getItem(k) ?? '').length > 2);
  return { session: present };
}

interface DashboardBodyProps {
  stats: DashboardStats;
  range: DashboardRange;
  onRangeChange: (r: DashboardRange) => void;
  isFetching: boolean;
  onExportClick: () => void;
}

function DashboardBody({ stats, range, onRangeChange, isFetching, onExportClick }: DashboardBodyProps) {
  const rangeLabel = describeRange(range);
  return (
    <div className="space-y-8">
      <PageHeader
        title="Dashboard"
        subtitle={`${rangeLabel}${isFetching ? ' · refreshing' : ''}`}
        action={
          <div className="flex flex-wrap items-center gap-2">
            <RangeSelector value={range} onChange={onRangeChange} />
            <button
              type="button"
              onClick={onExportClick}
              className="inline-flex items-center gap-1.5 rounded-pill bg-primary px-3.5 py-2 text-xs font-semibold text-white shadow-card transition-colors hover:bg-primary-dark"
            >
              <Download className="h-3.5 w-3.5" aria-hidden="true" />
              Export PDF
            </button>
          </div>
        }
      />

      <KpiRow stats={stats} />

      <div className="grid gap-6 lg:grid-cols-3">
        <MoneyTrendCard data={stats.monthlyMoneyCollected} className="lg:col-span-2" />
        <CurrentVsDefaultCard breakdown={stats.currentVsDefault} />
      </div>

      <div className="grid gap-6 lg:grid-cols-3">
        <SessionTrendCard data={stats.monthlySessionCounts} className="lg:col-span-2" />
        <SourceDonutCard data={stats.sourceBreakdown} />
      </div>

      <div className="grid gap-6 lg:grid-cols-3">
        <ScansTrendCard data={stats.monthlyScansCollected} className="lg:col-span-2" />
        <AmountHistogramCard data={stats.amountHistogram} />
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <TopDefaultersCard rows={stats.topDefaulters} />
        <RecentActivityCard rows={stats.recentActivity} />
      </div>
    </div>
  );
}

// QC R2 M2/M3 — describeRange formats custom dates using en-IN locale
// so operators see '15 Mar 2025' instead of the raw ISO '2025-03-15'.
// Reused by the dashboard subtitle AND the PDF export subtitle so they
// stay in sync without divergent formatters.
const CUSTOM_RANGE_FMT = new Intl.DateTimeFormat('en-IN', {
  day: '2-digit',
  month: 'short',
  year: 'numeric',
});
function formatCustomDate(iso: string): string {
  const d = new Date(`${iso}T00:00:00`);
  if (Number.isNaN(d.getTime())) return iso;
  return CUSTOM_RANGE_FMT.format(d);
}

function formatLocalDateIso(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

function describeRange(range: DashboardRange): string {
  if (range === null) return 'All time';
  if (range === 3) return 'Last 3 months';
  if (range === 6) return 'Last 6 months';
  if (range === 12) return 'Last 12 months';
  if (range.kind === 'current-month') return 'Current month';
  return `${formatCustomDate(range.fromIso)} to ${formatCustomDate(range.toIso)}`;
}

function RangeSelector({
  value,
  onChange,
}: {
  value: DashboardRange;
  onChange: (r: DashboardRange) => void;
}) {
  const isCustom = typeof value === 'object' && value !== null && value.kind === 'custom';
  const [customOpen, setCustomOpen] = useState(isCustom);
  // QC R2 H1+H2 — todayIso/defaultFromIso must be LOCAL YYYY-MM-DD,
  // not UTC. toISOString() shifts to UTC and the user in IST after
  // 5:30 AM sees yesterday's date as "today's max", blocking them
  // from selecting their own current local date in the picker. Build
  // the string from local getters to keep min/max bounds aligned with
  // the operator's wall clock.
  const todayIso = useMemo(() => formatLocalDateIso(new Date()), []);
  const defaultFromIso = useMemo(() => {
    const d = new Date();
    d.setMonth(d.getMonth() - 1);
    return formatLocalDateIso(d);
  }, []);
  const [fromIso, setFromIso] = useState<string>(isCustom ? value.fromIso : defaultFromIso);
  const [toIso, setToIso] = useState<string>(isCustom ? value.toIso : todayIso);

  // Roving tabindex pattern: arrow keys move focus across chips while
  // Tab moves focus past the whole group. Required for WCAG 2.1.1 on
  // the dashboard's primary control surface.
  const groupRef = useRef<HTMLDivElement | null>(null);
  const focusSibling = useCallback((current: HTMLElement, dir: 1 | -1) => {
    const buttons = Array.from(groupRef.current?.querySelectorAll<HTMLButtonElement>('button[data-chip="true"]') ?? []);
    const idx = buttons.indexOf(current as HTMLButtonElement);
    if (idx === -1) return;
    const next = buttons[(idx + dir + buttons.length) % buttons.length];
    next?.focus();
  }, []);

  const matchPreset = (preset: PresetOption): boolean => {
    if (preset.range === null && value === null) return true;
    if (typeof preset.range === 'number' && preset.range === value) return true;
    if (
      typeof preset.range === 'object' &&
      preset.range !== null &&
      typeof value === 'object' &&
      value !== null &&
      preset.range.kind === 'current-month' &&
      value.kind === 'current-month'
    ) {
      return true;
    }
    return false;
  };

  return (
    <div className="flex flex-wrap items-center gap-2">
      <div
        ref={groupRef}
        role="group"
        aria-label="Dashboard date range"
        className="inline-flex flex-wrap items-center rounded-pill border border-surface-border bg-surface p-0.5 shadow-card"
      >
        {PRESETS.map((opt) => {
          const active = matchPreset(opt);
          return (
            <button
              key={opt.key}
              data-chip="true"
              type="button"
              onClick={() => {
                onChange(opt.range);
                setCustomOpen(false);
              }}
              aria-pressed={active}
              tabIndex={active ? 0 : -1}
              onKeyDown={(e) => {
                if (e.key === 'ArrowRight' || e.key === 'ArrowDown') {
                  e.preventDefault();
                  focusSibling(e.currentTarget, 1);
                } else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') {
                  e.preventDefault();
                  focusSibling(e.currentTarget, -1);
                }
              }}
              className={[
                'min-h-[40px] rounded-pill px-3.5 py-2 text-xs font-semibold transition-colors',
                active
                  ? 'bg-primary text-white shadow-card'
                  : 'text-ink-secondary hover:text-ink-primary',
              ].join(' ')}
            >
              {opt.label}
            </button>
          );
        })}
        <button
          data-chip="true"
          type="button"
          onClick={() => setCustomOpen((v) => !v)}
          aria-pressed={isCustom}
          aria-expanded={customOpen}
          tabIndex={isCustom ? 0 : -1}
          onKeyDown={(e) => {
            if (e.key === 'ArrowRight' || e.key === 'ArrowDown') {
              e.preventDefault();
              focusSibling(e.currentTarget, 1);
            } else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') {
              e.preventDefault();
              focusSibling(e.currentTarget, -1);
            }
          }}
          className={[
            'min-h-[40px] rounded-pill px-3.5 py-2 text-xs font-semibold transition-colors',
            isCustom
              ? 'bg-primary text-white shadow-card'
              : 'text-ink-secondary hover:text-ink-primary',
          ].join(' ')}
        >
          Custom
        </button>
      </div>
      {customOpen && (
        <div className="inline-flex flex-wrap items-center gap-2 rounded-pill border border-surface-border bg-surface px-2 py-1 shadow-card">
          <label className="flex items-center gap-1 text-[11px] font-medium text-ink-secondary">
            From
            <input
              type="date"
              value={fromIso}
              max={toIso < todayIso ? toIso : todayIso}
              onChange={(e) => setFromIso(e.target.value)}
              className="rounded-md border border-surface-border bg-surface px-2 py-1 text-xs"
            />
          </label>
          <label className="flex items-center gap-1 text-[11px] font-medium text-ink-secondary">
            To
            <input
              type="date"
              value={toIso}
              min={fromIso}
              max={todayIso}
              onChange={(e) => setToIso(e.target.value)}
              className="rounded-md border border-surface-border bg-surface px-2 py-1 text-xs"
            />
          </label>
          <button
            type="button"
            disabled={!fromIso || !toIso || fromIso > toIso || toIso > todayIso}
            onClick={() => {
              onChange({ kind: 'custom', fromIso, toIso });
            }}
            className="min-h-[36px] rounded-pill bg-primary px-3.5 py-1.5 text-[11px] font-semibold text-white shadow-card transition-colors hover:bg-primary-dark disabled:cursor-not-allowed disabled:opacity-50"
          >
            Apply
          </button>
        </div>
      )}
    </div>
  );
}

interface KpiCardProps {
  title: string;
  value: string;
  subtitle?: string | undefined;
  icon: React.ReactNode;
  tone: 'primary' | 'mint' | 'coral' | 'warn' | 'neutral';
  delta?: { direction: 'up' | 'down'; label: string } | undefined;
}

const TONE_CLASSES: Record<KpiCardProps['tone'], { bg: string; ink: string }> = {
  primary: { bg: 'bg-primary/10', ink: 'text-primary-dark' },
  mint: { bg: 'bg-accent-mint/15', ink: 'text-accent-mint-ink' },
  // text-amber-700 (#B45309 on white = 4.91:1) replaces the prior
  // text-warn (#F59E0B on white = 1.76:1) — QC R1 M4 contrast fix.
  coral: { bg: 'bg-accent-coral/15', ink: 'text-accent-coral' },
  warn: { bg: 'bg-amber-100', ink: 'text-amber-700' },
  neutral: { bg: 'bg-surface-alt', ink: 'text-ink-primary' },
};

function KpiCard({ title, value, subtitle, icon, tone, delta }: KpiCardProps) {
  const t = TONE_CLASSES[tone];
  return (
    <div className="group relative overflow-hidden rounded-2xl border border-surface-border bg-surface p-4 shadow-card transition-shadow hover:shadow-elevated">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <p className="text-[11px] font-semibold uppercase tracking-wider text-ink-muted">
            {title}
          </p>
          <p
            className="mt-2 truncate text-xl font-semibold tracking-tight text-ink-primary sm:text-2xl"
            aria-label={`${title}: ${value}`}
          >
            {value}
          </p>
          {subtitle && (
            <p className="mt-1 text-xs text-ink-secondary">{subtitle}</p>
          )}
        </div>
        <div className={`grid h-10 w-10 shrink-0 place-items-center rounded-xl ${t.bg} ${t.ink}`}>
          {icon}
        </div>
      </div>
      {delta && (
        <div
          className={[
            'mt-3 inline-flex items-center gap-1 rounded-pill px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide',
            delta.direction === 'up'
              ? 'bg-accent-mint/15 text-accent-mint-ink'
              : 'bg-accent-coral/15 text-accent-coral',
          ].join(' ')}
        >
          {delta.direction === 'up' ? (
            <ArrowUpRight className="h-3 w-3" />
          ) : (
            <ArrowDownRight className="h-3 w-3" />
          )}
          {delta.label}
        </div>
      )}
    </div>
  );
}

function KpiRow({ stats }: { stats: DashboardStats }) {
  // Compute MoM delta on session count using the last two contiguous
  // months of the monthlySessionCounts array. Zero-denominator returns
  // undefined so the card doesn't render meaningless percentages on
  // fresh installs.
  const sessionsDelta = useMemo<KpiCardProps['delta'] | undefined>(() => {
    const arr = stats.monthlySessionCounts;
    if (arr.length < 2) return undefined;
    const last = arr[arr.length - 1].count;
    const prev = arr[arr.length - 2].count;
    if (prev === 0 && last === 0) return undefined;
    if (prev === 0) return { direction: 'up', label: 'New activity' };
    const pct = Math.round(((last - prev) / prev) * 100);
    if (pct === 0) return undefined;
    return {
      direction: pct >= 0 ? 'up' : 'down',
      label: `${pct >= 0 ? '+' : ''}${pct}% MoM`,
    };
  }, [stats.monthlySessionCounts]);

  const moneyDelta = useMemo<KpiCardProps['delta'] | undefined>(() => {
    const arr = stats.monthlyMoneyCollected;
    if (arr.length < 2) return undefined;
    const last = arr[arr.length - 1].amount;
    const prev = arr[arr.length - 2].amount;
    if (prev === 0 && last === 0) return undefined;
    if (prev === 0) return { direction: 'up', label: 'New collections' };
    const pct = Math.round(((last - prev) / prev) * 100);
    if (pct === 0) return undefined;
    return {
      direction: pct >= 0 ? 'up' : 'down',
      label: `${pct >= 0 ? '+' : ''}${pct}% MoM`,
    };
  }, [stats.monthlyMoneyCollected]);

  return (
    <div className="grid grid-cols-2 gap-4 md:grid-cols-3 xl:grid-cols-4">
      <KpiCard
        title="Total accounts"
        value={formatNumber(stats.totalAccounts)}
        subtitle={`${formatNumber(stats.activeAccounts)} active · ${formatNumber(stats.inactiveAccounts)} inactive`}
        icon={<Users className="h-5 w-5" />}
        tone="primary"
      />
      <KpiCard
        title="Defaulters"
        value={formatNumber(stats.defaulterCount)}
        subtitle="paid multiple months (catching up)"
        icon={<UserMinus className="h-5 w-5" />}
        tone="warn"
      />
      <KpiCard
        title="Collected this month"
        value={formatCompactCurrency(stats.totalCollectedThisMonth)}
        subtitle="weighted by months_paid"
        icon={<Wallet className="h-5 w-5" />}
        tone="mint"
      />
      <KpiCard
        title="Book amount"
        value={formatCompactCurrency(stats.totalAccountAmount)}
        subtitle="sum of monthly amounts (active)"
        icon={<IndianRupee className="h-5 w-5" />}
        tone="primary"
      />
      <KpiCard
        title="Money collected (range)"
        value={formatCompactCurrency(stats.monthlyMoneyCollected.reduce((s, m) => s + m.amount, 0))}
        subtitle="scans weighted by months_paid"
        icon={<Banknote className="h-5 w-5" />}
        tone="mint"
        delta={moneyDelta}
      />
      <KpiCard
        title="Sessions"
        value={formatNumber(stats.totalSessions)}
        subtitle="in selected range"
        icon={<Layers className="h-5 w-5" />}
        tone="neutral"
        delta={sessionsDelta}
      />
      <KpiCard
        title="Scans"
        value={formatNumber(stats.totalRdScans)}
        subtitle="RD numbers in range"
        icon={<ScanLine className="h-5 w-5" />}
        tone="neutral"
      />
      <KpiCard
        title="Active devices"
        value={`${stats.activeDevices} / ${stats.totalDevices}`}
        subtitle="online in last 5 minutes"
        icon={<Cpu className="h-5 w-5" />}
        tone={stats.activeDevices > 0 ? 'mint' : 'neutral'}
      />
      <KpiCard
        title="Avg ticket"
        value={formatCompactCurrency(stats.averageMonthlyAmount)}
        subtitle="real average across active"
        icon={<Sparkles className="h-5 w-5" />}
        tone="primary"
      />
      <KpiCard
        title="Current vs default (accounts)"
        value={`${formatNumber(stats.currentVsDefault.currentCount)} / ${formatNumber(stats.currentVsDefault.defaultCount)}`}
        subtitle="current · default (incl. never paid)"
        icon={<ShieldCheck className="h-5 w-5" />}
        tone="coral"
      />
      <KpiCard
        title="Current vs default (₹)"
        value={`${formatCompactCurrency(stats.currentVsDefault.currentAmount)} / ${formatCompactCurrency(stats.currentVsDefault.defaultAmount)}`}
        subtitle="monthly amounts (incl. never paid)"
        icon={<ShieldAlert className="h-5 w-5" />}
        tone="warn"
      />
      <KpiCard
        title="Account mix"
        value={`${formatNumber(stats.sourceBreakdown.find((s) => s.source === 'CSV')?.count ?? 0)} CSV`}
        subtitle={`${formatNumber(stats.sourceBreakdown.find((s) => s.source === 'MANUAL')?.count ?? 0)} manual`}
        icon={<Boxes className="h-5 w-5" />}
        tone="coral"
      />
    </div>
  );
}

interface ChartCardProps {
  title: string;
  subtitle?: string | undefined;
  children: React.ReactNode;
  className?: string | undefined;
  ariaLabel: string;
}

function ChartCard({ title, subtitle, children, className, ariaLabel }: ChartCardProps) {
  return (
    <figure
      role="img"
      aria-label={ariaLabel}
      className={[
        'rounded-2xl border border-surface-border bg-surface p-5 shadow-card',
        className ?? '',
      ].join(' ')}
    >
      <figcaption className="mb-4 flex items-end justify-between gap-3">
        <div>
          <h3 className="text-sm font-semibold text-ink-primary">{title}</h3>
          {subtitle && (
            <p className="mt-0.5 text-xs text-ink-secondary">{subtitle}</p>
          )}
        </div>
      </figcaption>
      {/* QC R2 M1 — figure aria-label takes precedence over the visible
          figcaption per the ARIA naming algorithm, so screen readers
          hear only the data-summary aria-label and not the title+subtitle
          a second time. Do NOT remove the aria-label or the chart loses
          its data-summary announcement. */}
      <div
        className={[
          'h-[260px]',
          // Recharts injects its own default classes; these selectors
          // override fills/strokes so the chart inherits our token
          // palette without per-chart prop bloat. Tick text uses
          // ink-secondary (4.48:1 on white) instead of ink-muted
          // (2.79:1) per QC R1 M3 contrast fix.
          "[&_.recharts-cartesian-axis-tick_text]:fill-ink-secondary",
          "[&_.recharts-cartesian-axis-tick_text]:text-[11px]",
          "[&_.recharts-cartesian-grid_line]:stroke-surface-border",
          "[&_.recharts-cartesian-grid_line]:stroke-[0.5]",
          "[&_.recharts-tooltip-cursor]:fill-primary/5",
        ].join(' ')}
      >
        {children}
      </div>
    </figure>
  );
}

interface ChartTooltipEntry {
  color?: string | undefined;
  name?: string | undefined;
  value?: number | string | undefined;
}

interface ChartTooltipProps {
  active?: boolean | undefined;
  payload?: ChartTooltipEntry[] | undefined;
  label?: string | number | undefined;
  currency?: boolean | undefined;
}

function ChartTooltip({ active, payload, label, currency }: ChartTooltipProps) {
  if (!active || !payload || payload.length === 0) return null;
  return (
    <div className="rounded-xl border border-surface-border bg-surface px-3 py-2 shadow-elevated">
      {label != null && label !== '' && (
        <p className="mb-1 text-[11px] font-semibold uppercase tracking-wide text-ink-secondary">
          {String(label)}
        </p>
      )}
      <ul className="space-y-0.5 text-xs">
        {payload.map((p, i) => (
          <li key={i} className="flex items-center gap-2">
            <span
              className="inline-block h-2 w-2 rounded-full"
              style={{ backgroundColor: p.color ?? '#FF9F43' }}
            />
            <span className="text-ink-secondary">{p.name ?? ''}:</span>
            <span className="font-semibold tabular-nums text-ink-primary">
              {typeof p.value === 'number'
                ? `${currency ? '₹' : ''}${formatNumber(p.value)}`
                : String(p.value ?? '')}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function MoneyTrendCard({
  data,
  className,
}: {
  data: Array<{ month: string; amount: number; defaulterAmount: number }>;
  className?: string;
}) {
  const total = data.reduce((s, d) => s + d.amount, 0);
  const defaulterTotal = data.reduce((s, d) => s + d.defaulterAmount, 0);
  return (
    <ChartCard
      title="Money collected"
      subtitle={`₹${formatNumber(total)} total · ₹${formatNumber(defaulterTotal)} from defaulters (subset)`}
      className={className}
      ariaLabel={`Monthly money collected. Total rupees ${total}.`}
    >
      {total === 0 ? (
        <ChartEmpty message="No collections recorded in this range" />
      ) : data.length === 1 ? (
        <ChartSingleValue label={data[0]!.month} value={formatCompactCurrency(data[0]!.amount)} />
      ) : (
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart data={data} margin={{ top: 8, right: 8, left: -8, bottom: 0 }}>
            <defs>
              <linearGradient id="moneyGradient" x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%" stopColor="#0E8278" stopOpacity={0.45} />
                <stop offset="95%" stopColor="#0E8278" stopOpacity={0} />
              </linearGradient>
              <linearGradient id="defaulterMoneyGradient" x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%" stopColor="#B45309" stopOpacity={0.4} />
                <stop offset="95%" stopColor="#B45309" stopOpacity={0} />
              </linearGradient>
            </defs>
            <CartesianGrid strokeDasharray="2 4" vertical={false} />
            <XAxis dataKey="month" tickLine={false} axisLine={false} />
            <YAxis
              tickLine={false}
              axisLine={false}
              width={48}
              tickFormatter={(v: number) => `₹${formatNumber(v)}`}
            />
            <Tooltip content={<ChartTooltip currency />} />
            <Legend iconType="circle" wrapperStyle={{ fontSize: '11px', paddingTop: '8px' }} />
            <Area
              type="monotone"
              dataKey="amount"
              name="All collections"
              stroke="#0E8278"
              strokeWidth={2}
              fill="url(#moneyGradient)"
              activeDot={{ r: 4, strokeWidth: 0 }}
            />
            <Area
              type="monotone"
              dataKey="defaulterAmount"
              name="Defaulter portion"
              stroke="#B45309"
              strokeWidth={2}
              fill="url(#defaulterMoneyGradient)"
              activeDot={{ r: 4, strokeWidth: 0 }}
            />
          </AreaChart>
        </ResponsiveContainer>
      )}
    </ChartCard>
  );
}

function CurrentVsDefaultCard({
  breakdown,
}: {
  breakdown: DashboardStats['currentVsDefault'];
}) {
  const total = breakdown.currentAmount + breakdown.defaultAmount;
  const chartData = [
    {
      label: 'Current',
      count: breakdown.currentCount,
      amount: breakdown.currentAmount,
    },
    {
      label: 'Default',
      count: breakdown.defaultCount,
      amount: breakdown.defaultAmount,
    },
  ];
  return (
    <ChartCard
      title="Current vs default"
      subtitle={`Active accounts split by paid-up status`}
      ariaLabel={`Current vs default. Current ${breakdown.currentCount} accounts, ₹${breakdown.currentAmount}. Default ${breakdown.defaultCount} accounts, ₹${breakdown.defaultAmount}.`}
    >
      {total === 0 && breakdown.currentCount + breakdown.defaultCount === 0 ? (
        <ChartEmpty message="No active accounts yet" />
      ) : (
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={chartData} margin={{ top: 8, right: 8, left: -8, bottom: 0 }}>
            <CartesianGrid strokeDasharray="2 4" vertical={false} />
            <XAxis dataKey="label" tickLine={false} axisLine={false} />
            <YAxis
              yAxisId="amount"
              tickLine={false}
              axisLine={false}
              width={48}
              tickFormatter={(v: number) => `₹${formatNumber(v)}`}
            />
            <Tooltip content={<ChartTooltip currency />} />
            <Bar
              yAxisId="amount"
              dataKey="amount"
              name="Monthly amount"
              fill="#FF9F43"
              radius={[8, 8, 0, 0]}
              maxBarSize={56}
            >
              <Cell fill="#0E8278" />
              <Cell fill="#B45309" />
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      )}
    </ChartCard>
  );
}

function SessionTrendCard({
  data,
  className,
}: {
  data: Array<{ month: string; count: number }>;
  className?: string;
}) {
  const total = data.reduce((s, d) => s + d.count, 0);
  return (
    <ChartCard
      title="Sessions over time"
      subtitle={`${formatNumber(total)} sessions in range`}
      className={className}
      ariaLabel={`Sessions per month. Total ${total}.`}
    >
      {total === 0 ? (
        <ChartEmpty message="No sessions in this range" />
      ) : data.length === 1 ? (
        <ChartSingleValue label={data[0]!.month} value={`${formatNumber(data[0]!.count)} sessions`} />
      ) : (
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart data={data} margin={{ top: 8, right: 8, left: -16, bottom: 0 }}>
            <defs>
              <linearGradient id="sessionGradient" x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%" stopColor="#FF9F43" stopOpacity={0.5} />
                <stop offset="95%" stopColor="#FF9F43" stopOpacity={0} />
              </linearGradient>
            </defs>
            <CartesianGrid strokeDasharray="2 4" vertical={false} />
            <XAxis dataKey="month" tickLine={false} axisLine={false} />
            <YAxis tickLine={false} axisLine={false} width={32} allowDecimals={false} />
            <Tooltip content={<ChartTooltip />} cursor={{ fill: 'rgba(255,159,67,0.05)' }} />
            <Area
              type="monotone"
              dataKey="count"
              name="Sessions"
              stroke="#FF9F43"
              strokeWidth={2}
              fill="url(#sessionGradient)"
              activeDot={{ r: 4, strokeWidth: 0 }}
            />
          </AreaChart>
        </ResponsiveContainer>
      )}
    </ChartCard>
  );
}

function ScansTrendCard({
  data,
  className,
}: {
  data: Array<{ month: string; collected: number; defaulters: number }>;
  className?: string;
}) {
  const total = data.reduce((s, d) => s + d.collected, 0);
  return (
    <ChartCard
      title="Scans & defaulters"
      subtitle={`${formatNumber(total)} scans in range`}
      className={className}
      ariaLabel={`Monthly scans and defaulters. Total scans ${total}.`}
    >
      {total === 0 ? (
        <ChartEmpty message="No scans in this range" />
      ) : (
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={data} margin={{ top: 8, right: 8, left: -16, bottom: 0 }}>
            <CartesianGrid strokeDasharray="2 4" vertical={false} />
            <XAxis dataKey="month" tickLine={false} axisLine={false} />
            <YAxis tickLine={false} axisLine={false} width={32} allowDecimals={false} />
            <Tooltip content={<ChartTooltip />} />
            <Legend
              iconType="circle"
              wrapperStyle={{ fontSize: '11px', paddingTop: '8px' }}
            />
            <Line
              type="monotone"
              dataKey="collected"
              name="Total scans"
              stroke="#4ECDC4"
              strokeWidth={2}
              dot={false}
              activeDot={{ r: 4, strokeWidth: 0 }}
            />
            <Line
              type="monotone"
              dataKey="defaulters"
              name="Defaulter scans"
              stroke="#B45309"
              strokeWidth={2}
              dot={false}
              activeDot={{ r: 4, strokeWidth: 0 }}
            />
          </LineChart>
        </ResponsiveContainer>
      )}
    </ChartCard>
  );
}

function SourceDonutCard({
  data,
}: {
  data: Array<{ source: 'MANUAL' | 'CSV'; count: number }>;
}) {
  const total = data.reduce((s, d) => s + d.count, 0);
  const COLORS: Record<'MANUAL' | 'CSV', string> = {
    MANUAL: '#FF9F43',
    CSV: '#4ECDC4',
  };
  return (
    <ChartCard
      title="Account source"
      subtitle={`${formatNumber(total)} accounts`}
      ariaLabel={`Account source breakdown. ${data.map((d) => `${d.source}: ${d.count}`).join(', ')}.`}
    >
      {total === 0 ? (
        <ChartEmpty message="No accounts yet" />
      ) : (
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie
              data={data}
              dataKey="count"
              nameKey="source"
              innerRadius="60%"
              outerRadius="85%"
              paddingAngle={2}
              cornerRadius={4}
              startAngle={90}
              endAngle={-270}
            >
              {data.map((d) => (
                <Cell key={d.source} fill={COLORS[d.source]} stroke="none" />
              ))}
            </Pie>
            <Tooltip content={<ChartTooltip />} />
            <Legend
              iconType="circle"
              wrapperStyle={{ fontSize: '11px', paddingTop: '8px' }}
            />
          </PieChart>
        </ResponsiveContainer>
      )}
    </ChartCard>
  );
}

function AmountHistogramCard({
  data,
}: {
  data: Array<{ bucket: string; count: number }>;
}) {
  const total = data.reduce((s, d) => s + d.count, 0);
  return (
    <ChartCard
      title="Monthly amount distribution"
      subtitle="account buckets by monthly amount"
      ariaLabel={`Monthly amount distribution across ${total} accounts.`}
    >
      {total === 0 ? (
        <ChartEmpty message="No accounts yet" />
      ) : (
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={data} margin={{ top: 8, right: 8, left: -16, bottom: 0 }}>
            <CartesianGrid strokeDasharray="2 4" vertical={false} />
            <XAxis dataKey="bucket" tickLine={false} axisLine={false} />
            <YAxis tickLine={false} axisLine={false} width={32} allowDecimals={false} />
            <Tooltip content={<ChartTooltip />} />
            <Bar
              dataKey="count"
              name="Accounts"
              fill="#FF9F43"
              radius={[8, 8, 0, 0]}
              maxBarSize={56}
            />
          </BarChart>
        </ResponsiveContainer>
      )}
    </ChartCard>
  );
}

function TopDefaultersCard({
  rows,
}: {
  rows: Array<{ name: string; rdNumber: string; monthsOverdue: number; maskedRdNumber: string }>;
}) {
  return (
    <div className="rounded-2xl border border-surface-border bg-surface p-5 shadow-card">
      <div className="mb-3 flex items-end justify-between">
        <div>
          <h3 className="text-sm font-semibold text-ink-primary">Top defaulters</h3>
          <p className="mt-0.5 text-xs text-ink-secondary">By months overdue</p>
        </div>
        <Link
          to="/accounts"
          className="min-h-[36px] rounded-pill border border-surface-border px-3 py-1.5 text-[11px] font-medium text-ink-secondary transition-colors hover:border-primary hover:text-primary"
        >
          View all
        </Link>
      </div>
      {rows.length === 0 ? (
        <div className="flex flex-col items-center gap-2 rounded-xl border border-dashed border-surface-border bg-surface-alt p-8 text-center text-xs text-ink-secondary">
          <ShieldCheck className="h-6 w-6 text-accent-mint-ink" aria-hidden="true" />
          No active accounts are overdue.
        </div>
      ) : (
        <ol className="space-y-1">
          {rows.map((r, i) => (
            <li
              key={r.rdNumber}
              className="flex items-center justify-between rounded-xl px-3 py-2 hover:bg-surface-alt"
            >
              <div className="flex min-w-0 items-center gap-3">
                <span className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-pill bg-amber-100 text-[11px] font-semibold text-amber-700">
                  {i + 1}
                </span>
                <div className="min-w-0">
                  <p className="truncate text-sm font-medium text-ink-primary">{r.name}</p>
                  <p className="truncate font-mono text-[11px] text-ink-secondary">{r.maskedRdNumber}</p>
                </div>
              </div>
              <span className="ml-3 inline-flex shrink-0 items-center rounded-pill bg-danger/10 px-2 py-0.5 text-[11px] font-semibold text-danger">
                {r.monthsOverdue}mo
              </span>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}

// QC R2 M5 — every badge swatch above passes WCAG AA 4.5:1 on white.
// Prior text-primary-dark (3.5:1), text-danger (3.4:1) and text-accent-
// coral (2.6:1) failed AA; the amber-800 / red-700 / mint-ink palette
// here is verified at 6.5:1, 5.9:1, 4.91:1, 7.6:1, 5.9:1 respectively.
// Do NOT swap back to brand colors without measuring contrast first.
const KIND_BADGE: Record<ActivityKind, { label: string; className: string }> = {
  session_finalized: { label: 'Session', className: 'bg-primary/10 text-amber-800' },
  session_deleted: { label: 'Deleted', className: 'bg-red-100 text-red-700' },
  defaulter_edited: { label: 'Defaulter', className: 'bg-amber-100 text-amber-700' },
  account_added: { label: 'Added', className: 'bg-accent-mint/15 text-accent-mint-ink' },
  account_edited: { label: 'Edited', className: 'bg-red-100 text-red-700' },
};

function RecentActivityCard({
  rows,
}: {
  rows: DashboardStats['recentActivity'];
}) {
  return (
    <div className="rounded-2xl border border-surface-border bg-surface p-5 shadow-card">
      <div className="mb-3 flex items-end justify-between">
        <div>
          <h3 className="text-sm font-semibold text-ink-primary">Recent activity</h3>
          <p className="mt-0.5 text-xs text-ink-secondary">Latest events from all phones</p>
        </div>
        <Link
          to="/activity"
          className="min-h-[36px] rounded-pill border border-surface-border px-3 py-1.5 text-[11px] font-medium text-ink-secondary transition-colors hover:border-primary hover:text-primary"
        >
          View all
        </Link>
      </div>
      {rows.length === 0 ? (
        <div className="flex flex-col items-center gap-2 rounded-xl border border-dashed border-surface-border bg-surface-alt p-8 text-center text-xs text-ink-secondary">
          <Inbox className="h-6 w-6 text-ink-muted" aria-hidden="true" />
          No activity yet.
        </div>
      ) : (
        <ul className="space-y-1">
          {rows.map((r, i) => {
            const badge = KIND_BADGE[r.kind];
            const body = (
              <div className="flex items-start gap-3 px-3 py-2">
                <span
                  className={[
                    'mt-0.5 inline-flex shrink-0 items-center rounded-pill px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide',
                    badge.className,
                  ].join(' ')}
                >
                  {badge.label}
                </span>
                <div className="min-w-0 flex-1">
                  <p className="truncate text-xs font-medium text-ink-primary">{r.primary}</p>
                  {r.secondary && (
                    <p className="truncate text-[11px] text-ink-secondary">{r.secondary}</p>
                  )}
                </div>
                <span
                  title={formatDateTime(r.occurredAt)}
                  aria-label={formatDateTime(r.occurredAt)}
                  className="ml-2 shrink-0 text-[11px] tabular-nums text-ink-secondary"
                >
                  {formatRelativeTime(r.occurredAt)}
                </span>
              </div>
            );
            if (r.linkTo) {
              return (
                <li key={i} className="rounded-xl transition-colors hover:bg-surface-alt">
                  <Link to={r.linkTo} className="block">
                    {body}
                  </Link>
                </li>
              );
            }
            return <li key={i}>{body}</li>;
          })}
        </ul>
      )}
    </div>
  );
}

function ChartEmpty({ message }: { message: string }) {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-2 rounded-xl border border-dashed border-surface-border bg-surface-alt text-center">
      <Inbox className="h-6 w-6 text-ink-muted" aria-hidden="true" />
      <p className="px-6 text-xs text-ink-secondary">{message}</p>
    </div>
  );
}

// Single-point fallback for AreaChart / LineChart. Recharts renders a
// 1-element dataset with type="monotone" as a stroke-only dot with a
// zero-width fill — visually indistinguishable from broken. We swap to
// a centred KPI-style callout so the operator clearly sees the one
// data point with its month label. R5 oracle bg_78192f17 F1.
function ChartSingleValue({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-1.5 rounded-xl border border-surface-border bg-surface-alt text-center">
      <p className="text-[10px] font-medium uppercase tracking-wider text-ink-muted">{label}</p>
      <p className="text-2xl font-semibold text-ink-primary">{value}</p>
      <p className="text-[11px] text-ink-secondary">Only one period in range</p>
    </div>
  );
}

export function DashboardSkeleton() {
  return (
    <div className="space-y-8">
      <PageHeader title="Dashboard" subtitle="Snapshot of accounts, sessions, scans, and devices" />
      <SkeletonCard count={4} heightPx={104} rounded="2xl" />
      <SkeletonCard count={2} heightPx={300} rounded="2xl" />
    </div>
  );
}

