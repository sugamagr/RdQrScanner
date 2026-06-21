import { useRef, useState, type FormEvent } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '../lib/useAuth';

export function SignInPage() {
  const { session, signIn, expiryReason } = useAuth();
  const location = useLocation() as { state?: { from?: string } };
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // P6γ NITPICK in-flight guard: setLoading(true) reaches the DOM
  // asynchronously (React batches setState), so a fast double-click
  // between submit and re-render can queue two signIn requests. Read
  // and write via a synchronous ref to short-circuit before any
  // network call.
  const inFlightRef = useRef(false);

  if (session) {
    return <Navigate to={location.state?.from ?? '/sessions'} replace />;
  }

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (inFlightRef.current) return;
    inFlightRef.current = true;
    setLoading(true);
    setError(null);
    try {
      const result = await signIn(email.trim(), password);
      if (result.error) {
        setError(result.error);
        setLoading(false);
      }
    } finally {
      inFlightRef.current = false;
    }
  };

  return (
    <div className="grid min-h-screen place-items-center bg-gradient-to-b from-surface-alt to-surface px-4">
      <div className="w-full max-w-sm">
        <div className="mb-8 flex flex-col items-center text-center">
          <div className="mb-4 grid h-14 w-14 place-items-center rounded-2xl bg-primary text-white shadow-elevated">
            <span className="text-lg font-bold">RD</span>
          </div>
          <h1 className="text-2xl font-semibold tracking-tight text-ink-primary">
            Welcome back
          </h1>
          <p className="mt-1 text-sm text-ink-secondary">
            Sign in with your shop owner account.
          </p>
        </div>

        {expiryReason && !error && (
          <div
            role="status"
            className="mb-4 rounded-xl border border-warn/20 bg-warn/5 px-3.5 py-2.5 text-xs text-warn"
          >
            {expiryReason}
          </div>
        )}

        <form
          onSubmit={handleSubmit}
          className="rounded-2xl border border-surface-border bg-surface p-6 shadow-card"
        >
          <label className="block">
            <span className="text-xs font-medium text-ink-secondary">Email</span>
            <input
              type="email"
              autoComplete="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="mt-1 w-full rounded-xl border border-surface-border bg-surface-alt px-3.5 py-2.5 text-sm text-ink-primary placeholder:text-ink-muted"
              placeholder="owner@yourdomain.com"
            />
          </label>

          <label className="mt-4 block">
            <span className="text-xs font-medium text-ink-secondary">Password</span>
            <input
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="mt-1 w-full rounded-xl border border-surface-border bg-surface-alt px-3.5 py-2.5 text-sm text-ink-primary placeholder:text-ink-muted"
              placeholder="••••••••"
            />
          </label>

          {error && (
            <div
              role="alert"
              className="mt-4 rounded-xl border border-danger/20 bg-danger/5 px-3.5 py-2.5 text-xs text-danger"
            >
              {error}
            </div>
          )}

          <button
            type="submit"
            disabled={loading || !email || !password}
            className="mt-6 w-full rounded-xl bg-primary px-4 py-2.5 text-sm font-semibold text-white shadow-card transition-all hover:bg-primary-dark disabled:cursor-not-allowed disabled:opacity-50"
          >
            {loading ? 'Signing in…' : 'Sign in'}
          </button>
        </form>

        <p className="mt-6 text-center text-xs text-ink-muted">
          Same credentials as the phones. Forgot your password? Sign in via Supabase
          Studio to reset.
        </p>
      </div>
    </div>
  );
}
