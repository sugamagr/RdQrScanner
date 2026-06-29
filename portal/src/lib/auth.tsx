import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import type { Session } from '@supabase/supabase-js';
import { useQueryClient } from '@tanstack/react-query';
import { supabase } from './supabase';
import { AuthContext, type AuthContextValue } from './useAuth';

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const [loading, setLoading] = useState(true);
  const [expiryReason, setExpiryReason] = useState<string | null>(null);
  const qc = useQueryClient();
  // Tracks the last session id so we can distinguish a fresh-login transition
  // (null -> session) from an involuntary sign-out (session -> null).
  const lastSessionIdRef = useRef<string | null>(null);

  useEffect(() => {
    let mounted = true;

    // Without the .catch(), a transient network failure or an SDK
    // rejection during boot would leave loading=true forever and the
    // app would be stuck on the FullPageLoader. Always flip loading
    // off in the failure branch; the user can hit Sign in to retry.
    supabase.auth
      .getSession()
      .then(({ data }) => {
        if (!mounted) return;
        setSession(data.session);
        lastSessionIdRef.current = data.session?.access_token ?? null;
        setLoading(false);
      })
      .catch((err: unknown) => {
        if (!mounted) return;
        console.warn('[auth] getSession() failed during boot', err);
        setSession(null);
        lastSessionIdRef.current = null;
        setLoading(false);
      });

    const {
      data: { subscription },
    } = supabase.auth.onAuthStateChange((event, next) => {
      const prevHadSession = !!lastSessionIdRef.current;
      const nextHasSession = !!next;

      if (prevHadSession && !nextHasSession) {
        // Cross-account safety: wipe the in-memory query cache so the
        // next signed-in user never momentarily sees the previous
        // owner's rows even before refetch.
        qc.clear();
        if (event === 'TOKEN_REFRESHED' || event === 'USER_UPDATED') {
          setExpiryReason('Your session expired. Sign in again to continue.');
        } else if (event === 'SIGNED_OUT') {
          // Clear any prior expiry hint on an explicit sign-out.
          setExpiryReason(null);
        }
      } else if (!prevHadSession && nextHasSession) {
        setExpiryReason(null);
      }

      lastSessionIdRef.current = next?.access_token ?? null;
      setSession(next);
    });

    return () => {
      mounted = false;
      subscription.unsubscribe();
    };
  }, [qc]);

  const value = useMemo<AuthContextValue>(
    () => ({
      user: session?.user ?? null,
      session,
      loading,
      expiryReason,
      signIn: async (email, password) => {
        const { error } = await supabase.auth.signInWithPassword({ email, password });
        if (error) {
          return { error: friendlyAuthError(error.message, error.status) };
        }
        return { error: null };
      },
      signOut: async () => {
        await supabase.auth.signOut();
        qc.clear();
      },
    }),
    [session, loading, expiryReason, qc]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

// Supabase returns generic messages; map the common ones to plain English.
function friendlyAuthError(raw: string, status?: number): string {
  const lower = raw.toLowerCase();
  if (lower.includes('invalid login credentials') || status === 400) {
    return 'Email or password is incorrect.';
  }
  if (lower.includes('network') || lower.includes('failed to fetch')) {
    return 'No network. Try again when you are online.';
  }
  if (status && status >= 500) {
    return 'Server error. Try again in a moment.';
  }
  return raw;
}
