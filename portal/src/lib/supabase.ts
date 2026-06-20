import { createClient, type SupabaseClient } from '@supabase/supabase-js';

// Phase 5 boundary review (bg_37c5d971 #9): defer the missing-env-var
// failure to first access so React's ErrorBoundary catches it instead
// of the browser surfacing a bare 'Uncaught Error' white page during
// ES module evaluation. Throwing in a function called from a component
// render path keeps the error inside React's error boundary boundary.

const url = import.meta.env.VITE_SUPABASE_URL;
const anonKey = import.meta.env.VITE_SUPABASE_ANON_KEY;

let cached: SupabaseClient | null = null;

function buildClient(): SupabaseClient {
  if (!url || !anonKey) {
    throw new Error(
      'Missing VITE_SUPABASE_URL or VITE_SUPABASE_ANON_KEY. ' +
        'Copy .env.example to .env.local (dev) or set them in the ' +
        'Cloudflare Pages project Settings → Environment Variables (prod) ' +
        'and reload.'
    );
  }
  return createClient(url, anonKey, {
    auth: {
      persistSession: true,
      autoRefreshToken: true,
      detectSessionInUrl: true,
    },
    realtime: {
      params: { eventsPerSecond: 5 },
    },
  });
}

// Proxy keeps the public surface (`import { supabase }`) identical while
// deferring instantiation. Every property access goes through `get()`,
// which materializes the client on first use. If env is missing the
// error surfaces inside whatever component triggered the access.
export const supabase: SupabaseClient = new Proxy({} as SupabaseClient, {
  get(_target, prop, _receiver) {
    if (cached === null) cached = buildClient();
    const value = Reflect.get(cached, prop, cached);
    return typeof value === 'function' ? value.bind(cached) : value;
  },
});
