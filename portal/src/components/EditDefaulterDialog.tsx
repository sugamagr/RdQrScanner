import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
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
import {
  fetchAccountForRdNumber,
  fetchLotTotalsExcluding,
  updateRdNumberMonths,
} from '../lib/queries';
import type { RdNumberRow } from '../types/db';

const MONTHS_MIN = 1;
const MONTHS_MAX = 36;
const LOT_TOTAL_LIMIT_RUPEES = 20_000;

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

  const initialList = useMemo<MonthYear[]>(() => {
    // Coerce any stored months_list into a contiguous block ending at
    // the newest stored month. RD payments are sequential by definition
    // — gappy persisted lists from the old toggle UI get repaired here
    // by re-anchoring on max(month) and rebuilding the block.
    const stored = parseList(rd.months_list, rd.months_paid)
    if (stored != null && stored.length === rd.months_paid) {
      const newest = stored.reduce((acc, m) =>
        m.year > acc.year || (m.year === acc.year && m.month > acc.month) ? m : acc
      )
      return buildBlockEndingAt(newest, rd.months_paid)
    }
    return autoWindow(rd.months_paid, anchor)
  }, [rd.months_list, rd.months_paid, anchor]);

  const [monthsPaid, setMonthsPaid] = useState<number>(rd.months_paid);
  const [selected, setSelected] = useState<MonthYear[]>(initialList);
  const prevMonthsPaidRef = useRef(rd.months_paid);

  useEffect(() => {
    // On slider change, preserve the anchor (newest month) and rebuild
    // the contiguous block at the new length. No more pad/trim — that
    // could produce gappy selections under the old model, and the new
    // model has no concept of "partial selection" to repair.
    if (prevMonthsPaidRef.current === monthsPaid) return;
    prevMonthsPaidRef.current = monthsPaid;
    setSelected((prev) => {
      const anchorMonth = prev[0] ?? anchor;
      return buildBlockEndingAt(anchorMonth, monthsPaid);
    });
  }, [monthsPaid, anchor]);

  // Cap-enforcement state. Fetch the rest-of-LOT verified total ONCE
  // on mount (the other rows' months_paid won't change inside this
  // dialog) and the THIS row's account monthly_amount ONCE. Then the
  // live cap check is pure arithmetic in render — no extra round trips
  // as the operator slides months_paid.
  const restOfLotTotals = useQuery({
    queryKey: ['lot-totals-excluding', rd.lot_id, rd.id],
    queryFn: () => fetchLotTotalsExcluding({ lotId: rd.lot_id, excludeRdId: rd.id }),
    staleTime: 60_000,
  });
  const ownAccount = useQuery({
    queryKey: ['account-for-rd', rd.number],
    queryFn: () => fetchAccountForRdNumber(rd.number),
    staleTime: 60_000,
  });

  // Live verified rupees if this save lands: rest-of-LOT verified
  // (unchanged) + this row's monthly_amount × pending months_paid. If
  // the account profile is missing, this row contributes zero to the
  // verified total but the unverifiedCount goes up by 1 (matches the
  // phone's LiveLotTotal semantic).
  const ownMonthlyAmount = ownAccount.data?.monthly_amount ?? null;
  const restTotals = restOfLotTotals.data ?? { verifiedRupees: 0, unverifiedCount: 0 };
  const pendingVerifiedRupees =
    restTotals.verifiedRupees +
    (ownMonthlyAmount != null && ownMonthlyAmount > 0 ? ownMonthlyAmount * monthsPaid : 0);
  const pendingUnverifiedCount =
    restTotals.unverifiedCount + (ownMonthlyAmount != null && ownMonthlyAmount > 0 ? 0 : 1);
  const isOverCap = pendingVerifiedRupees > LOT_TOTAL_LIMIT_RUPEES;
  const totalsLoading = restOfLotTotals.isLoading || ownAccount.isLoading;

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
      qc.invalidateQueries({ queryKey: ['lot-totals-excluding'] });
      onClose();
    },
  });

  const inFlightRef = useRef(false);
  const handleSave = () => {
    if (mutation.isPending || inFlightRef.current) return;
    inFlightRef.current = true;
    mutation.mutate(undefined, {
      onSettled: () => {
        inFlightRef.current = false;
      },
    });
  };

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

  // Save blocked by (a) in-flight mutation, (b) over-cap state (spec
  // §15.5.12 / D24 — phone enforces the same boundary so portal MUST
  // mirror or the phone gets stuck on subsequent edits), or (c) totals
  // still loading (don't let the user save with an unverified cap).
  const saveDisabled = mutation.isPending || isOverCap || totalsLoading;
  const blockLabel = monthsPaid > 1 && selected.length === monthsPaid
    ? `${formatExport(selected[selected.length - 1])} – ${formatExport(selected[0])}`
    : '';

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
                Tap any month to anchor a {monthsPaid}-month block ending
                there. RD payments are sequential — picks can't skip months.
                The LOT month is outlined.
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
                      onClick={() => setSelected(buildBlockEndingAt(cand, monthsPaid))}
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

          {!totalsLoading && (
            <div
              className={
                isOverCap
                  ? 'rounded-xl border border-danger/30 bg-danger/5 px-3 py-2 text-xs text-danger'
                  : 'rounded-xl border border-surface-border bg-surface-alt px-3 py-2 text-xs text-ink-secondary'
              }
              aria-live="polite"
            >
              <div className="flex items-center justify-between">
                <span className="font-medium">LOT total</span>
                <span className="font-mono">
                  ₹{pendingVerifiedRupees.toLocaleString('en-IN')}
                  {' / '}
                  ₹{LOT_TOTAL_LIMIT_RUPEES.toLocaleString('en-IN')}
                </span>
              </div>
              {isOverCap && (
                <p className="mt-1 text-[11px]">
                  Saving here would exceed the ₹{LOT_TOTAL_LIMIT_RUPEES.toLocaleString('en-IN')}{' '}
                  per-LOT cap. Reduce months for this row or another row in
                  the same LOT before saving.
                </p>
              )}
              {pendingUnverifiedCount > 0 && !isOverCap && (
                <p className="mt-1 text-[11px] text-ink-muted">
                  {pendingUnverifiedCount} row
                  {pendingUnverifiedCount === 1 ? '' : 's'} without an account profile
                  not counted — real total may be higher.
                </p>
              )}
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
            {blockLabel}
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
              onClick={handleSave}
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
 * Builds a contiguous N-month block ending at `endMonth` (inclusive),
 * walking BACKWARD in time. RD payments are inherently sequential — the
 * old toggle-each-month UX let users construct gappy selections that
 * didn't reflect any real-world payment pattern. Now one tap anchors
 * the trailing edge and the prior N-1 months autofill.
 *
 * Returned in newest-first order so it matches the grid render
 * direction and the picked-styling comparison stays trivial.
 */
function buildBlockEndingAt(endMonth: MonthYear, count: number): MonthYear[] {
  const out: MonthYear[] = [];
  let cursor = endMonth;
  for (let i = 0; i < count; i++) {
    out.push(cursor);
    cursor = minusOneMonth(cursor);
  }
  return out;
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
