import { useDeferredValue, useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { PageHeader } from '../components/PageHeader';
import { searchRdNumbers, type RdSearchHit } from '../lib/queries';
import { formatRelativeTime } from '../lib/format';

export function SearchPage() {
  const [input, setInput] = useState('');
  const deferred = useDeferredValue(input);
  const [debounced, setDebounced] = useState('');

  useEffect(() => {
    const handle = window.setTimeout(() => setDebounced(deferred.trim()), 220);
    return () => window.clearTimeout(handle);
  }, [deferred]);

  const query = useQuery({
    queryKey: ['rd-search', debounced],
    queryFn: () => searchRdNumbers(debounced),
    enabled: debounced.length >= 2,
    staleTime: 30_000,
  });

  const showResults = debounced.length >= 2;
  const hits = query.data ?? [];

  return (
    <div>
      <PageHeader
        title="Search RD numbers"
        subtitle="Type any portion of an RD number to find which session it lives in."
      />

      <div className="mt-6">
        <label className="block">
          <span className="sr-only">RD number</span>
          <input
            type="text"
            inputMode="numeric"
            autoFocus
            placeholder="e.g. 1234 or last 4 digits"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            className="w-full rounded-2xl border border-surface-border bg-surface px-4 py-3 text-base placeholder:text-ink-muted shadow-card focus:border-primary"
          />
        </label>
        <p className="mt-2 text-xs text-ink-muted">
          {input.length === 0
            ? 'Type at least 2 characters.'
            : input.trim().length < 2
              ? 'Keep typing — at least 2 characters needed.'
              : query.isFetching
                ? 'Searching…'
                : hits.length === 100
                  ? 'Showing first 100 matches — narrow the query for more.'
                  : `${hits.length} match${hits.length === 1 ? '' : 'es'}.`}
        </p>
      </div>

      {query.isError && (
        <div className="mt-4 rounded-2xl border border-danger/20 bg-danger/5 p-4 text-sm text-danger">
          {query.error instanceof Error
            ? query.error.message
            : 'Search failed. Try again.'}
        </div>
      )}

      {showResults && !query.isFetching && hits.length === 0 && !query.isError && (
        <div className="mt-6 rounded-2xl border border-dashed border-surface-border bg-surface p-12 text-center">
          <p className="text-sm font-medium text-ink-primary">
            No RD numbers match "{debounced}".
          </p>
          <p className="mt-1 text-xs text-ink-secondary">
            Try fewer digits or check for typos.
          </p>
        </div>
      )}

      {hits.length > 0 && (
        <ul className="mt-6 space-y-2">
          {hits.map((hit) => (
            <SearchResultRow key={hit.rd.id} hit={hit} highlight={debounced} />
          ))}
        </ul>
      )}
    </div>
  );
}

function SearchResultRow({
  hit,
  highlight,
}: {
  hit: RdSearchHit;
  highlight: string;
}) {
  const isDefaulter = hit.rd.months_paid > 1;
  return (
    <li>
      <Link
        to={`/sessions/${hit.session.id}`}
        className="flex items-center gap-3 rounded-xl border border-surface-border bg-surface px-4 py-3 shadow-card transition-all hover:border-primary/40 hover:bg-surface-alt"
      >
        <div className="flex-1">
          <div className="flex items-center gap-2 font-mono text-base font-semibold text-ink-primary">
            <Highlighted text={hit.rd.number} match={highlight} />
            {isDefaulter && (
              <span className="rounded-pill bg-warn/10 px-2 py-0.5 font-sans text-[10px] font-semibold uppercase tracking-wide text-warn">
                Defaulter · {hit.rd.months_paid} months
              </span>
            )}
          </div>
          <p className="mt-1 text-xs text-ink-secondary">
            Session #{hit.session.display_number} · LOT #{hit.lot.lot_number}
            {hit.session.operator_name ? ` · ${hit.session.operator_name}` : ''}
            {' · '}
            {formatRelativeTime(hit.session.end_time)}
          </p>
        </div>
        <span className="text-xs text-ink-muted">→</span>
      </Link>
    </li>
  );
}

function Highlighted({ text, match }: { text: string; match: string }) {
  if (!match) return <span>{text}</span>;
  const idx = text.toLowerCase().indexOf(match.toLowerCase());
  if (idx === -1) return <span>{text}</span>;
  const before = text.slice(0, idx);
  const hit = text.slice(idx, idx + match.length);
  const after = text.slice(idx + match.length);
  return (
    <span>
      {before}
      <span className="bg-primary/15 text-primary-dark">{hit}</span>
      {after}
    </span>
  );
}
