/**
 * Supabase keep-alive + weekly health email Worker.
 *
 * Two cron jobs share this Worker (see wrangler.toml triggers.crons):
 *
 *   1. `0 3 * / 3 * *` (every 3 days, 03:00 UTC = 08:30 IST)
 *      Fires an authenticated REST HEAD against Supabase to prevent
 *      free-tier auto-pause. Cheap, silent on success, logs error on
 *      persistent failure.
 *
 *   2. `0 8 * * 1` (every Monday, 08:00 UTC = 13:30 IST)
 *      Runs a full three-component health check: Supabase REST +
 *      portal HTML fetch + GitHub Releases API. Sends a plain-text
 *      summary email to RECIPIENT_EMAIL via Resend regardless of
 *      outcome (so a silent-monday email tells you SOMETHING is
 *      wrong, either the Worker itself or Resend).
 *
 * Cron dispatch happens in scheduled() by matching event.cron against
 * the two expected strings. WHY not two separate Workers: half the
 * infra to maintain, shared pingWithRetry() helper stays DRY, and
 * the deploy pipeline is a single wrangler command. WHY the string
 * match instead of first-cron-vs-not: explicit is better than
 * implicit; a future third cron expression won't silently take one
 * of the existing branches.
 *
 * WHY not a DNS lookup / TCP ping for Supabase: the pause detector
 * measures WAL activity and connection count, not TCP-level probes.
 * A real REST call goes CF edge -> Supabase CF gateway -> PostgREST
 * -> Postgres. Only that last hop keeps the counter ticking.
 *
 * Failure modes and handling (Supabase keep-alive):
 *  - Supabase 5xx transient: retry once after 2 seconds, log both
 *    attempts
 *  - Network timeout: aborts after 15s (Supabase p99 is ~2s, but a
 *    cold-start-from-pause can take 5-15s)
 *  - Unexpected 401/403: means SUPABASE_ANON_KEY drifted. NO retry
 *    because retrying a bad key just doubles the log noise. Fix is
 *    `wrangler secret put SUPABASE_ANON_KEY`
 *  - Worker crashes: Cloudflare cron retries on next scheduled slot
 *
 * Failure modes and handling (weekly email):
 *  - Any component check fails: email still sent, with the failed
 *    component clearly marked. Silence is the danger; a broken but
 *    delivered email is diagnostic
 *  - Resend API 4xx/5xx: logged, no retry (Monday retry is 7 days
 *    away). If you notice no email arrived, check CF Worker logs
 *  - RESEND_API_KEY missing or drifted: email attempt fails, health
 *    check results still land in CF Worker logs so you can eyeball
 *    them there. Fix: `wrangler secret put RESEND_API_KEY`
 *
 * NO HTTP handler is exposed. Cron-only deploy avoids the workers.dev
 * subdomain registration prerequisite. Manual verification uses:
 *   npx wrangler dev --test-scheduled
 * then invoke the /__scheduled endpoint with the matching cron expr.
 */

export interface Env {
  SUPABASE_URL: string;
  SUPABASE_ANON_KEY: string;
  PORTAL_URL: string;
  GITHUB_REPO: string;
  RECIPIENT_EMAIL: string;
  RESEND_FROM: string;
  RESEND_API_KEY: string;
}

interface PingResult {
  ok: boolean;
  status: number;
  latencyMs: number;
  attempt: number;
  error?: string;
}

interface ComponentHealth {
  name: string;
  ok: boolean;
  detail: string;
  latencyMs: number;
}

/**
 * Fires a single authenticated REST call against Supabase.
 *
 * HEAD /rest/v1/devices?limit=1. The devices table exists in every
 * RD Scanner deploy so this is guaranteed to hit real DB. HEAD returns
 * no body but still counts as a query. limit=1 makes it O(1) even if
 * the table had millions of rows.
 *
 * 200 or 206 means Postgres processed it. Anything else is a signal.
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
 * NO retry on 401/403 because retrying a bad key just doubles the
 * failure log noise. Timeouts and 5xx get one retry after 2 seconds
 * because Supabase cold-start-from-pause has been measured at 5-15s.
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

function logResults(results: readonly PingResult[], trigger: string) {
  const succeeded = results.some((r) => r.ok);
  const summary = {
    trigger,
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

/**
 * One-shot fetch with abort. Returns a ComponentHealth built from
 * the response status. NOT used for Supabase (see pingWithRetry for
 * the retry-aware path); this is for portal + GitHub where the
 * weekly-email caller wants a single-shot yes/no.
 */
async function checkOnce(
  name: string,
  url: string,
  expectedStatus: number,
  init: RequestInit = {},
): Promise<ComponentHealth> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 15000);
  const start = Date.now();
  try {
    const res = await fetch(url, { ...init, signal: controller.signal });
    const latencyMs = Date.now() - start;
    return {
      name,
      ok: res.status === expectedStatus,
      detail: `HTTP ${res.status}${res.status === expectedStatus ? '' : ` (expected ${expectedStatus})`}`,
      latencyMs,
    };
  } catch (e) {
    return {
      name,
      ok: false,
      detail: e instanceof Error ? e.message : String(e),
      latencyMs: Date.now() - start,
    };
  } finally {
    clearTimeout(timeout);
  }
}

/**
 * Runs Supabase REST + portal HTML + GitHub Releases API checks in
 * parallel. Parallel because they're independent and the weekly cron
 * has no reason to serialize; total latency stays at ~max(each) not
 * sum(each).
 *
 * Supabase check uses pingWithRetry so a transient Supabase 5xx
 * doesn't turn Monday email into a false-alarm. Portal + GitHub get
 * single-shot: they're rarely flaky and the email itself will make
 * a real outage obvious.
 */
async function runWeeklyHealthCheck(env: Env): Promise<ComponentHealth[]> {
  const [supabasePings, portalHealth, releasesHealth] = await Promise.all([
    pingWithRetry(env),
    checkOnce('Portal', env.PORTAL_URL, 200),
    checkOnce(
      'GitHub Releases API',
      `https://api.github.com/repos/${env.GITHUB_REPO}/releases/latest`,
      200,
      { headers: { 'User-Agent': 'rd-scanner-keepalive-worker/1.0' } },
    ),
  ]);
  const supabaseSucceeded = supabasePings.some((r) => r.ok);
  const supabaseLatency = supabasePings.reduce((sum, r) => sum + r.latencyMs, 0);
  const finalStatus = supabasePings[supabasePings.length - 1].status;
  const supabaseHealth: ComponentHealth = {
    name: 'Supabase',
    ok: supabaseSucceeded,
    detail: supabaseSucceeded
      ? `HTTP ${finalStatus} (${supabasePings.length} attempt(s))`
      : `HTTP ${finalStatus} after ${supabasePings.length} attempt(s) - ${supabasePings[supabasePings.length - 1].error ?? 'no body'}`,
    latencyMs: supabaseLatency,
  };
  return [supabaseHealth, portalHealth, releasesHealth];
}

/**
 * Builds the plain-text email body. Kept plain-text (not HTML) so
 * spam filters don't downgrade delivery + no risk of an HTML-render
 * bug hiding a red flag. First line is the overall verdict so an
 * inbox preview shows it without opening.
 */
function formatEmailBody(healths: readonly ComponentHealth[]): string {
  const allOk = healths.every((h) => h.ok);
  const verdict = allOk ? 'All systems healthy.' : 'ATTENTION: at least one component reports unhealthy.';
  const lines: string[] = [verdict, ''];
  for (const h of healths) {
    const flag = h.ok ? '[OK]' : '[FAIL]';
    lines.push(`${flag} ${h.name}: ${h.detail} (${h.latencyMs}ms)`);
  }
  lines.push('');
  lines.push(`Report generated at ${new Date().toISOString()}`);
  lines.push('Next report: 7 days from now (Monday 08:00 UTC).');
  lines.push('');
  lines.push('If any component reports FAIL, check the Cloudflare Worker logs at:');
  lines.push('https://dash.cloudflare.com/?to=/:account/workers/services/view/rd-scanner-supabase-keepalive');
  return lines.join('\n');
}

/**
 * Sends the health-check email via Resend REST API. Returns true on
 * 2xx. Logs the response body verbatim on failure so a rotated /
 * disabled key produces a diagnosable log line.
 */
async function sendEmail(env: Env, subject: string, body: string): Promise<boolean> {
  const res = await fetch('https://api.resend.com/emails', {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      Authorization: `Bearer ${env.RESEND_API_KEY}`,
    },
    body: JSON.stringify({
      from: env.RESEND_FROM,
      to: [env.RECIPIENT_EMAIL],
      subject,
      text: body,
    }),
  });
  if (res.status >= 200 && res.status < 300) {
    console.log(JSON.stringify({ trigger: 'weekly-email', emailStatus: res.status }));
    return true;
  }
  const errorBody = await res.text().catch(() => '(no body)');
  console.error(
    JSON.stringify({
      trigger: 'weekly-email',
      emailStatus: res.status,
      errorBody,
    }),
  );
  return false;
}

export default {
  async scheduled(event: ScheduledEvent, env: Env, ctx: ExecutionContext): Promise<void> {
    if (event.cron === '0 3 */3 * *') {
      ctx.waitUntil(
        (async () => {
          const results = await pingWithRetry(env);
          logResults(results, 'supabase-keepalive');
        })(),
      );
      return;
    }
    if (event.cron === '0 8 * * 1') {
      ctx.waitUntil(
        (async () => {
          const healths = await runWeeklyHealthCheck(env);
          const allOk = healths.every((h) => h.ok);
          const subject = allOk
            ? '[RD Scanner] Weekly health: all systems healthy'
            : '[RD Scanner] Weekly health: ATTENTION needed';
          const body = formatEmailBody(healths);
          await sendEmail(env, subject, body);
          console.log(
            JSON.stringify({
              trigger: 'weekly-email',
              allOk,
              components: healths.map((h) => ({ name: h.name, ok: h.ok })),
            }),
          );
        })(),
      );
      return;
    }
    console.error(
      JSON.stringify({
        trigger: 'unknown-cron',
        cron: event.cron,
        message: 'No handler matched this cron expression',
      }),
    );
  },
};
