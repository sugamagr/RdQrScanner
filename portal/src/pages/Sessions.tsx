import { PageHeader } from '../components/PageHeader';

export function SessionsPage() {
  return (
    <div>
      <PageHeader
        title="Sessions"
        subtitle="Browse every finalized scanning session across all phones."
      />
      <div className="mt-6 rounded-2xl border border-dashed border-surface-border bg-surface p-12 text-center">
        <p className="text-sm text-ink-secondary">
          Sessions list — implemented in T4.4.
        </p>
      </div>
    </div>
  );
}
