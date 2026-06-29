import { useMemo, useState } from 'react';
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
  Layers,
  ScanLine,
  UserMinus,
  Users,
  Wallet,
} from 'lucide-react';
import { PageHeader } from '../components/PageHeader';
import { FullPageLoader, SkeletonCard } from '../components/Loader';
import {
  fetchDashboardStats,
  type DashboardRange,
  type DashboardStats,
} from '../lib/dashboardQueries';
import { formatDateTime, formatNumber, formatRelativeTime } from '../lib/format';
import type { ActivityKind } from '../types/db';

/**
 * Operator dashboard — home page of the portal. Composes the full
 * picture from [fetchDashboardStats] into a 10-widget grid:
 *
 *   1. KPI row: total accounts, active accounts, defaulters, collected
 *      this month, sessions in range, total scans in range, active
 *      devices.
 *   2. Trends: monthly session counts (area chart), monthly scans
 *      with defaulter overlay (composed line + bar).
 *   3. Distribution: account source breakdown (donut), monthly amount
 *      histogram (bar).
 *   4. Top defaulters table (top 10).
 *   5. Recent activity strip linking into the Activity feed.
 *
 * Time range selector (3 / 6 / 12 / All) gates the time-series widgets.
 * The KPIs that are inherently "now" (active accounts, active devices,
 * collected this month) ignore the range. The dashboard query
 * de-duplicates work by reading every dependency once and projecting
 * client-side — see fetchDashboardStats for the rationale.
 */

const RANGE_OPTIONS: Array<{ value: DashboardRange; label: string }> = [
  { value: 3, label: '3M' },
  { value: 6, label: '6M' },
  { value: 12, label: '12M' },
  { value: null, label: 'All' },
];

export function DashboardPage() {
  const [range, setRange] = useState<DashboardRange>(12);

  const { data, isPending, isError, error, refetch } = useQuery({
    queryKey: ['dashboard', range],
    queryFn: () => fetchDashboardStats(range),
    // Stale-while-revalidate is the right default for a dashboard:
    // realtime invalidates on writes, this just smooths route
    // re-entries within a session.
    staleTime: 30_000,
  });

  if (isPending) return <FullPageLoader label="Building dashboard" />;
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

  return <DashboardBody stats={data} range={range} onRangeChange={setRange} />;
}

interface DashboardBodyProps {
  stats: DashboardStats;
  range: DashboardRange;
  onRangeChange: (r: DashboardRange) => void;
}

function DashboardBody({ stats, range, onRangeChange }: DashboardBodyProps) {
  return (
    <div className="space-y-8">
      <PageHeader
        title="Dashboard"
        subtitle="Snapshot of accounts, sessions, scans, and devices"
        action={<RangeSelector value={range} onChange={onRangeChange} />}
      />

      <KpiRow stats={stats} />

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

function RangeSelector({
  value,
  onChange,
}: {
  value: DashboardRange;
  onChange: (r: DashboardRange) => void;
}) {
  return (
    <div
      role="group"
      aria-label="Dashboard date range"
      className="inline-flex items-center rounded-pill border border-surface-border bg-surface p-0.5 shadow-card"
    >
      {RANGE_OPTIONS.map((opt) => {
        const active = opt.value === value;
        return (
          <button
            key={String(opt.value)}
            type="button"
            onClick={() => onChange(opt.value)}
            aria-pressed={active}
            className={[
              'rounded-pill px-3 py-1 text-xs font-semibold transition-colors',
              active
                ? 'bg-primary text-white shadow-card'
                : 'text-ink-secondary hover:text-ink-primary',
            ].join(' ')}
          >
            {opt.label}
          </button>
        );
      })}
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
  coral: { bg: 'bg-accent-coral/15', ink: 'text-accent-coral' },
  warn: { bg: 'bg-warn/15', ink: 'text-warn' },
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
          <p className="mt-2 truncate text-2xl font-semibold tracking-tight text-ink-primary">
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
  // "—" so the card doesn't render meaningless percentages on fresh
  // installs.
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
        subtitle="distinct RD numbers with overdue"
        icon={<UserMinus className="h-5 w-5" />}
        tone="warn"
      />
      <KpiCard
        title="Collected this month"
        value={`₹${formatNumber(stats.totalCollectedThisMonth)}`}
        subtitle="last_paid_through ≥ current month"
        icon={<Wallet className="h-5 w-5" />}
        tone="mint"
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
        value={`₹${formatNumber(stats.totalAccounts > 0
          ? Math.round(
              stats.amountHistogram.reduce((s, b, i) => {
                // Midpoint approximation per bucket — produces a stable
                // "typical monthly amount" indicator without us having
                // to ship the full account list to the client just for
                // this number. Buckets are in fetchDashboardStats; if
                // the bucket label set changes, update this midpoint
                // table.
                const midpoints = [250, 750, 1500, 3500, 7500];
                return s + b.count * (midpoints[i] ?? 0);
              }, 0) / stats.totalAccounts,
            )
          : 0)}`}
        subtitle="monthly amount across accounts"
        icon={<Banknote className="h-5 w-5" />}
        tone="primary"
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
            <p className="mt-0.5 text-xs text-ink-muted">{subtitle}</p>
          )}
        </div>
      </figcaption>
      <div
        className={[
          'h-[260px]',
          // Recharts injects its own default classes; these selectors
          // override fills/strokes so the chart inherits our token
          // palette without per-chart prop bloat.
          "[&_.recharts-cartesian-axis-tick_text]:fill-ink-muted",
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

/**
 * Custom Tailwind-themed tooltip — the default Recharts box is ugly.
 *
 * Recharts' upstream `TooltipProps` generic doesn't match the actual
 * runtime payload under `exactOptionalPropertyTypes: true`. We type
 * the props locally against the shape Recharts documents in its
 * `Customized` recipe and accept `unknown` color/name for safety.
 */
interface ChartTooltipEntry {
  color?: string | undefined;
  name?: string | undefined;
  value?: number | string | undefined;
}

interface ChartTooltipProps {
  active?: boolean | undefined;
  payload?: ChartTooltipEntry[] | undefined;
  label?: string | number | undefined;
}

function ChartTooltip({ active, payload, label }: ChartTooltipProps) {
  if (!active || !payload || payload.length === 0) return null;
  return (
    <div className="rounded-xl border border-surface-border bg-surface px-3 py-2 shadow-elevated">
      {label != null && label !== '' && (
        <p className="mb-1 text-[11px] font-semibold uppercase tracking-wide text-ink-muted">
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
              {typeof p.value === 'number' ? formatNumber(p.value) : String(p.value ?? '')}
            </span>
          </li>
        ))}
      </ul>
    </div>
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
              stroke="#F59E0B"
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
  rows: Array<{ name: string; rdNumber: string; monthsOverdue: number }>;
}) {
  return (
    <div className="rounded-2xl border border-surface-border bg-surface p-5 shadow-card">
      <div className="mb-3 flex items-end justify-between">
        <div>
          <h3 className="text-sm font-semibold text-ink-primary">Top defaulters</h3>
          <p className="mt-0.5 text-xs text-ink-muted">By months overdue</p>
        </div>
        <Link
          to="/accounts"
          className="rounded-pill border border-surface-border px-2.5 py-1 text-[11px] font-medium text-ink-secondary transition-colors hover:border-primary hover:text-primary"
        >
          View all
        </Link>
      </div>
      {rows.length === 0 ? (
        <div className="rounded-xl border border-dashed border-surface-border bg-surface-alt p-6 text-center text-xs text-ink-secondary">
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
                <span className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-pill bg-warn/15 text-[11px] font-semibold text-warn">
                  {i + 1}
                </span>
                <div className="min-w-0">
                  <p className="truncate text-sm font-medium text-ink-primary">{r.name}</p>
                  <p className="truncate font-mono text-[11px] text-ink-muted">{r.rdNumber}</p>
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

const KIND_BADGE: Record<ActivityKind, { label: string; className: string }> = {
  session_finalized: { label: 'Session', className: 'bg-primary/10 text-primary-dark' },
  session_deleted: { label: 'Deleted', className: 'bg-danger/10 text-danger' },
  defaulter_edited: { label: 'Defaulter', className: 'bg-warn/15 text-warn' },
  account_added: { label: 'Added', className: 'bg-accent-mint/15 text-accent-mint-ink' },
  account_edited: { label: 'Edited', className: 'bg-accent-coral/15 text-accent-coral' },
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
          <p className="mt-0.5 text-xs text-ink-muted">Latest events from all phones</p>
        </div>
        <Link
          to="/activity"
          className="rounded-pill border border-surface-border px-2.5 py-1 text-[11px] font-medium text-ink-secondary transition-colors hover:border-primary hover:text-primary"
        >
          View all
        </Link>
      </div>
      {rows.length === 0 ? (
        <div className="rounded-xl border border-dashed border-surface-border bg-surface-alt p-6 text-center text-xs text-ink-secondary">
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
                  className="ml-2 shrink-0 text-[11px] tabular-nums text-ink-muted"
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
    <div className="flex h-full items-center justify-center rounded-xl border border-dashed border-surface-border bg-surface-alt text-center">
      <p className="px-6 text-xs text-ink-secondary">{message}</p>
    </div>
  );
}

// Suppress unused import warning while keeping the export shape used
// by the App.tsx route plus the skeleton helper for callers that want
// to preload the route.
export function DashboardSkeleton() {
  return (
    <div className="space-y-8">
      <PageHeader title="Dashboard" subtitle="Snapshot of accounts, sessions, scans, and devices" />
      <SkeletonCard count={4} heightPx={104} rounded="2xl" />
      <SkeletonCard count={2} heightPx={300} rounded="2xl" />
    </div>
  );
}
