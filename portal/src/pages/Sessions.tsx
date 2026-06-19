import { useState } from 'react';
import { useInfiniteQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { PageHeader } from '../components/PageHeader';
import {
  SESSIONS_PAGE_SIZE,
  fetchSessionsPage,
  type SessionsPage,
} from '../lib/queries';
import { formatDateTime, formatNumber, formatRelativeTime } from '../lib/format';

export function SessionsPage() {
  const [searchInput, setSearchInput] = useState('');
  const [committedSearch, setCommittedSearch] = useState('');

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
              setCommittedSearch(searchInput);
            }}
            className="flex items-center gap-2"
          >
            <input
              type="text"
              inputMode="numeric"
              placeholder="Search by session #"
              value={searchInput}
              onChange={(e) => setSearchInput(e.target.value)}
              className="w-44 rounded-pill border border-surface-border bg-surface px-3.5 py-1.5 text-sm placeholder:text-ink-muted sm:w-56"
            />
            {committedSearch && (
              <button
                type="button"
                onClick={() => {
                  setSearchInput('');
                  setCommittedSearch('');
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
        <div className="mt-6 overflow-hidden rounded-2xl border border-surface-border bg-surface shadow-card">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-surface-border bg-surface-alt text-xs uppercase tracking-wide text-ink-secondary">
              <tr>
                <th className="px-4 py-3 font-medium">#</th>
                <th className="px-4 py-3 font-medium">Operator</th>
                <th className="px-4 py-3 font-medium">LOTs</th>
                <th className="px-4 py-3 font-medium">RD numbers</th>
                <th className="px-4 py-3 font-medium">Defaulters</th>
                <th className="px-4 py-3 font-medium">Ended</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((session) => (
                <tr
                  key={session.id}
                  className="border-b border-surface-border last:border-b-0 hover:bg-surface-alt"
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
                  <td className="px-4 py-3 align-middle font-mono text-ink-primary">
                    {formatNumber(session.total_lots)}
                  </td>
                  <td className="px-4 py-3 align-middle font-mono text-ink-primary">
                    {formatNumber(session.total_rd_numbers)}
                  </td>
                  <td className="px-4 py-3 align-middle">
                    {session.default_count > 0 ? (
                      <span className="inline-flex items-center rounded-pill bg-warn/10 px-2.5 py-0.5 text-xs font-medium text-warn">
                        {formatNumber(session.default_count)}
                      </span>
                    ) : (
                      <span className="text-ink-muted">0</span>
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
