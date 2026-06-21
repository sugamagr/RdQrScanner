import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Loader2, X } from 'lucide-react';
import { updateAccount } from '../lib/queries';
import type { RdAccountRow } from '../types/db';

interface Props {
  account: RdAccountRow;
  onClose: () => void;
}

/**
 * Edit dialog for an rd_accounts row. RD number is locked (PK after
 * creation); Name + Monthly amount + Active toggle are mutable.
 *
 * Submits via `updateAccount` which stamps `last_editor_device_id = null`
 * so the next phone pull renders this edit's origin as Portal in
 * Channel C notifications + the in-app banner (spec §15.5.5).
 */
export function AccountEditDialog({ account, onClose }: Props) {
  const qc = useQueryClient();
  const [name, setName] = useState(account.name);
  const [amount, setAmount] = useState(String(account.monthly_amount));
  const [isActive, setIsActive] = useState(account.is_active);

  const dialogRef = useRef<HTMLDivElement>(null);
  const closeBtnRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    closeBtnRef.current?.focus();
    const focusableSelector =
      'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled])';
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

  const mutation = useMutation({
    mutationFn: () =>
      updateAccount({
        rdNumber: account.rd_number,
        name: name.trim(),
        monthlyAmount: Number(amount.trim()),
        isActive,
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['accounts'] });
      onClose();
    },
  });

  const trimmedName = name.trim();
  const parsedAmount = Number(amount.trim());
  const nameValid = trimmedName.length > 0 && trimmedName.length <= 60;
  const amountValid = Number.isInteger(parsedAmount) && parsedAmount > 0;
  const hasChanges =
    trimmedName !== account.name ||
    parsedAmount !== account.monthly_amount ||
    isActive !== account.is_active;
  const canSave = nameValid && amountValid && hasChanges && !mutation.isPending;

  const inFlightRef = useRef(false);

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    // P6γ NITPICK in-flight guard: mutation.isPending state may not
    // have re-rendered between rapid clicks; the synchronous ref
    // beats React's batched setState. mutation.isPending is also
    // still checked via canSave for UX (button disabled state).
    if (!canSave || inFlightRef.current) return;
    inFlightRef.current = true;
    mutation.mutate(undefined, {
      onSettled: () => {
        inFlightRef.current = false;
      },
    });
  };

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="account-edit-title"
      className="fixed inset-0 z-50 flex items-end justify-center bg-ink-primary/40 p-0 backdrop-blur-sm sm:items-center sm:p-4"
      onClick={onClose}
    >
      <div
        ref={dialogRef}
        className="flex max-h-[100dvh] w-full max-w-md flex-col overflow-hidden rounded-t-2xl bg-surface shadow-elevated sm:max-h-[90dvh] sm:rounded-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <header className="flex items-start justify-between border-b border-surface-border px-5 py-4">
          <div>
            <p className="text-xs font-medium uppercase tracking-wider text-ink-muted">
              RD #{account.rd_number}
            </p>
            <h2
              id="account-edit-title"
              className="mt-0.5 text-base font-semibold text-ink-primary"
            >
              Edit account
            </h2>
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

        <form onSubmit={onSubmit} className="flex-1 space-y-5 overflow-y-auto px-5 py-5">
          <label className="block">
            <span className="text-xs font-medium text-ink-secondary">Customer name</span>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              autoComplete="off"
              maxLength={80}
              className={`mt-1 w-full rounded-xl border bg-surface-alt px-3.5 py-2.5 text-sm text-ink-primary placeholder:text-ink-muted ${
                name && !nameValid
                  ? 'border-danger/60 focus:border-danger'
                  : 'border-surface-border focus:border-primary'
              }`}
              placeholder="Ramesh Kumar"
            />
            {name && !nameValid && (
              <p className="mt-1 text-[11px] text-danger">
                Name must be 1–60 characters.
              </p>
            )}
          </label>

          <label className="block">
            <span className="text-xs font-medium text-ink-secondary">Monthly amount (INR)</span>
            <input
              type="text"
              inputMode="numeric"
              value={amount}
              onChange={(e) => setAmount(e.target.value.replace(/\D/g, ''))}
              autoComplete="off"
              className={`mt-1 w-full rounded-xl border bg-surface-alt px-3.5 py-2.5 text-sm text-ink-primary placeholder:text-ink-muted ${
                amount && !amountValid
                  ? 'border-danger/60 focus:border-danger'
                  : 'border-surface-border focus:border-primary'
              }`}
              placeholder="500"
            />
            {amount && !amountValid && (
              <p className="mt-1 text-[11px] text-danger">Amount must be a positive integer.</p>
            )}
          </label>

          <div className="flex items-center justify-between rounded-xl bg-surface-alt px-4 py-3">
            <div>
              <p className="text-sm font-medium text-ink-primary">Active</p>
              <p className="mt-0.5 text-[11px] text-ink-secondary">
                {isActive
                  ? 'Visible in the default Accounts list.'
                  : 'Hidden until toggled on or scanned.'}
              </p>
            </div>
            <button
              type="button"
              role="switch"
              aria-checked={isActive}
              onClick={() => setIsActive((v) => !v)}
              className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${
                isActive ? 'bg-accent-mint' : 'bg-surface-border'
              }`}
            >
              <span
                className={`inline-block h-5 w-5 transform rounded-full bg-white shadow transition-transform ${
                  isActive ? 'translate-x-5' : 'translate-x-0.5'
                }`}
              />
            </button>
          </div>

          {mutation.isError && (
            <div className="rounded-xl border border-danger/20 bg-danger/5 px-3 py-2 text-xs text-danger">
              {mutation.error instanceof Error ? mutation.error.message : 'Save failed.'}
            </div>
          )}
        </form>

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
            onClick={onSubmit as unknown as () => void}
            disabled={!canSave}
            className="inline-flex items-center gap-1.5 rounded-pill bg-primary px-4 py-1.5 text-xs font-semibold text-white shadow-card transition-colors hover:bg-primary-dark disabled:cursor-not-allowed disabled:opacity-50"
          >
            {mutation.isPending && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
            {mutation.isPending ? 'Saving…' : 'Save'}
          </button>
        </footer>
      </div>
    </div>
  );
}
