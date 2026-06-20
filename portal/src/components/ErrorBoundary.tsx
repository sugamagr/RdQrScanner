import { Component, type ErrorInfo, type ReactNode } from 'react';

interface Props {
  children: ReactNode;
  fallback?: (error: Error, reset: () => void) => ReactNode;
}

interface State {
  error: Error | null;
}

/**
 * Catches render-phase crashes anywhere in the subtree and renders a
 * recovery screen with a Reset action instead of dropping the user on
 * a white page. Phase 5 T5.9.
 *
 * React error boundaries only catch render + lifecycle + constructor
 * exceptions — async errors (Promise rejections, event handlers) still
 * surface via TanStack Query's error state or the mutation error UI.
 * Both layers together keep the portal crash-free.
 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    // No remote crash reporter yet; surface to the console so the dev
    // tools tab carries the stack while the user sees the friendly UI.
    // Phase 5 T5.8 telemetry stub would wire Sentry/PostHog here.
    // eslint-disable-next-line no-console
    console.error('[portal] render error', error, info.componentStack);
  }

  private reset = (): void => {
    this.setState({ error: null });
  };

  render(): ReactNode {
    const { error } = this.state;
    if (!error) return this.props.children;
    if (this.props.fallback) return this.props.fallback(error, this.reset);
    return <DefaultFallback error={error} onReset={this.reset} />;
  }
}

function DefaultFallback({
  error,
  onReset,
}: {
  error: Error;
  onReset: () => void;
}) {
  return (
    <div className="grid min-h-screen place-items-center bg-surface-alt px-4">
      <div className="w-full max-w-md rounded-2xl border border-danger/20 bg-surface p-6 shadow-card">
        <div className="grid h-10 w-10 place-items-center rounded-xl bg-danger/10 text-danger">
          <span aria-hidden className="text-lg font-semibold">
            !
          </span>
        </div>
        <h1 className="mt-4 text-base font-semibold text-ink-primary">
          Something went wrong on this page
        </h1>
        <p className="mt-1 text-xs text-ink-secondary">
          The portal hit an unexpected error. Your data is safe in the
          cloud — only this view crashed.
        </p>
        <details className="mt-3 rounded-xl bg-surface-alt p-3 text-xs text-ink-secondary">
          <summary className="cursor-pointer font-medium text-ink-primary">
            Details
          </summary>
          <pre className="mt-2 overflow-x-auto whitespace-pre-wrap break-words font-mono text-[11px]">
            {error.message}
          </pre>
        </details>
        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={() => window.location.reload()}
            className="rounded-pill border border-surface-border px-3.5 py-1.5 text-xs font-medium text-ink-secondary hover:border-ink-secondary hover:text-ink-primary"
          >
            Reload
          </button>
          <button
            type="button"
            onClick={onReset}
            className="rounded-pill bg-primary px-4 py-1.5 text-xs font-semibold text-white shadow-card hover:bg-primary-dark"
          >
            Try again
          </button>
        </div>
      </div>
    </div>
  );
}
