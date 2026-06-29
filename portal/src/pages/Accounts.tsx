import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ArrowDown,
  ArrowUp,
  ArrowUpDown,
  Edit3,
  Lock,
  MoreVertical,
  Search as SearchIcon,
  Upload,
  Users,
  X,
} from 'lucide-react';
import { PageHeader } from '../components/PageHeader';
import { SkeletonCard } from '../components/Loader';
import { AccountEditDialog } from '../components/AccountEditDialog';
import { DeleteOrInactivateDialog } from '../components/DeleteOrInactivateDialog';
import { ImportCsvDialog } from '../components/ImportCsvDialog';
import { fetchAccounts, reactivateAccount } from '../lib/queries';
import { useAuth } from '../lib/useAuth';
import { formatNumber } from '../lib/format';
import type { RdAccountRow } from '../types/db';

type SortKey = 'name' | 'rd_number' | 'monthly_amount' | 'last_paid_through';
type SortDir = 'asc' | 'desc';

export function AccountsPage() {
  const { user } = useAuth();
  const ownerId = user?.id ?? '';
  const qc = useQueryClient();

  const query = useQuery({
    queryKey: ['accounts'],
    queryFn: fetchAccounts,
  });

  const reactivateMutation = useMutation({
    mutationFn: (rdNumber: string) => reactivateAccount(rdNumber),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['accounts'] });
    },
  });
  // C3-P6 LOW in-flight guard mirroring the pattern used by every other
  // mutation in the portal (see SignIn/AccountEditDialog/Delete-
  // OrInactivate/EditDefaulter/ImportCsv). Rapid double-click on
  // 'Reactivate' from the overflow menu would otherwise queue two
  // mutations before React flushes mutation.isPending.
  const reactivateInFlightRef = useRef(false);

  const [search, setSearch] = useState('');
  const [showInactive, setShowInactive] = useState(false);
  const [sortKey, setSortKey] = useState<SortKey>('name');
  const [sortDir, setSortDir] = useState<SortDir>('asc');
  const [editing, setEditing] = useState<RdAccountRow | null>(null);
  const [deleting, setDeleting] = useState<RdAccountRow | null>(null);
  const [importing, setImporting] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  const [overflowFor, setOverflowFor] = useState<string | null>(null);

  useEffect(() => {
    if (!toast) return;
    const t = window.setTimeout(() => setToast(null), 4000);
    return () => window.clearTimeout(t);
  }, [toast]);

  // C3-P6 NITPICK: stable memoized reference so the visibleAccounts
  // useMemo deps array doesn't see a new array identity on every render
  // when query.data is undefined (the `?? []` literal allocates fresh
  // each call). Same applies to activeCount/inactiveCount.
  const allAccounts = useMemo(() => query.data ?? [], [query.data]);
  const activeCount = useMemo(
    () => allAccounts.filter((a) => a.is_active).length,
    [allAccounts]
  );
  const inactiveCount = allAccounts.length - activeCount;

  const visibleAccounts = useMemo(() => {
    const q = search.trim().toLowerCase();
    const filtered = allAccounts.filter((account) => {
      if (!showInactive && !account.is_active) return false;
      if (!q) return true;
      return (
        account.name.toLowerCase().includes(q) ||
        account.rd_number.includes(q)
      );
    });
    const direction = sortDir === 'asc' ? 1 : -1;
    return [...filtered].sort((a, b) => {
      const av = readSortKey(a, sortKey);
      const bv = readSortKey(b, sortKey);
      if (av === bv) return 0;
      if (av == null) return 1;
      if (bv == null) return -1;
      return av > bv ? direction : -direction;
    });
  }, [allAccounts, search, showInactive, sortKey, sortDir]);

  const toggleSort = (key: SortKey) => {
    if (sortKey === key) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    } else {
      setSortKey(key);
      setSortDir('asc');
    }
  };

  const isInitialLoad = query.isLoading;

  return (
    <div>
      <PageHeader
        title="Accounts"
        subtitle={
          query.isLoading
            ? 'Loading…'
            : inactiveCount === 0
              ? `${activeCount} active`
              : `${activeCount} active · ${inactiveCount} inactive`
        }
        action={
          <button
            type="button"
            onClick={() => setImporting(true)}
            disabled={!ownerId}
            className="inline-flex items-center gap-1.5 rounded-pill bg-primary px-3.5 py-1.5 text-xs font-semibold text-white shadow-card transition-colors hover:bg-primary-dark disabled:cursor-not-allowed disabled:opacity-50"
          >
            <Upload className="h-3.5 w-3.5" />
            Import CSV
          </button>
        }
      />

      <div className="mt-4 flex flex-wrap items-center gap-3">
        <div className="relative w-full max-w-sm sm:w-72">
          <SearchIcon
            aria-hidden="true"
            className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-muted"
          />
          <input
            type="text"
            placeholder="Search by name or RD number"
            aria-label="Search accounts"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-full rounded-pill border border-surface-border bg-surface py-1.5 pl-9 pr-9 text-sm placeholder:text-ink-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
          />
          {search.length > 0 && (
            <button
              type="button"
              onClick={() => setSearch('')}
              aria-label="Clear search"
              className="absolute right-2 top-1/2 -translate-y-1/2 rounded-full p-1 text-ink-muted transition-colors hover:bg-surface-alt hover:text-ink-primary"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          )}
        </div>
        {inactiveCount > 0 && (
          <button
            type="button"
            onClick={() => setShowInactive((v) => !v)}
            className={`rounded-pill border px-3 py-1.5 text-xs font-medium transition-colors ${
              showInactive
                ? 'border-accent-mint/30 bg-accent-mint/10 text-accent-mint-ink'
                : 'border-surface-border bg-surface text-ink-secondary hover:border-ink-secondary hover:text-ink-primary'
            }`}
          >
            {showInactive ? 'Hide inactive' : `Show inactive (${formatNumber(inactiveCount)})`}
          </button>
        )}
      </div>

      {query.isError && (
        <div className="mt-6 rounded-2xl border border-danger/20 bg-danger/5 p-4 text-sm text-danger">
          {query.error instanceof Error ? query.error.message : 'Failed to load accounts.'}
        </div>
      )}

      {isInitialLoad && <Skeletons />}

      {!isInitialLoad && visibleAccounts.length === 0 && !query.isError && (
        <EmptyState
          isFiltered={search.length > 0 || (inactiveCount > 0 && !showInactive)}
          onImportClick={() => setImporting(true)}
          importDisabled={!ownerId}
        />
      )}

      {!isInitialLoad && visibleAccounts.length > 0 && (
        <div className="mt-6 overflow-x-auto rounded-2xl border border-surface-border bg-surface shadow-card">
          <table className="w-full sm:min-w-[760px] text-left text-sm">
            <caption className="sr-only">RD Accounts list</caption>
            <thead className="border-b border-surface-border bg-surface-alt text-xs uppercase tracking-wide text-ink-secondary">
              <tr>
                <SortableTh sortKey="name" current={sortKey} dir={sortDir} onClick={toggleSort}>
                  Name
                </SortableTh>
                <SortableTh sortKey="rd_number" current={sortKey} dir={sortDir} onClick={toggleSort}>
                  RD Number
                </SortableTh>
                <SortableTh
                  sortKey="monthly_amount"
                  current={sortKey}
                  dir={sortDir}
                  onClick={toggleSort}
                  align="right"
                >
                  Monthly amount
                </SortableTh>
                <SortableTh
                  sortKey="last_paid_through"
                  current={sortKey}
                  dir={sortDir}
                  onClick={toggleSort}
                  align="right"
                >
                  Paid till
                </SortableTh>
                <th className="px-4 py-3 font-medium">Source</th>
                <th className="px-4 py-3 font-medium text-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {visibleAccounts.map((account) => {
                const isCsv = account.source === 'CSV';
                const muted = !account.is_active;
                return (
                  <tr
                    key={account.rd_number}
                    className={`border-b border-surface-border transition-colors duration-150 last:border-b-0 hover:bg-surface-alt ${
                      muted ? 'opacity-60' : ''
                    }`}
                  >
                    <td className="px-4 py-3 align-middle font-medium text-ink-primary">
                      {account.name}
                      {muted && (
                        <span className="ml-2 rounded-pill bg-warn/15 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wider text-warn">
                          Inactive
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-3 align-middle font-mono tabular-nums text-ink-secondary">
                      {account.rd_number}
                    </td>
                    <td className="px-4 py-3 align-middle text-right font-mono tabular-nums text-ink-primary">
                      ₹{formatNumber(account.monthly_amount)}
                    </td>
                    <td className="px-4 py-3 align-middle text-right text-ink-secondary">
                      {account.last_paid_through ? (
                        <span className="font-medium text-accent-mint-ink">
                          {formatPaidTill(account.last_paid_through)}
                        </span>
                      ) : (
                        <span className="text-ink-muted">—</span>
                      )}
                    </td>
                    <td className="px-4 py-3 align-middle">
                      {isCsv ? (
                        <span
                          title="Imported via CSV"
                          className="inline-flex items-center gap-1 rounded-pill bg-surface-alt px-2 py-0.5 text-[10px] font-medium text-ink-secondary ring-1 ring-surface-border"
                        >
                          <Lock className="h-3 w-3" />
                          CSV
                        </span>
                      ) : (
                        <span className="inline-flex rounded-pill bg-surface-alt px-2 py-0.5 text-[10px] font-medium text-ink-muted ring-1 ring-surface-border">
                          Manual
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-3 align-middle">
                      <div className="flex items-center justify-end gap-1">
                        <button
                          type="button"
                          onClick={() => setEditing(account)}
                          className="rounded-lg p-1.5 text-ink-secondary transition-colors hover:bg-surface-alt hover:text-ink-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
                          aria-label={`Edit ${account.name}`}
                        >
                          <Edit3 className="h-4 w-4" />
                        </button>
                        <OverflowMenu
                          account={account}
                          isOpen={overflowFor === account.rd_number}
                          onToggle={() =>
                            setOverflowFor((cur) =>
                              cur === account.rd_number ? null : account.rd_number
                            )
                          }
                          onClose={() => setOverflowFor(null)}
                          onMarkInactiveOrDelete={() => {
                            setOverflowFor(null);
                            setDeleting(account);
                          }}
                          onReactivate={() => {
                            setOverflowFor(null);
                            if (reactivateInFlightRef.current) return;
                            reactivateInFlightRef.current = true;
                            reactivateMutation.mutate(account.rd_number, {
                              onSuccess: () =>
                                setToast(`Reactivated: ${account.name}`),
                              onError: (err) =>
                                setToast(
                                  err instanceof Error
                                    ? `Reactivate failed: ${err.message}`
                                    : 'Reactivate failed.'
                                ),
                              onSettled: () => {
                                reactivateInFlightRef.current = false;
                              },
                            });
                          }}
                        />
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {editing && (
        <AccountEditDialog account={editing} onClose={() => setEditing(null)} />
      )}
      {deleting && (
        <DeleteOrInactivateDialog account={deleting} onClose={() => setDeleting(null)} />
      )}
      {importing && (
        <ImportCsvDialog
          ownerId={ownerId}
          onClose={() => setImporting(false)}
          onImported={(summary) => setToast(summary)}
        />
      )}
      {toast && (
        <div
          role="status"
          aria-live="polite"
          className="pointer-events-none fixed inset-x-0 bottom-6 z-40 flex justify-center"
        >
          <button
            type="button"
            onClick={() => setToast(null)}
            className="pointer-events-auto inline-flex items-center gap-2 rounded-pill bg-ink-primary px-4 py-2 text-xs font-medium text-white shadow-elevated"
          >
            <span>{toast}</span>
            <X className="h-3 w-3 opacity-70" />
          </button>
        </div>
      )}
    </div>
  );
}

function OverflowMenu({
  account,
  isOpen,
  onToggle,
  onClose,
  onMarkInactiveOrDelete,
  onReactivate,
}: {
  account: RdAccountRow;
  isOpen: boolean;
  onToggle: () => void;
  onClose: () => void;
  onMarkInactiveOrDelete: () => void;
  onReactivate: () => void;
}) {
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  // Portal-rendered + viewport-anchored so the table wrapper's overflow-x-auto
  // doesn't clip it. CSS spec forces overflow:auto on the cross-axis whenever
  // either axis is auto/scroll/hidden — top-full inside the scroll container
  // got clipped on every row that wasn't the bottom one.
  const [position, setPosition] = useState<{ top: number; right: number } | null>(null);

  useLayoutEffect(() => {
    if (!isOpen) {
      setPosition(null);
      return;
    }
    const updatePosition = () => {
      const rect = triggerRef.current?.getBoundingClientRect();
      if (!rect) return;
      setPosition({
        top: rect.bottom + 4,
        right: window.innerWidth - rect.right,
      });
    };
    updatePosition();
    window.addEventListener('scroll', updatePosition, true);
    window.addEventListener('resize', updatePosition);
    return () => {
      window.removeEventListener('scroll', updatePosition, true);
      window.removeEventListener('resize', updatePosition);
    };
  }, [isOpen]);

  useEffect(() => {
    if (!isOpen) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose();
        triggerRef.current?.focus();
      }
    };
    const onClickOutside = (e: MouseEvent) => {
      const target = e.target as Node;
      if (triggerRef.current?.contains(target)) return;
      if (menuRef.current?.contains(target)) return;
      onClose();
    };
    window.addEventListener('keydown', onKeyDown);
    window.addEventListener('mousedown', onClickOutside);
    return () => {
      window.removeEventListener('keydown', onKeyDown);
      window.removeEventListener('mousedown', onClickOutside);
    };
  }, [isOpen, onClose]);

  return (
    <>
      <button
        ref={triggerRef}
        type="button"
        onClick={onToggle}
        aria-haspopup="menu"
        aria-expanded={isOpen}
        aria-label={`More actions for ${account.name}`}
        className="rounded-lg p-1.5 text-ink-secondary transition-colors hover:bg-surface-alt hover:text-ink-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
      >
        <MoreVertical className="h-4 w-4" />
      </button>
      {isOpen && position &&
        createPortal(
          <div
            ref={menuRef}
            role="menu"
            style={{ position: 'fixed', top: position.top, right: position.right }}
            className="z-50 w-48 rounded-xl border border-surface-border bg-surface py-1 shadow-elevated"
          >
            {account.is_active ? (
              <button
                type="button"
                role="menuitem"
                onClick={onMarkInactiveOrDelete}
                className="block w-full px-3 py-2 text-left text-xs text-ink-primary transition-colors hover:bg-surface-alt focus-visible:bg-surface-alt focus-visible:outline-none"
              >
                Mark inactive / Delete
              </button>
            ) : (
              <>
                <button
                  type="button"
                  role="menuitem"
                  onClick={onReactivate}
                  className="block w-full px-3 py-2 text-left text-xs font-medium text-accent-mint-ink transition-colors hover:bg-accent-mint/10 focus-visible:bg-accent-mint/10 focus-visible:outline-none"
                >
                  Reactivate
                </button>
                <button
                  type="button"
                  role="menuitem"
                  onClick={onMarkInactiveOrDelete}
                  className="block w-full px-3 py-2 text-left text-xs text-danger transition-colors hover:bg-danger/5 focus-visible:bg-danger/5 focus-visible:outline-none"
                >
                  Delete permanently
                </button>
              </>
            )}
          </div>,
          document.body
        )}
    </>
  );
}

function readSortKey(
  account: RdAccountRow,
  key: SortKey
): string | number | null {
  switch (key) {
    case 'name':
      return account.name.toLowerCase();
    case 'rd_number':
      return account.rd_number;
    case 'monthly_amount':
      return account.monthly_amount;
    case 'last_paid_through':
      return account.last_paid_through;
  }
}

function SortableTh({
  sortKey,
  current,
  dir,
  onClick,
  children,
  align = 'left',
}: {
  sortKey: SortKey;
  current: SortKey;
  dir: SortDir;
  onClick: (key: SortKey) => void;
  children: React.ReactNode;
  align?: 'left' | 'right';
}) {
  const isActive = current === sortKey;
  const Icon = !isActive ? ArrowUpDown : dir === 'asc' ? ArrowUp : ArrowDown;
  const ariaSort = !isActive ? 'none' : dir === 'asc' ? 'ascending' : 'descending';
  return (
    <th
      aria-sort={ariaSort}
      className={`px-4 py-3 font-medium ${align === 'right' ? 'text-right' : ''}`}
    >
      <button
        type="button"
        onClick={() => onClick(sortKey)}
        className={`inline-flex items-center gap-1 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40 ${
          isActive ? 'text-ink-primary' : 'hover:text-ink-primary'
        }`}
      >
        {children}
        <Icon className="h-3 w-3 opacity-70" />
      </button>
    </th>
  );
}

function Skeletons() {
  return (
    <div className="mt-6">
      <SkeletonCard count={6} heightPx={56} rounded="xl" label="Loading accounts" />
    </div>
  );
}

function EmptyState({
  isFiltered,
  onImportClick,
  importDisabled,
}: {
  isFiltered: boolean;
  onImportClick: () => void;
  importDisabled: boolean;
}) {
  return (
    <div className="mt-6 rounded-2xl border border-dashed border-surface-border bg-surface p-12 text-center">
      <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-surface-alt text-ink-muted">
        <Users className="h-8 w-8" aria-hidden="true" />
      </div>
      <p className="mt-4 text-sm font-medium text-ink-primary">
        {isFiltered ? 'No accounts match.' : 'No accounts yet.'}
      </p>
      <p className="mx-auto mt-1 max-w-md text-xs text-ink-secondary">
        {isFiltered
          ? 'Try a different search or toggle "Show inactive".'
          : 'Import a CSV or add accounts from a phone to populate this list.'}
      </p>
      {!isFiltered && (
        <button
          type="button"
          onClick={onImportClick}
          disabled={importDisabled}
          className="mt-5 inline-flex items-center gap-1.5 rounded-pill bg-primary px-3.5 py-1.5 text-xs font-semibold text-white shadow-card transition-colors hover:bg-primary-dark disabled:cursor-not-allowed disabled:opacity-50"
        >
          <Upload className="h-3.5 w-3.5" />
          Import CSV
        </button>
      )}
    </div>
  );
}

function formatPaidTill(yyyyMm: string): string {
  const parts = yyyyMm.split('-');
  if (parts.length !== 2) return yyyyMm;
  const year = Number(parts[0]);
  const monthIdx = Number(parts[1]) - 1;
  if (Number.isNaN(year) || Number.isNaN(monthIdx) || monthIdx < 0 || monthIdx > 11) {
    return yyyyMm;
  }
  const names = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
                 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  return `${names[monthIdx]} ${year}`;
}


