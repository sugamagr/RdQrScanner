import { useEffect, useState } from 'react';
import { useInfiniteQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { PageHeader } from '../components/PageHeader';
import {
  SESSIONS_PAGE_SIZE,
  fetchSessionsPage,
  type SessionsPage,
} from '../lib/queries';
import { formatDateTime, formatNumber, formatRelativeTime } from '../lib/format';

export function SessionsPage() {
  // Search state is persisted in URL ?q= so browser Back from
  // SessionDetail returns to the same filtered + scrolled view, and
  // the owner can bookmark or share a filtered link. Without this the
  // search box clears on every nav-away even though the Sessions
  // route remembers nothing else either.
  const [searchParams, setSearchParams] = useSearchParams();
  const urlSearch = searchParams.get('q') ?? '';
  const [searchInput, setSearchInput] = useState(urlSearch);
  const [committedSearch, setCommittedSearch] = useState(urlSearch);
  // Resync local state when the URL changes (back/forward navigation
  // or external link). Without this the input shows stale text after
  // a popstate event.
  useEffect(() => {
    setSearchInput(urlSearch);
    setCommittedSearch(urlSearch);
  }, [urlSearch]);
  const commitSearch = (next: string) => {
    setCommittedSearch(next);
    const params = new URLSearchParams(searchParams);
    if (next) {
      params.set('q', next);
    } else {
      params.delete('q');
    }
    setSearchParams(params, { replace: false });
  };

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

  return (
    <div>
      <PageHeader
        title="Sessions"
        subtitle="Every finalized scanning session across all signed-in phones."
        action={
          <form
            onSubmit={(e) => {
              e.preventDefault();
              commitSearch(searchInput);
            }}
            className="flex items-center gap-2"
          >
            <label className="contents">
              <span className="sr-only">Search by session number</span>
              <input
                type="text"
                inputMode="numeric"
                placeholder="Search by session #"
                aria-label="Search by session number"
                value={searchInput}
                onChange={(e) => setSearchInput(e.target.value)}
                className="w-44 rounded-pill border border-surface-border bg-surface px-3.5 py-1.5 text-sm placeholder:text-ink-muted sm:w-56"
              />
            </label>
            {committedSearch && (
              <button
                type="button"
                onClick={() => {
                  setSearchInput('');
                  commitSearch('');
                }}
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
              {rows.map((session) => (
                <tr
                  key={session.id}
                  className="border-b border-surface-border transition-colors duration-150 last:border-b-0 hover:bg-surface-alt"
                >
                  <td className="px-4 py-3 align-middle">
                    <Link
                      to={`/sessions/${session.id}`}
                      className="font-semibold text-primary-dark hover:text-primary"
                    >
                      #{session.display_number}
                    </Link>
                  </td>
                  <td className="px-4 py-3 align-middle text-ink-primary">
                    {session.operator_name || (
                      <span className="text-ink-muted">—</span>
                    )}
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
    <div className="mt-6 space-y-2">
      {Array.from({ length: 6 }).map((_, i) => (
        <div
          key={i}
          className="h-14 animate-pulse rounded-xl border border-surface-border bg-surface-alt"
        />
      ))}
    </div>
  );
}

function EmptyState({ search }: { search: string }) {
  return (
    <div className="mt-6 rounded-2xl border border-dashed border-surface-border bg-surface p-12 text-center">
      <p className="text-sm font-medium text-ink-primary">
        {search ? `No sessions match #${search}.` : 'No sessions yet.'}
      </p>
      <p className="mt-1 text-xs text-ink-secondary">
        {search
          ? 'Try a different number or clear the search.'
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
