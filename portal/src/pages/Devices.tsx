import { PageHeader } from '../components/PageHeader';

export function DevicesPage() {
  return (
    <div>
      <PageHeader
        title="Devices"
        subtitle="Phones signed in to this account, with last-active times."
      />
      <div className="mt-6 rounded-2xl border border-dashed border-surface-border bg-surface p-12 text-center">
        <p className="text-sm text-ink-secondary">Devices list — implemented in T4.9.</p>
      </div>
    </div>
  );
}
