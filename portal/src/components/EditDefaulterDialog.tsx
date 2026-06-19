import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import {
  autoWindow,
  formatExport,
  fromIso,
  monthYearToToken,
  parseList,
  type MonthYear,
} from '../lib/monthYear';
import { updateRdNumberMonths } from '../lib/queries';
import type { RdNumberRow } from '../types/db';

const MONTHS_MIN = 1;
const MONTHS_MAX = 36;

interface Props {
  rd: RdNumberRow;
  lotTimestamp: string;
  onClose: () => void;
}

/**
 * Mirrors the phone's DefaulterDialog UX: pick months_paid (1-36) and
 * select which specific YYYY-MM months were paid. Defaults to the
 * auto-window (N most-recent months ending at the LOT's date) when no
 * months_list is stored or the count changes.
 *
 * Writes hit rd_numbers via Supabase update; the server-side
 * updated_at trigger lifts updatedAt so the LWW filter at the phone's
 * mergeFromCloud picks the cloud value as winner. Phones with realtime
 * subscribed receive the change within seconds; phones in
 * foreground-poll catch it within 5 min.
 */
export function EditDefaulterDialog({ rd, lotTimestamp, onClose }: Props) {
  const qc = useQueryClient();
  const anchor = useMemo(() => fromIso(lotTimestamp), [lotTimestamp]);

  const initialList = useMemo<MonthYear[]>(
    () => parseList(rd.months_list, rd.months_paid) ?? autoWindow(rd.months_paid, anchor),
    [rd.months_list, rd.months_paid, anchor]
  );

  const [monthsPaid, setMonthsPaid] = useState<number>(rd.months_paid);
  const [selected, setSelected] = useState<MonthYear[]>(initialList);

  useEffect(() => {
    // Adjust the selection size when the slider changes. Keep the
    // user's prefix so existing picks survive a one-step increment;
    // pad with the auto-window tail for new slots.
    if (selected.length === monthsPaid) return;
    if (monthsPaid > selected.length) {
      const additions = autoWindow(monthsPaid, anchor).slice(selected.length);
      setSelected([...selected, ...additions]);
    } else {
      setSelected(selected.slice(0, monthsPaid));
    }
  }, [monthsPaid, selected, anchor]);

  const mutation = useMutation({
    mutationFn: () =>
      updateRdNumberMonths({
        id: rd.id,
        monthsPaid,
        monthsList: monthsPaid > 1 ? selected.map(monthYearToToken).join(',') : null,
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['rd'] });
      qc.invalidateQueries({ queryKey: ['rd-search'] });
      qc.invalidateQueries({ queryKey: ['sessions'] });
      onClose();
    },
  });

  const candidateMonths = useMemo(() => buildCandidateGrid(anchor), [anchor]);

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="defaulter-dialog-title"
      className="fixed inset-0 z-50 flex items-end justify-center bg-ink-primary/40 p-0 backdrop-blur-sm sm:items-center sm:p-4"
      onClick={onClose}
    >
      <div
        className="w-full max-w-lg overflow-hidden rounded-t-2xl bg-surface shadow-elevated sm:rounded-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <header className="border-b border-surface-border px-5 py-4">
          <p className="text-xs font-medium uppercase tracking-wider text-ink-muted">
            RD #{rd.number}
          </p>
          <h2
            id="defaulter-dialog-title"
            className="mt-0.5 text-base font-semibold text-ink-primary"
          >
            Edit defaulter months
          </h2>
        </header>

        <div className="space-y-5 px-5 py-5">
          <div>
            <div className="flex items-center justify-between">
              <label
                htmlFor="months-paid-slider"
                className="text-xs font-medium text-ink-secondary"
              >
                Months paid
              </label>
              <span className="font-mono text-sm font-semibold text-primary-dark">
                {monthsPaid}
              </span>
            </div>
            <input
              id="months-paid-slider"
              type="range"
              min={MONTHS_MIN}
              max={MONTHS_MAX}
              value={monthsPaid}
              onChange={(e) => setMonthsPaid(Number(e.target.value))}
              className="mt-2 w-full accent-primary"
            />
            <div className="mt-1 flex justify-between text-[10px] text-ink-muted">
              <span>{MONTHS_MIN}</span>
              <span>{MONTHS_MAX}</span>
            </div>
          </div>

          {monthsPaid > 1 && (
            <div>
              <p className="text-xs font-medium text-ink-secondary">Which months?</p>
              <p className="mt-0.5 text-[11px] text-ink-muted">
                Tap to select {monthsPaid} month{monthsPaid === 1 ? '' : 's'}. Current
                pick: {selected.length}/{monthsPaid}.
              </p>
              <div className="mt-3 grid max-h-56 grid-cols-3 gap-1.5 overflow-y-auto sm:grid-cols-4">
                {candidateMonths.map((cand) => {
                  const picked = selected.some(
                    (m) => m.year === cand.year && m.month === cand.month
                  );
                  return (
                    <button
                      key={`${cand.year}-${cand.month}`}
                      type="button"
                      onClick={() =>
                        toggleMonth(selected, cand, monthsPaid, setSelected)
                      }
                      className={
                        'rounded-pill border px-2 py-1 text-[11px] font-medium transition-colors ' +
                        (picked
                          ? 'border-primary bg-primary/10 text-primary-dark'
                          : 'border-surface-border bg-surface text-ink-secondary hover:border-ink-secondary')
                      }
                    >
                      {formatExport(cand)}
                    </button>
                  );
                })}
              </div>
            </div>
          )}

          {mutation.isError && (
            <div className="rounded-xl border border-danger/20 bg-danger/5 px-3 py-2 text-xs text-danger">
              {mutation.error instanceof Error
                ? mutation.error.message
                : 'Save failed.'}
            </div>
          )}
        </div>

        <footer className="flex items-center justify-end gap-2 border-t border-surface-border bg-surface-alt px-5 py-3">
          <button
            type="button"
            onClick={onClose}
            disabled={mutation.isPending}
            className="rounded-pill px-3.5 py-1.5 text-xs font-medium text-ink-secondary hover:text-ink-primary"
          >
            Cancel
          </button>
          <button
            type="button"
            disabled={mutation.isPending || (monthsPaid > 1 && selected.length !== monthsPaid)}
            onClick={() => mutation.mutate()}
            className="rounded-pill bg-primary px-4 py-1.5 text-xs font-semibold text-white shadow-card transition-colors hover:bg-primary-dark disabled:cursor-not-allowed disabled:opacity-50"
          >
            {mutation.isPending ? 'Saving…' : 'Save'}
          </button>
        </footer>
      </div>
    </div>
  );
}

function toggleMonth(
  current: MonthYear[],
  cand: MonthYear,
  cap: number,
  set: (next: MonthYear[]) => void
) {
  const idx = current.findIndex(
    (m) => m.year === cand.year && m.month === cand.month
  );
  if (idx >= 0) {
    set([...current.slice(0, idx), ...current.slice(idx + 1)]);
    return;
  }
  if (current.length >= cap) {
    set([...current.slice(1), cand]);
    return;
  }
  set([...current, cand]);
}

function buildCandidateGrid(anchor: MonthYear): MonthYear[] {
  // 36-month candidate strip ending at the anchor month so the user can
  // pick any month within a 3-year window around the LOT date.
  const grid: MonthYear[] = [];
  let cursor = { ...anchor };
  for (let i = 0; i < 36; i++) {
    grid.push(cursor);
    cursor =
      cursor.month === 1
        ? { year: cursor.year - 1, month: 12 }
        : { year: cursor.year, month: cursor.month - 1 };
  }
  return grid;
}
