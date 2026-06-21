import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import {
  autoWindow,
  formatExport,
  fromIso,
  minusOneMonth,
  monthYearToToken,
  parseList,
  plusOneMonth,
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
  const prevMonthsPaidRef = useRef(rd.months_paid);

  useEffect(() => {
    // Only adjust selection size when the SLIDER changes — never as a
    // reaction to selection edits. Earlier version included `selected`
    // in deps; after every user deselect the effect re-padded with an
    // auto-window month, defeating intent (user clicked X to remove,
    // the effect put X right back). prevMonthsPaidRef gates so the
    // pad/trim only fires on actual monthsPaid transitions.
    if (prevMonthsPaidRef.current === monthsPaid) return;
    prevMonthsPaidRef.current = monthsPaid;
    setSelected((prev) => {
      if (prev.length === monthsPaid) return prev;
      if (monthsPaid > prev.length) {
        const additions = autoWindow(monthsPaid, anchor).slice(prev.length);
        return [...prev, ...additions];
      }
      return prev.slice(0, monthsPaid);
    });
  }, [monthsPaid, anchor]);

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

  // Focus management + ESC + Tab-cycling focus trap per WAI-ARIA modal
  // pattern. Wave 2 oracle finding bg_53bf3b2b W1 + Phase 5 F10:
  //  - remembers the opener so focus returns on close
  //  - traps Tab/Shift+Tab inside the dialog (F10 — Tab walking out of
  //    a modal is a long-standing a11y bug; screen readers + keyboard
  //    users get lost)
  const dialogRef = useRef<HTMLDivElement>(null);
  const closeBtnRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    closeBtnRef.current?.focus();
    const focusableSelector =
      'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        onClose();
        return;
      }
      if (e.key !== 'Tab') return;
      const root = dialogRef.current;
      if (!root) return;
      const focusables = Array.from(
        root.querySelectorAll<HTMLElement>(focusableSelector)
      ).filter((el) => el.offsetParent !== null);
      if (focusables.length === 0) return;
      const first = focusables[0];
      const last = focusables[focusables.length - 1];
      const active = document.activeElement as HTMLElement | null;
      if (e.shiftKey && active === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && active === last) {
        e.preventDefault();
        first.focus();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
      opener?.focus();
    };
  }, [onClose]);

  const remainingPicks = monthsPaid > 1 ? monthsPaid - selected.length : 0;
  const saveDisabled = mutation.isPending || (monthsPaid > 1 && selected.length !== monthsPaid);

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="defaulter-dialog-title"
      className="fixed inset-0 z-50 flex items-end justify-center bg-ink-primary/40 p-0 backdrop-blur-sm sm:items-center sm:p-4"
      onClick={onClose}
    >
      <div
        ref={dialogRef}
        className="flex max-h-[100dvh] w-full max-w-lg flex-col overflow-hidden rounded-t-2xl bg-surface shadow-elevated sm:max-h-[90dvh] sm:rounded-2xl"
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

        <div className="flex-1 space-y-5 overflow-y-auto px-5 py-5">
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
                Newest at top, older below. The LOT month is outlined. Pick
                {' '}{monthsPaid} — currently {selected.length}/{monthsPaid}.
              </p>
              <div className="mt-3 flex items-center justify-between text-[10px] uppercase tracking-wider text-ink-muted">
                <span>Future</span>
                <span>Past →</span>
              </div>
              <div className="mt-2 grid max-h-56 grid-cols-3 gap-1.5 overflow-y-auto sm:grid-cols-4">
                {candidateMonths.map((cand) => {
                  const picked = selected.some(
                    (m) => m.year === cand.year && m.month === cand.month
                  );
                  const isAnchor =
                    cand.year === anchor.year && cand.month === anchor.month;
                  const base =
                    'rounded-pill border px-2 py-1 text-[11px] font-medium transition-colors';
                  const styles = picked
                    ? 'border-primary bg-primary/10 text-primary-dark'
                    : isAnchor
                      ? 'border-primary/40 bg-surface text-ink-primary hover:border-primary/70'
                      : 'border-surface-border bg-surface text-ink-secondary hover:border-ink-secondary';
                  return (
                    <button
                      key={`${cand.year}-${cand.month}`}
                      type="button"
                      onClick={() =>
                        toggleMonth(selected, cand, monthsPaid, setSelected)
                      }
                      className={`${base} ${styles}`}
                      title={isAnchor ? 'LOT month' : undefined}
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

        <footer className="flex items-center justify-between gap-2 border-t border-surface-border bg-surface-alt px-5 py-3">
          <span
            className="text-[11px] text-ink-muted"
            aria-live="polite"
          >
            {monthsPaid > 1 && remainingPicks > 0
              ? `Pick ${remainingPicks} more month${remainingPicks === 1 ? '' : 's'} to save.`
              : monthsPaid > 1 && selected.length > monthsPaid
                ? `Remove ${selected.length - monthsPaid} to save.`
                : ''}
          </span>
          <div className="flex items-center gap-2">
            <button
              ref={closeBtnRef}
              type="button"
              onClick={onClose}
              disabled={mutation.isPending}
              className="rounded-pill px-3.5 py-1.5 text-xs font-medium text-ink-secondary hover:text-ink-primary"
            >
              Cancel
            </button>
            <button
              type="button"
              disabled={saveDisabled}
              onClick={() => mutation.mutate()}
              className="rounded-pill bg-primary px-4 py-1.5 text-xs font-semibold text-white shadow-card transition-colors hover:bg-primary-dark disabled:cursor-not-allowed disabled:opacity-50"
            >
              {mutation.isPending ? 'Saving…' : 'Save'}
            </button>
          </div>
        </footer>
      </div>
    </div>
  );
}

/**
 * Toggles a candidate month in the selection.
 *
 * Behavior:
 *  - If already picked: deselect (always allowed; user might want to swap
 *    one of the auto-window defaults for a different month).
 *  - If not picked and under cap: add.
 *  - If not picked and AT cap: no-op. The footer hint already tells the
 *    user "Remove 1 to save." — silently FIFO-evicting the oldest pick
 *    confused users into thinking the click did nothing (they couldn't
 *    see the off-screen eviction).
 */
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
  if (current.length >= cap) return;
  set([...current, cand]);
}

function buildCandidateGrid(anchor: MonthYear): MonthYear[] {
  // [anchor - 18, anchor + 18] inclusive = 37 candidates. Supports
  // prepayment (future months) per user request. Chronological with
  // newest first so the visible top-left is the latest future month;
  // scrolling down walks backward in time toward the past tail.
  const grid: MonthYear[] = [];
  let cursor = { ...anchor };
  for (let i = 0; i < MONTHS_FORWARD; i++) {
    cursor = plusOneMonth(cursor);
  }
  for (let i = 0; i < MONTHS_BACK + 1 + MONTHS_FORWARD; i++) {
    grid.push(cursor);
    cursor = minusOneMonth(cursor);
  }
  return grid;
}

const MONTHS_BACK = 18;
const MONTHS_FORWARD = 18;
