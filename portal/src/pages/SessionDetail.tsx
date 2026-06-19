import { useParams } from 'react-router-dom';
import { PageHeader } from '../components/PageHeader';

export function SessionDetailPage() {
  const { sessionId } = useParams<{ sessionId: string }>();
  return (
    <div>
      <PageHeader
        title="Session detail"
        subtitle={`Session ${sessionId ?? ''} — LOTs and RD numbers.`}
      />
      <div className="mt-6 rounded-2xl border border-dashed border-surface-border bg-surface p-12 text-center">
        <p className="text-sm text-ink-secondary">
          Session detail — implemented in T4.5.
        </p>
      </div>
    </div>
  );
}
