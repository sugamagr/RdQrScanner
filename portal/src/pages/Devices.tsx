import { useQuery } from '@tanstack/react-query';
import { PageHeader } from '../components/PageHeader';
import { SkeletonCard } from '../components/Loader';
import { fetchDevices } from '../lib/queries';
import { formatDateTime, formatNumber, formatRelativeTime } from '../lib/format';
import { useDocumentTitle } from '../lib/useDocumentTitle';
import type { DeviceRow } from '../types/db';

const ACTIVE_THRESHOLD_MS = 10 * 60 * 1000;
const IDLE_THRESHOLD_MS = 24 * 60 * 60 * 1000;

// A device is "stalled" if it has either persistent failures (an error
// from its last push) or work that hasn't drained. The card surfaces
// this BEFORE the active/idle/dormant pill because a phone can be
// "active" (recent heartbeat) AND simultaneously failing to push —
// the owner needs to see the failure, not just the green dot.
function syncHealth(device: DeviceRow): 'ok' | 'pending' | 'failing' {
  if (device.last_sync_error) return 'failing';
  if (device.pending_count > 0) return 'pending';
  return 'ok';
}

export function DevicesPage() {
  useDocumentTitle('Devices');
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
        <div className="mt-6">
          <SkeletonCard count={3} heightPx={80} rounded="2xl" label="Loading devices" />
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
  const health = syncHealth(device);

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
        <div className="flex shrink-0 flex-col items-end gap-1">
          <StatusBadge status={status} />
          {health !== 'ok' && <SyncHealthBadge health={health} />}
        </div>
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
            Last push
          </dt>
          <dd
            title={device.last_push_at ? formatDateTime(device.last_push_at) : 'never'}
            className="mt-0.5 text-ink-primary"
          >
            {device.last_push_at ? formatRelativeTime(device.last_push_at) : (
              <span className="text-ink-muted">never</span>
            )}
          </dd>
        </div>
        <div>
          <dt className="font-medium uppercase tracking-wider text-ink-muted">
            Pending
          </dt>
          <dd className="mt-0.5 font-mono tabular-nums text-ink-primary">
            {device.pending_count > 0 ? (
              <span className="text-warn">{formatNumber(device.pending_count)}</span>
            ) : (
              <span className="text-ink-secondary">0</span>
            )}
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

      {device.last_sync_error && (
        <div className="mt-3 rounded-xl border border-danger/20 bg-danger/5 p-3">
          <p className="text-[10px] font-semibold uppercase tracking-wider text-danger">
            Last sync error
          </p>
          <p
            className="mt-1 break-words text-xs text-danger/90"
            title={device.last_sync_error}
          >
            {device.last_sync_error}
          </p>
        </div>
      )}
    </li>
  );
}

function SyncHealthBadge({ health }: { health: 'pending' | 'failing' }) {
  const config =
    health === 'failing'
      ? {
          label: 'Failing',
          classes: 'bg-danger/10 text-danger ring-1 ring-danger/30',
        }
      : {
          label: 'Pending',
          classes: 'bg-warn/15 text-warn ring-1 ring-warn/30',
        };
  return (
    <span
      className={`inline-flex shrink-0 items-center gap-1 rounded-pill px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wider ${config.classes}`}
    >
      {config.label}
    </span>
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
