import { useEffect, useRef, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Loader2, X } from 'lucide-react';
import { markAccountInactive, softDeleteAccount } from '../lib/queries';
import type { RdAccountRow } from '../types/db';
import { useBackdropClose } from './useBackdropClose';

interface Props {
  account: RdAccountRow;
  onClose: () => void;
}

/**
 * Two-path delete confirmation, copy verbatim from user spec. Primary
 * action is Mark Inactive (recommended for natural close-outs);
 * Delete is the secondary danger path (for "added by mistake").
 */
export function DeleteOrInactivateDialog({ account, onClose }: Props) {
  const qc = useQueryClient();
  const [error, setError] = useState<string | null>(null);

  const dialogRef = useRef<HTMLDivElement>(null);
  const closeBtnRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    closeBtnRef.current?.focus();
    const focusableSelector =
      'button:not([disabled]), [href], input:not([disabled])';
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        onClose();
        return;
      }
      if (e.key !== 'Tab') return;
      const root = dialogRef.current;
      if (!root) return;
      const focusables = Array.from(root.querySelectorAll<HTMLElement>(focusableSelector))
        .filter((el) => el.offsetParent !== null);
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

  const inactivate = useMutation({
    mutationFn: () => markAccountInactive(account.rd_number),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['accounts'] });
      onClose();
    },
    onError: (e) => setError(e instanceof Error ? e.message : String(e)),
  });
  const remove = useMutation({
    mutationFn: () => softDeleteAccount(account.rd_number),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['accounts'] });
      onClose();
    },
    onError: (e) => setError(e instanceof Error ? e.message : String(e)),
  });

  const busy = inactivate.isPending || remove.isPending;

  // P6γ NITPICK mutual-exclusion: the two buttons share the `busy`
  // flag for the disabled state, but React batches setState so a
  // rapid Mark-Inactive → Delete click pair can fire BOTH mutations
  // before isPending propagates to the second button. Synchronous
  // ref guard short-circuits the second click before any network
  // call. The mutual exclusion is semantic (you can't both inactivate
  // AND tombstone a row in the same gesture), not just visual.
  const inFlightRef = useRef(false);
  const handleInactivate = () => {
    if (busy || inFlightRef.current) return;
    inFlightRef.current = true;
    inactivate.mutate(undefined, {
      onSettled: () => {
        inFlightRef.current = false;
      },
    });
  };
  const handleDelete = () => {
    if (busy || inFlightRef.current) return;
    inFlightRef.current = true;
    remove.mutate(undefined, {
      onSettled: () => {
        inFlightRef.current = false;
      },
    });
  };

  const backdropHandlers = useBackdropClose(onClose);
  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="delete-or-inactivate-title"
      className="fixed inset-0 z-50 flex items-end justify-center bg-ink-primary/40 p-0 backdrop-blur-sm sm:items-center sm:p-4"
      {...backdropHandlers}
    >
      <div
        ref={dialogRef}
        className="flex max-h-[100dvh] w-full max-w-md flex-col overflow-hidden rounded-t-2xl bg-surface shadow-elevated sm:max-h-[90dvh] sm:rounded-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <header className="flex items-start justify-between border-b border-surface-border px-5 py-4">
          <div>
            <h2
              id="delete-or-inactivate-title"
              className="text-base font-semibold text-ink-primary"
            >
              Mark inactive or delete?
            </h2>
            <p className="mt-0.5 font-mono text-xs text-ink-secondary">
              {account.name} · RD #{account.rd_number}
            </p>
          </div>
          <button
            ref={closeBtnRef}
            type="button"
            onClick={onClose}
            className="rounded-lg p-1 text-ink-secondary hover:bg-surface-alt hover:text-ink-primary"
            aria-label="Close"
          >
            <X className="h-5 w-5" />
          </button>
        </header>

        <div className="flex-1 space-y-3 overflow-y-auto px-5 py-5 text-sm">
          <p className="text-ink-primary">
            <span className="font-semibold">Mark inactive</span>
            <span className="ml-1.5 inline-flex rounded-pill bg-accent-mint/15 px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wider text-accent-mint-ink">
              Recommended
            </span>
            <span> — the account stays in the system, you can re-activate it any time, and all past payment history is preserved. Pick this when an account closes naturally.</span>
          </p>
          <p className="text-ink-secondary">
            <span className="font-semibold text-danger">Delete</span> — wipes the
            account profile entirely from this phone and the portal. Pick this only
            if you added the account by mistake and want it gone like it never
            existed.
          </p>
          {error && (
            <div className="rounded-xl border border-danger/20 bg-danger/5 px-3 py-2 text-xs text-danger">
              {error}
            </div>
          )}
        </div>

        <footer className="flex flex-col gap-3 border-t border-surface-border bg-surface-alt px-5 py-3 sm:flex-row sm:items-center sm:justify-end sm:gap-2">
          <button
            type="button"
            onClick={handleInactivate}
            disabled={busy}
            className="inline-flex items-center justify-center gap-1.5 rounded-pill bg-primary px-4 py-1.5 text-xs font-semibold text-white shadow-card transition-colors hover:bg-primary-dark disabled:cursor-not-allowed disabled:opacity-50"
          >
            {inactivate.isPending && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
            {inactivate.isPending ? 'Marking…' : 'Mark Inactive'}
          </button>
          <div className="flex items-center justify-end gap-1">
            <button
              type="button"
              onClick={onClose}
              disabled={busy}
              className="rounded-pill px-3.5 py-1.5 text-xs font-medium text-ink-secondary hover:text-ink-primary"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={handleDelete}
              disabled={busy}
              className="inline-flex items-center gap-1.5 rounded-pill px-3.5 py-1.5 text-xs font-semibold text-danger hover:bg-danger/5 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {remove.isPending && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
              {remove.isPending ? 'Deleting…' : 'Delete'}
            </button>
          </div>
        </footer>
      </div>
    </div>
  );
}
