import { useDeferredValue, useEffect, useState } from 'react';
import { useInfiniteQuery } from '@tanstack/react-query';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { PageHeader } from '../components/PageHeader';
import { SkeletonCard } from '../components/Loader';
import {
  SESSIONS_PAGE_SIZE,
  fetchSessionsPage,
  type SessionsPage,
} from '../lib/queries';
import { formatDateTime, formatNumber, formatRelativeTime } from '../lib/format';
import { useDocumentTitle } from '../lib/useDocumentTitle';
import type { ScanSessionRow } from '../types/db';

export function SessionsPage() {
  useDocumentTitle('Sessions');
  // Search state is persisted in URL ?q= so browser Back from
  // SessionDetail returns to the same filtered + scrolled view, and
  // the owner can bookmark or share a filtered link. Without this the
  // search box clears on every nav-away even though the Sessions
  // route remembers nothing else either.
  const [searchParams, setSearchParams] = useSearchParams();
  const urlSearch = searchParams.get('q') ?? '';
  const [searchInput, setSearchInput] = useState(urlSearch);
  // useDeferredValue mirrors the pattern in Accounts.tsx: typing stays
  // on the high-priority render lane while the (potentially 4-round-
  // trip) network fetch runs at low priority. Every keystroke fires
  // the query — no need for the operator to press Enter, which the
  // previous form-submit-only pattern silently required and which
  // matched no other search input in the portal. React batches the
  // effect so keeps typing responsive at any input rate.
  const committedSearch = useDeferredValue(searchInput);
  // Resync local state when the URL changes (back/forward navigation
  // or external link). Without this the input shows stale text after
  // a popstate event.
  useEffect(() => {
    setSearchInput(urlSearch);
  }, [urlSearch]);
  // URL sync side-effect: write ?q= only when the deferred value
  // settles. Two guards prevent noise: (a) skip when the URL already
  // matches (idempotent), (b) use replace:true so browser Back doesn't
  // step through every character. This preserves the "browser Back
  // returns to the same filtered view" contract from the previous
  // form-submit pattern without churning history on every keystroke.
  useEffect(() => {
    if (committedSearch === urlSearch) return;
    const params = new URLSearchParams(searchParams);
    if (committedSearch) {
      params.set('q', committedSearch);
    } else {
      params.delete('q');
    }
    setSearchParams(params, { replace: true });
  }, [committedSearch, urlSearch, searchParams, setSearchParams]);

  const query = useInfiniteQuery<SessionsPage>({
    queryKey: ['sessions', committedSearch],
    initialPageParam: 0,
    queryFn: ({ pageParam }) =>
      fetchSessionsPage({
        offset: pageParam as number,
        search: committedSearch,
      }),
    getNextPageParam: (last) => last.nextOffset,
  });

  const rows = query.data?.pages.flatMap((page) => page.rows) ?? [];
  const isInitialLoad = query.isLoading;
  const isEmpty = !isInitialLoad && rows.length === 0;
  const navigate = useNavigate();
  // Whole-row navigation. Handler-on-<tr> pattern (with role=link +
  // keyboard Enter/Space) instead of wrapping cells in <Link> because
  // <a> cannot legally contain <td> per HTML spec — nested Link would
  // render invalid markup that some screen readers linearize wrong.
  const openSession = (id: string) => navigate(`/sessions/${id}`);
  // Split the row list into segments per month so we can render
  // a divider between segments. Uses end_time (finalization time)
  // to match the row's own "Ended" column — a session that spans
  // two months is grouped by when it ended so the divider matches
  // the visible timestamp beside it. end_time can technically be
  // null for a not-yet-finalized session but Sessions.tsx never
  // renders those (fetchSessionsPage orders desc-nulls-last and
  // the phone never uploads pre-finalize rows); the guard below
  // is defensive.
  const rowsByMonth = groupSessionsByMonth(rows);

  return (
    <div>
      <PageHeader
        title="Sessions"
        subtitle="Every finalized scanning session across all signed-in phones."
        action={
          <form
            role="search"
            onSubmit={(e) => {
              e.preventDefault();
            }}
            className="flex items-center gap-2"
          >
            <label className="contents">
              <span className="sr-only">Search by session number or account name</span>
              <input
                type="text"
                placeholder="Search # or account name"
                aria-label="Search by session number or account name"
                value={searchInput}
                onChange={(e) => setSearchInput(e.target.value)}
                className="w-44 rounded-pill border border-surface-border bg-surface px-3.5 py-1.5 text-sm placeholder:text-ink-muted sm:w-64"
              />
            </label>
            {searchInput && (
              <button
                type="button"
                onClick={() => setSearchInput('')}
                className="rounded-pill px-3 py-1.5 text-xs text-ink-secondary hover:text-ink-primary"
              >
                Clear
              </button>
            )}
          </form>
        }
      />

      {query.isError && (
        <ErrorState
          message={
            query.error instanceof Error ? query.error.message : 'Failed to load sessions.'
          }
          onRetry={() => query.refetch()}
        />
      )}

      {isInitialLoad && <SessionSkeletons />}

      {isEmpty && <EmptyState search={committedSearch} />}

      {!isInitialLoad && rows.length > 0 && (
        <div className="mt-6 overflow-x-auto rounded-2xl border border-surface-border bg-surface shadow-card">
          <table className="w-full sm:min-w-[640px] text-left text-sm">
            <caption className="sr-only">Scanning sessions list</caption>
            <thead className="border-b border-surface-border bg-surface-alt text-xs uppercase tracking-wide text-ink-secondary">
              <tr>
                <th className="px-4 py-3 font-medium">#</th>
                <th className="px-4 py-3 font-medium">Operator</th>
                <th className="px-4 py-3 font-medium text-right">LOTs</th>
                <th className="px-4 py-3 font-medium text-right">RD numbers</th>
                <th className="px-4 py-3 font-medium text-right">Defaulters</th>
                <th className="px-4 py-3 font-medium">Ended</th>
              </tr>
            </thead>
            <tbody>
              {rowsByMonth.map((group) => (
                <FragmentGroup
                  key={group.monthKey}
                  monthLabel={group.monthLabel}
                  sessions={group.sessions}
                  onOpen={openSession}
                />
              ))}
            </tbody>
          </table>

          <div className="flex items-center justify-between border-t border-surface-border bg-surface-alt px-4 py-3 text-xs text-ink-secondary">
            <span>
              Showing {formatNumber(rows.length)} session{rows.length === 1 ? '' : 's'}
              {committedSearch && ` matching "${committedSearch}"`}
            </span>
            {query.hasNextPage && (
              <button
                type="button"
                onClick={() => query.fetchNextPage()}
                disabled={query.isFetchingNextPage}
                className="rounded-pill border border-surface-border bg-surface px-3 py-1.5 font-medium text-ink-primary transition-colors hover:border-primary hover:text-primary disabled:cursor-wait disabled:opacity-50"
              >
                {query.isFetchingNextPage
                  ? 'Loading…'
                  : `Load next ${SESSIONS_PAGE_SIZE}`}
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function SessionSkeletons() {
  return (
    <div className="mt-6">
      <SkeletonCard count={6} heightPx={56} rounded="xl" label="Loading sessions" />
    </div>
  );
}

function EmptyState({ search }: { search: string }) {
  const isNumeric = search !== '' && Number.isInteger(Number(search));
  return (
    <div className="mt-6 rounded-2xl border border-dashed border-surface-border bg-surface p-12 text-center">
      <p className="text-sm font-medium text-ink-primary">
        {search
          ? isNumeric
            ? `No sessions match #${search}.`
            : `No sessions include an account matching "${search}".`
          : 'No sessions yet.'}
      </p>
      <p className="mt-1 text-xs text-ink-secondary">
        {search
          ? 'Try a different search term or clear the field.'
          : 'When a phone finalizes its first session it will appear here.'}
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

type SessionGroup = {
  monthKey: string;
  monthLabel: string;
  sessions: ScanSessionRow[];
};

const MONTH_LABEL_FMT = new Intl.DateTimeFormat(undefined, {
  month: 'long',
  year: 'numeric',
});

function groupSessionsByMonth(rows: ScanSessionRow[]): SessionGroup[] {
  const groups: SessionGroup[] = [];
  let current: SessionGroup | null = null;
  for (const session of rows) {
    const anchor = session.end_time ?? session.start_time;
    if (!anchor) continue;
    const d = new Date(anchor);
    if (Number.isNaN(d.getTime())) continue;
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
    if (!current || current.monthKey !== key) {
      current = {
        monthKey: key,
        monthLabel: MONTH_LABEL_FMT.format(d),
        sessions: [],
      };
      groups.push(current);
    }
    current.sessions.push(session);
  }
  return groups;
}

function FragmentGroup({
  monthLabel,
  sessions,
  onOpen,
}: {
  monthLabel: string;
  sessions: ScanSessionRow[];
  onOpen: (id: string) => void;
}) {
  return (
    <>
      <tr className="bg-surface-alt/60">
        <th
          scope="colgroup"
          colSpan={6}
          className="px-4 py-2 text-left text-[11px] font-semibold uppercase tracking-wider text-ink-secondary"
        >
          {monthLabel}
        </th>
      </tr>
      {sessions.map((session) => (
        <SessionRow key={session.id} session={session} onOpen={onOpen} />
      ))}
    </>
  );
}

function SessionRow({
  session,
  onOpen,
}: {
  session: ScanSessionRow;
  onOpen: (id: string) => void;
}) {
  // Whole-row link: role=link + keyboard Enter/Space activation. Uses
  // a cursor-pointer + focus ring for discoverability. Nested Link is
  // avoided (HTML: <a> cannot contain <td>). The visible #N is still
  // rendered as the primary underlined label so the row LOOKS like a
  // link on hover; the click surface just extends across all cells.
  const handleKey = (e: React.KeyboardEvent<HTMLTableRowElement>) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      onOpen(session.id);
    }
  };
  return (
    <tr
      role="link"
      tabIndex={0}
      aria-label={`Open session #${session.display_number}`}
      onClick={() => onOpen(session.id)}
      onKeyDown={handleKey}
      className="cursor-pointer border-b border-surface-border transition-colors duration-150 last:border-b-0 hover:bg-surface-alt focus:bg-surface-alt focus:outline-none focus-visible:ring-2 focus-visible:ring-primary/60 focus-visible:ring-inset"
    >
      <td className="px-4 py-3 align-middle">
        <span className="font-semibold text-primary-dark">
          #{session.display_number}
        </span>
      </td>
      <td className="px-4 py-3 align-middle text-ink-primary">
        {session.operator_name || <span className="text-ink-muted">—</span>}
      </td>
      <td className="px-4 py-3 align-middle text-right font-mono tabular-nums text-ink-primary">
        {formatNumber(session.total_lots)}
      </td>
      <td className="px-4 py-3 align-middle text-right font-mono tabular-nums text-ink-primary">
        {formatNumber(session.total_rd_numbers)}
      </td>
      <td className="px-4 py-3 align-middle text-right">
        {session.default_count > 0 ? (
          <span className="inline-flex items-center rounded-pill bg-warn/15 px-2.5 py-0.5 text-xs font-semibold tabular-nums text-warn">
            {formatNumber(session.default_count)}
          </span>
        ) : (
          <span className="tabular-nums text-ink-muted">0</span>
        )}
      </td>
      <td className="px-4 py-3 align-middle">
        <span title={formatDateTime(session.end_time)} className="text-ink-secondary">
          {formatRelativeTime(session.end_time)}
        </span>
      </td>
    </tr>
  );
}
