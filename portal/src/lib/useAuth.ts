import { createContext, useContext } from 'react';
import type { Session, User } from '@supabase/supabase-js';

/**
 * Shape of the auth context value provided by AuthProvider and
 * consumed by useAuth(). Kept in this non-component file so neither
 * the provider nor the hook tripped react-refresh/only-export-components
 * (extracted from auth.tsx in C3-P6 nitpick cleanup).
 */
export interface AuthContextValue {
  user: User | null;
  session: Session | null;
  loading: boolean;
  /**
   * Set to a friendly reason when the session ended involuntarily
   * (refresh failure, server-side revoke). Cleared on the next
   * successful sign-in. The SignIn page surfaces this as a soft hint.
   */
  expiryReason: string | null;
  signIn: (email: string, password: string) => Promise<{ error: string | null }>;
  signOut: () => Promise<void>;
}

export const AuthContext = createContext<AuthContextValue | null>(null);

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth must be used inside <AuthProvider>');
  }
  return ctx;
}
