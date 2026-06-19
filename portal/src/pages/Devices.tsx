import { useQuery } from '@tanstack/react-query';
import { PageHeader } from '../components/PageHeader';
import { fetchDevices } from '../lib/queries';
import { formatDateTime, formatRelativeTime } from '../lib/format';
import type { DeviceRow } from '../types/db';

const ACTIVE_THRESHOLD_MS = 10 * 60 * 1000;
const IDLE_THRESHOLD_MS = 24 * 60 * 60 * 1000;

export function DevicesPage() {
  const query = useQuery({
    queryKey: ['devices'],
    queryFn: fetchDevices,
  });

  const devices = query.data ?? [];

  return (
    <div>
      <PageHeader
        title="Devices"
        subtitle="Phones signed in to this account, with last-active times."
      />

      {query.isError && (
        <div className="mt-6 rounded-2xl border border-danger/20 bg-danger/5 p-6 text-center">
          <p className="text-sm font-medium text-danger">
            {query.error instanceof Error
              ? query.error.message
              : 'Failed to load devices.'}
          </p>
          <button
            type="button"
            onClick={() => query.refetch()}
            className="mt-3 rounded-pill border border-danger/30 px-3.5 py-1.5 text-xs font-medium text-danger hover:bg-danger/10"
          >
            Retry
          </button>
        </div>
      )}

      {query.isLoading && (
        <div className="mt-6 space-y-2">
          {Array.from({ length: 3 }).map((_, i) => (
            <div
              key={i}
              className="h-20 animate-pulse rounded-2xl border border-surface-border bg-surface-alt"
            />
          ))}
        </div>
      )}

      {!query.isLoading && devices.length === 0 && !query.isError && (
        <div className="mt-6 rounded-2xl border border-dashed border-surface-border bg-surface p-12 text-center">
          <p className="text-sm font-medium text-ink-primary">No devices yet.</p>
          <p className="mt-1 text-xs text-ink-secondary">
            When a phone signs in for the first time it will appear here.
          </p>
        </div>
      )}

      {devices.length > 0 && (
        <ul className="mt-6 grid gap-3 sm:grid-cols-2">
          {devices.map((device) => (
            <DeviceCard key={device.id} device={device} />
          ))}
        </ul>
      )}
    </div>
  );
}

function DeviceCard({ device }: { device: DeviceRow }) {
  const lastSeenMs = Date.parse(device.last_seen_at);
  const age = Number.isNaN(lastSeenMs) ? Number.POSITIVE_INFINITY : Date.now() - lastSeenMs;
  const status = age < ACTIVE_THRESHOLD_MS ? 'active' : age < IDLE_THRESHOLD_MS ? 'idle' : 'dormant';

  return (
    <li className="rounded-2xl border border-surface-border bg-surface p-4 shadow-card transition-colors duration-150 hover:border-primary/30">
      <div className="flex items-start justify-between gap-3">
        <div className="flex-1 min-w-0">
          <p className="truncate text-base font-semibold text-ink-primary">
            {device.device_name}
          </p>
          {device.device_model && (
            <p className="mt-0.5 truncate text-xs text-ink-secondary">
              {device.device_model}
              {device.app_version ? ` · v${device.app_version}` : ''}
            </p>
          )}
        </div>
        <StatusBadge status={status} />
      </div>

      <dl className="mt-3 grid grid-cols-2 gap-2 text-[11px]">
        <div>
          <dt className="font-medium uppercase tracking-wider text-ink-muted">
            Last seen
          </dt>
          <dd
            title={formatDateTime(device.last_seen_at)}
            className="mt-0.5 text-ink-primary"
          >
            {formatRelativeTime(device.last_seen_at)}
          </dd>
        </div>
        <div>
          <dt className="font-medium uppercase tracking-wider text-ink-muted">
            Joined
          </dt>
          <dd
            title={formatDateTime(device.first_seen_at)}
            className="mt-0.5 text-ink-primary"
          >
            {formatRelativeTime(device.first_seen_at)}
          </dd>
        </div>
      </dl>
    </li>
  );
}

function StatusBadge({ status }: { status: 'active' | 'idle' | 'dormant' }) {
  const config = {
    active: {
      label: 'Active',
      classes: 'bg-accent-mint/15 text-accent-mint-ink ring-1 ring-accent-mint/30',
    },
    idle: {
      label: 'Idle',
      classes: 'bg-warn/15 text-warn ring-1 ring-warn/30',
    },
    dormant: {
      label: 'Dormant',
      classes: 'bg-surface-alt text-ink-muted ring-1 ring-surface-border',
    },
  }[status];

  return (
    <span
      className={`inline-flex shrink-0 items-center gap-1 rounded-pill px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wider ${config.classes}`}
    >
      <span
        className={`h-1.5 w-1.5 rounded-full ${
          status === 'active' ? 'animate-pulse bg-accent-mint' : 'bg-current'
        }`}
        aria-hidden
      />
      {config.label}
    </span>
  );
}
