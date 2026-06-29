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
  // Tracks the last session's user.id so we can distinguish a fresh-login transition
  // (null -> session), involuntary sign-out (session -> null), AND a cross-account
  // swap (session A -> session B with different user.id). The third case can fire
  // without an intervening null when a storage event from another tab or an
  // auto-refresh races a sign-in to a different account; without the swap branch
  // the previous owner's qc cache would be visible to the new owner until refetch.
  const lastUserIdRef = useRef<string | null>(null);

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
        lastUserIdRef.current = data.session?.user.id ?? null;
        setLoading(false);
      })
      .catch((err: unknown) => {
        if (!mounted) return;
        console.warn('[auth] getSession() failed during boot', err);
        setSession(null);
        lastUserIdRef.current = null;
        setLoading(false);
      });

    const {
      data: { subscription },
    } = supabase.auth.onAuthStateChange((event, next) => {
      const prevUserId = lastUserIdRef.current;
      const nextUserId = next?.user.id ?? null;
      const prevHadSession = prevUserId !== null;
      const nextHasSession = nextUserId !== null;
      const ownerChanged =
        prevHadSession && nextHasSession && prevUserId !== nextUserId;

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
      } else if (ownerChanged) {
        // Same browser, different owner — must wipe cache even though
        // the supabase SDK never emitted a null between the two
        // sessions. Otherwise the new owner sees the prior owner's
        // rows for a render before TanStack refetches.
        qc.clear();
        setExpiryReason(null);
      }

      lastUserIdRef.current = nextUserId;
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
