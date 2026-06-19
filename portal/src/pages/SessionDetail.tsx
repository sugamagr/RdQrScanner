import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { PageHeader } from '../components/PageHeader';
import {
  fetchLotsForSession,
  fetchRdNumbersForLots,
  fetchSession,
} from '../lib/queries';
import { formatDateTime, formatNumber, formatRelativeTime } from '../lib/format';
import type { RdNumberRow, ScanLotRow, ScanSessionRow } from '../types/db';
import { buildSessionXlsx, triggerDownload } from '../lib/xlsx';
import { EditDefaulterDialog } from '../components/EditDefaulterDialog';

export function SessionDetailPage() {
  const { sessionId } = useParams<{ sessionId: string }>();

  const sessionQuery = useQuery({
    queryKey: ['session', sessionId],
    queryFn: () => fetchSession(sessionId as string),
    enabled: !!sessionId,
  });

  const lotsQuery = useQuery({
    queryKey: ['lots', sessionId],
    queryFn: () => fetchLotsForSession(sessionId as string),
    enabled: !!sessionId,
  });

  const lotIds = useMemo(() => lotsQuery.data?.map((l) => l.id) ?? [], [lotsQuery.data]);
  const rdQuery = useQuery({
    queryKey: ['rd', sessionId, lotIds],
    queryFn: () => fetchRdNumbersForLots(lotIds),
    enabled: lotIds.length > 0,
  });

  const rdByLot = useMemo(() => {
    const map = new Map<string, RdNumberRow[]>();
    for (const rd of rdQuery.data ?? []) {
      const list = map.get(rd.lot_id) ?? [];
      list.push(rd);
      map.set(rd.lot_id, list);
    }
    return map;
  }, [rdQuery.data]);

  const session = sessionQuery.data;
  const isLoading = sessionQuery.isLoading || lotsQuery.isLoading;
  const [exportError, setExportError] = useState<string | null>(null);
  const [editing, setEditing] = useState<{ rd: RdNumberRow; lotTimestamp: string } | null>(null);
  const canExport =
    !!session && (lotsQuery.data?.length ?? 0) > 0 && !rdQuery.isLoading;

  const handleExport = () => {
    if (!session || !lotsQuery.data) return;
    setExportError(null);
    try {
      const bytes = buildSessionXlsx({
        sessionDisplayNumber: session.display_number,
        lots: lotsQuery.data,
        rdNumbersByLotId: rdByLot,
      });
      const filename = `RD_Session_${session.display_number}_${Date.now()}.xlsx`;
      triggerDownload(bytes, filename);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Export failed.';
      setExportError(msg);
    }
  };

  if (!sessionId) {
    return <NotFound />;
  }

  return (
    <div>
      <div className="mb-2">
        <Link
          to="/sessions"
          className="text-xs font-medium text-ink-secondary hover:text-ink-primary"
        >
          ← Back to sessions
        </Link>
      </div>
      <PageHeader
        title={session ? `Session #${session.display_number}` : 'Session'}
        subtitle={
          session
            ? `${session.operator_name || 'Unknown operator'} · ${formatRelativeTime(session.end_time)}`
            : isLoading
              ? 'Loading…'
              : 'Session not found.'
        }
        action={
          session && (
            <button
              type="button"
              onClick={handleExport}
              disabled={!canExport}
              className="rounded-pill bg-primary px-3.5 py-1.5 text-xs font-semibold text-white shadow-card transition-colors hover:bg-primary-dark disabled:cursor-not-allowed disabled:opacity-50"
            >
              {rdQuery.isLoading ? 'Preparing…' : 'Export XLSX'}
            </button>
          )
        }
      />

      {sessionQuery.isError && (
        <ErrorBox
          message={
            sessionQuery.error instanceof Error
              ? sessionQuery.error.message
              : 'Failed to load session.'
          }
        />
      )}

      {exportError && (
        <div className="mt-4 rounded-2xl border border-danger/20 bg-danger/5 p-3 text-xs text-danger">
          Export failed: {exportError}
        </div>
      )}

      {!isLoading && !session && !sessionQuery.isError && <NotFound />}

      {session && (
        <>
          <SessionMeta session={session} totalLots={lotsQuery.data?.length ?? 0} />

          <div className="mt-6 space-y-3">
            {lotsQuery.data?.map((lot) => (
              <LotCard
                key={lot.id}
                lot={lot}
                rdNumbers={rdByLot.get(lot.id) ?? []}
                rdLoading={rdQuery.isLoading && lotIds.length > 0}
                onEditRd={(rd) => setEditing({ rd, lotTimestamp: lot.timestamp })}
              />
            ))}
            {lotsQuery.data && lotsQuery.data.length === 0 && (
              <div className="rounded-2xl border border-dashed border-surface-border bg-surface p-8 text-center">
                <p className="text-sm text-ink-secondary">
                  This session has no LOTs. It was likely finalized empty.
                </p>
              </div>
            )}
          </div>
        </>
      )}

      {editing && (
        <EditDefaulterDialog
          rd={editing.rd}
          lotTimestamp={editing.lotTimestamp}
          onClose={() => setEditing(null)}
        />
      )}
    </div>
  );
}

function SessionMeta({
  session,
  totalLots,
}: {
  session: ScanSessionRow;
  totalLots: number;
}) {
  const items = [
    { label: 'Started', value: formatDateTime(session.start_time) },
    { label: 'Ended', value: formatDateTime(session.end_time) },
    { label: 'LOTs', value: formatNumber(session.total_lots || totalLots) },
    { label: 'RD numbers', value: formatNumber(session.total_rd_numbers) },
    {
      label: 'Defaulters',
      value: formatNumber(session.default_count),
      accent: session.default_count > 0,
    },
  ];

  return (
    <div className="mt-6 grid grid-cols-2 gap-3 rounded-2xl border border-surface-border bg-surface p-4 shadow-card sm:grid-cols-5">
      {items.map((item) => (
        <div key={item.label}>
          <p className="text-[10px] font-medium uppercase tracking-wider text-ink-muted">
            {item.label}
          </p>
          <p
            className={`mt-0.5 text-base font-semibold ${
              item.accent ? 'text-warn' : 'text-ink-primary'
            }`}
          >
            {item.value}
          </p>
        </div>
      ))}
    </div>
  );
}

function LotCard({
  lot,
  rdNumbers,
  rdLoading,
  onEditRd,
}: {
  lot: ScanLotRow;
  rdNumbers: RdNumberRow[];
  rdLoading: boolean;
  onEditRd: (rd: RdNumberRow) => void;
}) {
  const defaulters = rdNumbers.filter((rd) => rd.months_paid > 1);
  return (
    <div className="rounded-2xl border border-surface-border bg-surface shadow-card transition-colors duration-150 hover:border-surface-border/80">
      <div className="flex items-center justify-between border-b border-surface-border px-4 py-3">
        <div>
          <p className="text-sm font-semibold text-ink-primary">
            LOT #{lot.lot_number}
          </p>
          <p className="text-xs text-ink-secondary">
            {formatRelativeTime(lot.timestamp)}
          </p>
        </div>
        <div className="text-right text-xs text-ink-secondary">
          <p>
            <span className="font-mono text-ink-primary">{rdNumbers.length}</span>{' '}
            RD number{rdNumbers.length === 1 ? '' : 's'}
          </p>
          {defaulters.length > 0 && (
            <p className="mt-0.5 text-warn">
              {defaulters.length} defaulter{defaulters.length === 1 ? '' : 's'}
            </p>
          )}
        </div>
      </div>

      <div className="p-4">
        {rdLoading && rdNumbers.length === 0 && (
          <div className="space-y-1.5">
            <div className="h-6 animate-pulse rounded bg-surface-alt" />
            <div className="h-6 w-3/4 animate-pulse rounded bg-surface-alt" />
          </div>
        )}
        {rdNumbers.length > 0 && (
          <div className="flex flex-wrap gap-1.5">
            {rdNumbers.map((rd) => {
              const isDefaulter = rd.months_paid > 1;
              return (
                <button
                  key={rd.id}
                  type="button"
                  onClick={() => onEditRd(rd)}
                  title={
                    isDefaulter
                      ? `Defaulter · ${rd.months_paid} months${
                          rd.months_list ? ` (${rd.months_list})` : ''
                        } · Tap to edit`
                      : 'Tap to mark as defaulter'
                  }
                  aria-label={
                    isDefaulter
                      ? `Edit defaulter ${rd.number}, ${rd.months_paid} months`
                      : `Mark ${rd.number} as defaulter`
                  }
                  className={`inline-flex items-center rounded-pill px-2.5 py-0.5 font-mono text-xs transition-all active:scale-[0.98] [@media(hover:hover)]:hover:scale-[1.03] [@media(hover:hover)]:hover:shadow-card ${
                    isDefaulter
                      ? 'bg-warn/15 text-warn ring-1 ring-warn/20'
                      : 'bg-surface-alt text-ink-primary [@media(hover:hover)]:hover:bg-primary/10 [@media(hover:hover)]:hover:text-primary-dark'
                  }`}
                >
                  {rd.number}
                  {isDefaulter && (
                    <span className="ml-1 font-sans text-[10px] font-semibold">
                      ×{rd.months_paid}
                    </span>
                  )}
                </button>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}

function NotFound() {
  return (
    <div className="mt-6 rounded-2xl border border-dashed border-surface-border bg-surface p-12 text-center">
      <p className="text-sm font-medium text-ink-primary">Session not found.</p>
      <p className="mt-1 text-xs text-ink-secondary">
        It may have been deleted or you are signed in with a different account.
      </p>
      <Link
        to="/sessions"
        className="mt-4 inline-flex rounded-pill border border-surface-border bg-surface px-3.5 py-1.5 text-xs font-medium text-ink-primary hover:border-primary hover:text-primary"
      >
        Back to sessions
      </Link>
    </div>
  );
}

function ErrorBox({ message }: { message: string }) {
  return (
    <div className="mt-6 rounded-2xl border border-danger/20 bg-danger/5 p-6 text-center">
      <p className="text-sm font-medium text-danger">{message}</p>
    </div>
  );
}
