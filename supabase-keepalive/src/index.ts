/**
 * Supabase keep-alive Worker.
 *
 * Runs every 3 days on Cloudflare's cron scheduler (see wrangler.toml).
 * Fires an authenticated REST query against the RD Scanner's Supabase
 * project. The query itself is cheap (SELECT 1 disguised as a bounded
 * HEAD against a real table with RLS) but it's a DB round-trip, which
 * is what Supabase's inactivity detector actually counts.
 *
 * WHY not a DNS lookup / TCP ping: Supabase's free-tier pause detector
 * measures WAL activity and connection count, not TCP-level probes.
 * Cloudflare's edge doing a HEAD to supabase.co would resolve DNS and
 * connect to a Cloudflare edge but never touch the actual Postgres
 * process. A real REST call goes: CF edge -> Supabase CF gateway ->
 * PostgREST -> Postgres. That last hop is what keeps the counter
 * ticking.
 *
 * Failure modes and their handling:
 *  - Supabase 5xx transient: retry once after 2 seconds, log both
 *    attempts; a single sustained 5xx across two attempts fires a
 *    warning log line the CF dashboard surfaces
 *  - Network timeout: aborts after 15s (WAY more than Supabase's
 *    normal p99 of ~2s but tight enough to fail-fast if the project
 *    is actually paused and Supabase is spinning it up cold)
 *  - Unexpected 401/403: means SUPABASE_ANON_KEY drifted (was rotated
 *    or the project was recreated); logs the exact status so the fix
 *    is `wrangler secret put SUPABASE_ANON_KEY` with the new value
 *  - Worker itself crashes: Cloudflare's cron retry policy will
 *    invoke it again at the next scheduled time; no data loss because
 *    the Worker is stateless
 *
 * NO HTTP handler is exposed. Cron-only deploy avoids the workers.dev
 * subdomain registration step. Manual verification uses:
 *   npx wrangler dev --test-scheduled
 * then hit http://localhost:8787/__scheduled with query params matching
 * the wrangler.toml cron expression to invoke the scheduled handler
 * locally against real Supabase.
 */

export interface Env {
  SUPABASE_URL: string;
  SUPABASE_ANON_KEY: string;
}

interface PingResult {
  ok: boolean;
  status: number;
  latencyMs: number;
  attempt: number;
  error?: string;
}

/**
 * Fires a single authenticated REST call against Supabase.
 *
 * The query is `HEAD /rest/v1/devices?limit=1`. Devices table exists
 * in every RD Scanner deploy so this is guaranteed to hit real DB.
 * HEAD returns no body but still counts as a query. limit=1 makes it
 * O(1) even if the table has millions of rows (it doesn't - user's
 * project has 2-5 devices).
 *
 * A 200 or 206 response means Postgres processed it. Anything else
 * is a signal to log.
 */
async function pingOnce(env: Env, attempt: number, signal: AbortSignal): Promise<PingResult> {
  const start = Date.now();
  try {
    const res = await fetch(`${env.SUPABASE_URL}/rest/v1/devices?limit=1`, {
      method: 'HEAD',
      headers: {
        apikey: env.SUPABASE_ANON_KEY,
        Authorization: `Bearer ${env.SUPABASE_ANON_KEY}`,
        Prefer: 'count=none',
      },
      signal,
    });
    const latencyMs = Date.now() - start;
    return {
      ok: res.status === 200 || res.status === 206,
      status: res.status,
      latencyMs,
      attempt,
    };
  } catch (e) {
    return {
      ok: false,
      status: 0,
      latencyMs: Date.now() - start,
      attempt,
      error: e instanceof Error ? e.message : String(e),
    };
  }
}

/**
 * Orchestrates ping with one retry on transient failure.
 *
 * A "transient failure" here is anything that isn't 2xx or 4xx auth
 * error. 401/403 gets no retry because retrying a bad key just
 * doubles the failure log noise. Timeouts and 5xx get one retry
 * after 2 seconds because Supabase's cold-start-from-pause has been
 * measured at 5-15 seconds in the wild.
 *
 * Cron invocation returns void (Cloudflare doesn't care about the
 * return value). Manual HTTP invocation returns the results so I
 * can eyeball them in the browser.
 */
async function pingWithRetry(env: Env): Promise<PingResult[]> {
  const results: PingResult[] = [];
  const controller1 = new AbortController();
  const timeout1 = setTimeout(() => controller1.abort(), 15000);
  const first = await pingOnce(env, 1, controller1.signal);
  clearTimeout(timeout1);
  results.push(first);

  if (first.ok) return results;
  if (first.status === 401 || first.status === 403) return results;

  await new Promise((resolve) => setTimeout(resolve, 2000));

  const controller2 = new AbortController();
  const timeout2 = setTimeout(() => controller2.abort(), 15000);
  const second = await pingOnce(env, 2, controller2.signal);
  clearTimeout(timeout2);
  results.push(second);

  return results;
}

function logResults(results: readonly PingResult[]) {
  const succeeded = results.some((r) => r.ok);
  const summary = {
    trigger: 'cron',
    succeeded,
    attempts: results.length,
    finalStatus: results[results.length - 1].status,
    totalLatencyMs: results.reduce((sum, r) => sum + r.latencyMs, 0),
    results,
    at: new Date().toISOString(),
  };
  if (succeeded) {
    console.log(JSON.stringify(summary));
  } else {
    console.error(JSON.stringify(summary));
  }
}

export default {
  async scheduled(_event: ScheduledEvent, env: Env, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil(
      (async () => {
        const results = await pingWithRetry(env);
        logResults(results);
      })(),
    );
  },
};
