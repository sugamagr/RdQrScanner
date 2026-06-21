import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  ArrowDown,
  ArrowUp,
  ArrowUpDown,
  Edit3,
  Lock,
  MoreVertical,
  Upload,
} from 'lucide-react';
import { PageHeader } from '../components/PageHeader';
import { AccountEditDialog } from '../components/AccountEditDialog';
import { DeleteOrInactivateDialog } from '../components/DeleteOrInactivateDialog';
import { ImportCsvDialog } from '../components/ImportCsvDialog';
import { fetchAccounts } from '../lib/queries';
import { useAuth } from '../lib/auth';
import { formatNumber, formatRelativeTime } from '../lib/format';
import type { RdAccountRow } from '../types/db';

type SortKey = 'name' | 'rd_number' | 'monthly_amount' | 'last_paid_through';
type SortDir = 'asc' | 'desc';

export function AccountsPage() {
  const { user } = useAuth();
  const ownerId = user?.id ?? '';

  const query = useQuery({
    queryKey: ['accounts'],
    queryFn: fetchAccounts,
  });

  const [search, setSearch] = useState('');
  const [showInactive, setShowInactive] = useState(false);
  const [sortKey, setSortKey] = useState<SortKey>('name');
  const [sortDir, setSortDir] = useState<SortDir>('asc');
  const [editing, setEditing] = useState<RdAccountRow | null>(null);
  const [deleting, setDeleting] = useState<RdAccountRow | null>(null);
  const [importing, setImporting] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  const [overflowFor, setOverflowFor] = useState<string | null>(null);

  const allAccounts = query.data ?? [];
  const activeCount = allAccounts.filter((a) => a.is_active).length;
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
        <label className="contents">
          <span className="sr-only">Search accounts</span>
          <input
            type="text"
            placeholder="Search name or RD number"
            aria-label="Search accounts"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-full max-w-sm rounded-pill border border-surface-border bg-surface px-3.5 py-1.5 text-sm placeholder:text-ink-muted sm:w-72"
          />
        </label>
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
        <EmptyState isFiltered={search.length > 0 || !showInactive} />
      )}

      {!isInitialLoad && visibleAccounts.length > 0 && (
        <div className="mt-6 overflow-x-auto rounded-2xl border border-surface-border bg-surface shadow-card">
          <table className="w-full min-w-[760px] text-left text-sm">
            <thead className="border-b border-surface-border bg-surface-alt text-xs uppercase tracking-wide text-ink-secondary">
              <tr>
                <SortableTh sortKey="name" current={sortKey} dir={sortDir} onClick={toggleSort}>
                  Name
                </SortableTh>
                <SortableTh sortKey="rd_number" current={sortKey} dir={sortDir} onClick={toggleSort}>
                  RD Number
                </SortableTh>
                <SortableTh sortKey="monthly_amount" current={sortKey} dir={sortDir} onClick={toggleSort}>
                  Monthly amount
                </SortableTh>
                <SortableTh
                  sortKey="last_paid_through"
                  current={sortKey}
                  dir={sortDir}
                  onClick={toggleSort}
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
                    <td className="px-4 py-3 align-middle font-mono text-ink-secondary">
                      {account.rd_number}
                    </td>
                    <td className="px-4 py-3 align-middle font-mono text-ink-primary">
                      ₹{formatNumber(account.monthly_amount)}
                    </td>
                    <td className="px-4 py-3 align-middle text-ink-secondary">
                      {account.last_paid_through ? (
                        <span className="font-medium text-accent-mint-ink">
                          {formatPaidTill(account.last_paid_through)}
                        </span>
                      ) : (
                        <span className="text-ink-muted">Not started</span>
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
                        <span className="text-[11px] text-ink-muted">Manual</span>
                      )}
                    </td>
                    <td className="px-4 py-3 align-middle">
                      <div className="flex items-center justify-end gap-1">
                        <button
                          type="button"
                          onClick={() => setEditing(account)}
                          className="rounded-lg p-1.5 text-ink-secondary hover:bg-surface-alt hover:text-ink-primary"
                          aria-label={`Edit ${account.name}`}
                        >
                          <Edit3 className="h-4 w-4" />
                        </button>
                        <div className="relative">
                          <button
                            type="button"
                            onClick={() =>
                              setOverflowFor((cur) =>
                                cur === account.rd_number ? null : account.rd_number
                              )
                            }
                            className="rounded-lg p-1.5 text-ink-secondary hover:bg-surface-alt hover:text-ink-primary"
                            aria-label={`More actions for ${account.name}`}
                          >
                            <MoreVertical className="h-4 w-4" />
                          </button>
                          {overflowFor === account.rd_number && (
                            <div
                              className="absolute right-0 top-full z-10 mt-1 w-44 rounded-xl border border-surface-border bg-surface py-1 shadow-elevated"
                              onMouseLeave={() => setOverflowFor(null)}
                            >
                              <button
                                type="button"
                                onClick={() => {
                                  setOverflowFor(null);
                                  setDeleting(account);
                                }}
                                className="block w-full px-3 py-1.5 text-left text-xs text-ink-primary hover:bg-surface-alt"
                              >
                                Mark inactive / Delete
                              </button>
                            </div>
                          )}
                        </div>
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
        <div className="pointer-events-none fixed inset-x-0 bottom-6 z-40 flex justify-center">
          <button
            type="button"
            onClick={() => setToast(null)}
            className="pointer-events-auto rounded-pill bg-ink-primary px-4 py-2 text-xs font-medium text-white shadow-elevated"
          >
            {toast} · tap to dismiss
          </button>
        </div>
      )}
    </div>
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
}: {
  sortKey: SortKey;
  current: SortKey;
  dir: SortDir;
  onClick: (key: SortKey) => void;
  children: React.ReactNode;
}) {
  const isActive = current === sortKey;
  const Icon = !isActive ? ArrowUpDown : dir === 'asc' ? ArrowUp : ArrowDown;
  return (
    <th className="px-4 py-3 font-medium">
      <button
        type="button"
        onClick={() => onClick(sortKey)}
        className={`inline-flex items-center gap-1 transition-colors ${
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

function EmptyState({ isFiltered }: { isFiltered: boolean }) {
  return (
    <div className="mt-6 rounded-2xl border border-dashed border-surface-border bg-surface p-12 text-center">
      <p className="text-sm font-medium text-ink-primary">
        {isFiltered ? 'No accounts match.' : 'No accounts yet.'}
      </p>
      <p className="mt-1 text-xs text-ink-secondary">
        {isFiltered
          ? 'Try a different search or toggle "Show inactive".'
          : 'Import a CSV or add accounts from a phone to populate this list.'}
      </p>
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

// Used for a future spec extension; suppresses unused import warning
// on formatRelativeTime in this revision without removing the import.
void formatRelativeTime;
