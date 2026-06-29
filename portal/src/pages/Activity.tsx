import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { PageHeader } from '../components/PageHeader';
import { SkeletonCard } from '../components/Loader';
import { fetchActivityFeed } from '../lib/queries';
import type { ActivityKind, ActivityRow } from '../types/db';
import { formatDateTime, formatNumber, formatRelativeTime } from '../lib/format';

/**
 * Activity feed materialised client-side from scan_sessions, rd_numbers
 * (defaulter edits) and rd_accounts. There is no cloud `activity`
 * table — the phone's bell uses a Room-local `sync_events` log that
 * the portal can't read. See [fetchActivityFeed] for the merge logic
 * and [ActivityRow] for the projected shape.
 *
 * Fetches a fixed-size window (100 events by default) and paginates
 * client-side at 30 per page. Realtime invalidation is wired in
 * useRealtimeSync.ts so a phone push or another portal tab's edit
 * reflows the list within a second.
 */
const PAGE_SIZE = 30;
const FETCH_LIMIT = 100;

const ALL_KINDS: ReadonlyArray<ActivityKind> = [
  'session_finalized',
  'session_deleted',
  'defaulter_edited',
  'account_added',
  'account_edited',
];

const KIND_LABELS: Record<ActivityKind, string> = {
  session_finalized: 'Sessions',
  session_deleted: 'Deletes',
  defaulter_edited: 'Defaulters',
  account_added: 'Accounts added',
  account_edited: 'Accounts edited',
};

// Compact per-event badge — colour-coded so the feed scans visually
// without forcing the reader to parse the verb in `primary`. Colours
// reuse the palette from Sessions.tsx defaulter pill + the tailwind
// theme tokens so dark-mode work later inherits them automatically.
const KIND_BADGE: Record<ActivityKind, { label: string; className: string }> = {
  session_finalized: {
    label: 'Session',
    className: 'bg-primary/10 text-primary-dark',
  },
  session_deleted: {
    label: 'Deleted',
    className: 'bg-danger/10 text-danger',
  },
  defaulter_edited: {
    label: 'Defaulter',
    className: 'bg-warn/15 text-warn',
  },
  account_added: {
    label: 'Added',
    className: 'bg-accent-mint/15 text-accent-mint-ink',
  },
  account_edited: {
    label: 'Edited',
    className: 'bg-surface-alt text-ink-secondary',
  },
};

export function ActivityPage() {
  // Filter chips and page index live in the URL so browser Back from a
  // session-detail link returns to the same filtered view + page, and
  // the owner can deep-link to "Defaulters only, page 2" for triage.
  const [searchParams, setSearchParams] = useSearchParams();

  const activeKinds = useMemo<ReadonlyArray<ActivityKind>>(() => {
    const raw = searchParams.get('kinds');
    if (!raw) return ALL_KINDS;
    const tokens = raw.split(',').filter((t): t is ActivityKind =>
      (ALL_KINDS as ReadonlyArray<string>).includes(t)
    );
    return tokens.length > 0 ? tokens : ALL_KINDS;
  }, [searchParams]);

  const page = Math.max(0, Number(searchParams.get('page') ?? '0') | 0);

  const setKinds = (next: ReadonlyArray<ActivityKind>) => {
    const params = new URLSearchParams(searchParams);
    // Persist only when the filter is a strict subset; storing the
    // full set in the URL is noise and changes the canonical link
    // for "everything" away from /activity.
    if (next.length === 0 || next.length === ALL_KINDS.length) {
      params.delete('kinds');
    } else {
      params.set('kinds', next.join(','));
    }
    // Reset page on filter change — the previous offset rarely
    // makes sense against a different category subset.
    params.delete('page');
    setSearchParams(params, { replace: false });
  };

  const setPage = (next: number) => {
    const params = new URLSearchParams(searchParams);
    if (next <= 0) params.delete('page');
    else params.set('page', String(next));
    setSearchParams(params, { replace: false });
  };

  const toggleKind = (kind: ActivityKind) => {
    const set = new Set(activeKinds);
    if (set.has(kind) && set.size > 1) {
      set.delete(kind);
    } else {
      set.add(kind);
    }
    setKinds(Array.from(set));
  };

  const isAllSelected = activeKinds.length === ALL_KINDS.length;

  const query = useQuery<ActivityRow[]>({
    // Key on the sorted kinds tuple so toggling chips re-runs the
    // fetch and TanStack Query dedups identical filter combinations.
    queryKey: ['activity', [...activeKinds].sort().join(',')],
    queryFn: () => fetchActivityFeed({ limit: FETCH_LIMIT, kinds: activeKinds }),
  });

  const rows = query.data ?? [];
  const totalPages = Math.max(1, Math.ceil(rows.length / PAGE_SIZE));
  const safePage = Math.min(page, totalPages - 1);
  const start = safePage * PAGE_SIZE;
  const visible = rows.slice(start, start + PAGE_SIZE);
  const isInitialLoad = query.isLoading;
  const isEmpty = !isInitialLoad && rows.length === 0;

  return (
    <div>
      <PageHeader
        title="Activity"
        subtitle="Recent changes across sessions, defaulters, and accounts."
        action={
          <button
            type="button"
            onClick={() => setKinds(ALL_KINDS)}
            disabled={isAllSelected}
            className="rounded-pill border border-surface-border bg-surface px-3 py-1.5 text-xs font-medium text-ink-secondary transition-colors hover:border-primary hover:text-primary disabled:cursor-not-allowed disabled:opacity-50"
          >
            Show all
          </button>
        }
      />

      <div
        className="mt-4 flex flex-wrap items-center gap-2"
        role="group"
        aria-label="Filter activity by category"
      >
        {ALL_KINDS.map((kind) => {
          const active = activeKinds.includes(kind);
          return (
            <button
              key={kind}
              type="button"
              onClick={() => toggleKind(kind)}
              aria-pressed={active}
              className={[
                'rounded-pill px-3 py-1.5 text-xs font-medium transition-colors',
                active
                  ? 'border border-primary bg-primary/10 text-primary-dark'
                  : 'border border-surface-border bg-surface text-ink-secondary hover:border-ink-secondary hover:text-ink-primary',
              ].join(' ')}
            >
              {KIND_LABELS[kind]}
            </button>
          );
        })}
      </div>

      {query.isError && (
        <ErrorState
          message={
            query.error instanceof Error ? query.error.message : 'Failed to load activity.'
          }
          onRetry={() => query.refetch()}
        />
      )}

      {isInitialLoad && <ActivitySkeletons />}

      {isEmpty && <EmptyState filtered={!isAllSelected} />}

      {!isInitialLoad && rows.length > 0 && (
        <div className="mt-6 overflow-hidden rounded-2xl border border-surface-border bg-surface shadow-card">
          <ul className="divide-y divide-surface-border">
            {visible.map((row, idx) => (
              <ActivityItem key={`${row.kind}-${row.occurredAt}-${idx}`} row={row} />
            ))}
          </ul>

          <div className="flex items-center justify-between border-t border-surface-border bg-surface-alt px-4 py-3 text-xs text-ink-secondary">
            <span>
              Showing {formatNumber(start + 1)}–{formatNumber(start + visible.length)} of{' '}
              {formatNumber(rows.length)}
              {rows.length >= FETCH_LIMIT && ' (most recent)'}
            </span>
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => setPage(safePage - 1)}
                disabled={safePage === 0}
                className="rounded-pill border border-surface-border bg-surface px-3 py-1.5 font-medium text-ink-primary transition-colors hover:border-primary hover:text-primary disabled:cursor-not-allowed disabled:opacity-40"
              >
                Previous
              </button>
              <span className="tabular-nums">
                Page {safePage + 1} / {totalPages}
              </span>
              <button
                type="button"
                onClick={() => setPage(safePage + 1)}
                disabled={safePage >= totalPages - 1}
                className="rounded-pill border border-surface-border bg-surface px-3 py-1.5 font-medium text-ink-primary transition-colors hover:border-primary hover:text-primary disabled:cursor-not-allowed disabled:opacity-40"
              >
                Next
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function ActivityItem({ row }: { row: ActivityRow }) {
  const badge = KIND_BADGE[row.kind];
  const body = (
    <div className="flex items-start gap-4 px-4 py-3">
      <span
        className={[
          'mt-0.5 inline-flex shrink-0 items-center rounded-pill px-2.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide',
          badge.className,
        ].join(' ')}
      >
        {badge.label}
      </span>
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium text-ink-primary">{row.primary}</p>
        {row.secondary && (
          <p className="mt-0.5 truncate text-xs text-ink-secondary">{row.secondary}</p>
        )}
      </div>
      <div className="flex shrink-0 flex-col items-end gap-0.5 text-right">
        <span
          title={formatDateTime(row.occurredAt)}
          className="text-xs tabular-nums text-ink-secondary"
        >
          {formatRelativeTime(row.occurredAt)}
        </span>
        <span className="text-[11px] text-ink-muted">{row.actorLabel}</span>
      </div>
    </div>
  );

  if (row.linkTo) {
    return (
      <li className="transition-colors hover:bg-surface-alt">
        <Link to={row.linkTo} className="block">
          {body}
        </Link>
      </li>
    );
  }
  return <li>{body}</li>;
}

function ActivitySkeletons() {
  return (
    <div className="mt-6">
      <SkeletonCard count={8} heightPx={64} rounded="xl" label="Loading activity" />
    </div>
  );
}

function EmptyState({ filtered }: { filtered: boolean }) {
  return (
    <div className="mt-6 rounded-2xl border border-dashed border-surface-border bg-surface p-12 text-center">
      <p className="text-sm font-medium text-ink-primary">
        {filtered ? 'No activity matches the selected categories.' : 'No activity yet.'}
      </p>
      <p className="mt-1 text-xs text-ink-secondary">
        {filtered
          ? 'Add more categories or use "Show all" to widen the feed.'
          : 'Finalized sessions, defaulter edits, and account changes will appear here.'}
      </p>
    </div>
  );
}

function ErrorState({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="mt-6 rounded-2xl border border-danger/20 bg-danger/5 p-6 text-center">
      <p className="text-sm font-medium text-danger">{message}</p>
      <button
        type="button"
        onClick={onRetry}
        className="mt-3 rounded-pill border border-danger/30 px-3.5 py-1.5 text-xs font-medium text-danger hover:bg-danger/10"
      >
        Retry
      </button>
    </div>
  );
}
