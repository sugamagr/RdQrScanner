/**
 * RD Scanner ops Worker.
 *
 * One hourly cron does three jobs:
 *
 *   1. HEARTBEAT (every run). Calls the database function
 *      keepalive_beat(), which writes a row, then reads a real table.
 *      Supabase pauses a Free plan project that "does not receive
 *      sufficient user database activity over the past week" and says
 *      "a few user requests to the database each day" is typically
 *      enough. This is 24 writes and 24 reads a day.
 *
 *   2. SELF-HEAL (only when the heartbeat fails). Asks the Supabase
 *      Management API for the project status. If the project is paused
 *      (INACTIVE) it calls the restore endpoint, waits for the project
 *      to come back and emails the outcome. Other problems are emailed
 *      at the reminder hours, at most twice a day.
 *
 *   3. WEEKLY DIGEST (Monday 07:17 UTC = 12:47 IST). Emails the state
 *      of Supabase, the portal and GitHub Releases, including how many
 *      heartbeats the database recorded, which proves the hourly
 *      schedule really ran.
 *
 * WHY ONE CRON AND NOT THREE: the Worker picks the job from the
 * scheduled time, so nothing depends on matching cron strings or on
 * Cloudflare's day-of-week numbering (1 = Sunday there, which made the
 * old "Monday" digest fire on Sundays).
 *
 * WHY NO STORED STATE: the Worker keeps nothing between runs. A problem
 * that needs you is repeated at the reminder hours until it is fixed;
 * a pause that the Worker repairs produces exactly one email, because
 * the next run sees a project that is no longer paused.
 *
 * No HTTP handler is exposed. Local run: see README.md.
 */

export interface Env {
  SUPABASE_URL: string;
  /** The 20-letter id in the project URL and dashboard address. */
  SUPABASE_PROJECT_REF: string;
  SUPABASE_ANON_KEY: string;
  /**
   * Supabase account access token for the Management API. Optional:
   * without it the Worker still sends heartbeats but cannot check
   * whether the project is paused or restore it.
   */
  SUPABASE_ACCESS_TOKEN?: string;
  PORTAL_URL: string;
  GITHUB_REPO: string;
  RECIPIENT_EMAIL: string;
  RESEND_FROM: string;
  RESEND_API_KEY: string;
}

/** Waits and limits. Overridden only by the tests. */
export interface Timings {
  requestTimeoutMs: number;
  retryDelayMs: number;
  pollIntervalMs: number;
  pollMaxAttempts: number;
}

/** Base URLs of the outside services. Overridden only by the tests. */
export interface Endpoints {
  managementApi: string;
  githubApi: string;
  resendApi: string;
}

export const DEFAULT_TIMINGS: Timings = {
  requestTimeoutMs: 15_000,
  retryDelayMs: 3_000,
  // 14 checks, 30 seconds apart: the Worker waits up to 7 minutes for a
  // restore to finish. A cron run may last 15 minutes; with every
  // request timing out this run would still end after about 12.
  pollIntervalMs: 30_000,
  pollMaxAttempts: 14,
};

export const DEFAULT_ENDPOINTS: Endpoints = {
  managementApi: 'https://api.supabase.com',
  githubApi: 'https://api.github.com',
  resendApi: 'https://api.resend.com',
};

/** Hours (UTC) at which an unresolved problem is emailed: 08:47 and 20:47 IST. */
const REMINDER_HOURS_UTC = [3, 15];
/** Weekly digest: Monday (JS getUTCDay() === 1) in the 07:00 UTC hour. */
const DIGEST_DAY_UTC = 1;
const DIGEST_HOUR_UTC = 7;
/** Fewer heartbeats than this in a full day means the schedule is missing runs. */
const MIN_BEATS_PER_FULL_DAY = 20;

interface BeatStats {
  serverTime: string;
  beatsToday: number;
  beatsYesterday: number;
  beats7d: number;
  yesterdayFull: boolean;
}

interface Heartbeat {
  /** The database answered a real query. */
  ok: boolean;
  /** keepalive_beat() ran, so a row was written. */
  wrote: boolean;
  /** HTTP status of the request that decided the result; 0 = no response. */
  status: number;
  detail: string;
  latencyMs: number;
  attempts: number;
  stats?: BeatStats;
}

type ProjectState =
  | { kind: 'status'; status: string }
  | { kind: 'no-token' }
  | { kind: 'error'; httpStatus: number; detail: string };

interface Alert {
  subject: string;
  lines: string[];
  /** true: send in this run. false: send only at a reminder hour. */
  sendNow: boolean;
  /** Set when a restore brought the database back within this run. */
  recovered?: Heartbeat;
}

interface FetchOutcome {
  status: number;
  text: string;
  error?: string;
}

export interface RunReport {
  ok: boolean;
  wrote: boolean;
  status: number;
  attempts: number;
  latencyMs: number;
  alertSubject?: string;
  alertSent: boolean;
  digestSent: boolean;
}

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/** fetch that never throws and never hangs. Body is capped so a huge error page cannot blow up an email. */
async function timedFetch(url: string, init: RequestInit, timeoutMs: number): Promise<FetchOutcome> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(url, { ...init, signal: controller.signal });
    const text = (await res.text().catch(() => '')).slice(0, 2000);
    return { status: res.status, text };
  } catch (e) {
    return { status: 0, text: '', error: e instanceof Error ? e.message : String(e) };
  } finally {
    clearTimeout(timer);
  }
}

function describe(out: FetchOutcome): string {
  if (out.error) return `no response (${out.error})`;
  const snippet = out.text.replace(/\s+/g, ' ').trim().slice(0, 200);
  return snippet ? `HTTP ${out.status}: ${snippet}` : `HTTP ${out.status}`;
}

function parseStats(text: string): BeatStats | undefined {
  try {
    const raw = JSON.parse(text) as Record<string, unknown>;
    const serverTime = String(raw.server_time ?? '');
    const beatsToday = Number(raw.beats_today);
    if (!serverTime || Number.isNaN(Date.parse(serverTime)) || !Number.isFinite(beatsToday) || beatsToday < 1) {
      return undefined;
    }
    return {
      serverTime,
      beatsToday,
      beatsYesterday: Number(raw.beats_yesterday) || 0,
      beats7d: Number(raw.beats_7d) || 0,
      yesterdayFull: raw.yesterday_full === true,
    };
  } catch {
    return undefined;
  }
}

/**
 * One heartbeat: write through keepalive_beat(), then read a real table.
 *
 * If the function does not exist yet (HTTP 404, the SQL migration was
 * not run) the read alone still counts as a database request, so the
 * project keeps getting activity while the setup is finished. That case
 * returns ok = true, wrote = false.
 */
async function heartbeatOnce(env: Env, t: Timings): Promise<Omit<Heartbeat, 'attempts'>> {
  const base = env.SUPABASE_URL.replace(/\/+$/, '');
  const headers = {
    apikey: env.SUPABASE_ANON_KEY,
    Authorization: `Bearer ${env.SUPABASE_ANON_KEY}`,
    'content-type': 'application/json',
  };
  const start = Date.now();
  const readTable = () =>
    timedFetch(`${base}/rest/v1/devices?select=id&limit=1`, { method: 'GET', headers }, t.requestTimeoutMs);

  const write = await timedFetch(
    `${base}/rest/v1/rpc/keepalive_beat`,
    { method: 'POST', headers, body: '{}' },
    t.requestTimeoutMs,
  );

  if (write.status === 200) {
    const stats = parseStats(write.text);
    if (!stats) {
      return {
        ok: false,
        wrote: false,
        status: 200,
        detail: `keepalive_beat() answered with something unexpected: ${describe(write)}`,
        latencyMs: Date.now() - start,
      };
    }
    const read = await readTable();
    return {
      ok: true,
      wrote: true,
      status: 200,
      detail:
        read.status === 200
          ? 'heartbeat written and table read back'
          : `heartbeat written (table read returned ${describe(read)})`,
      latencyMs: Date.now() - start,
      stats,
    };
  }

  if (write.status === 404) {
    const read = await readTable();
    if (read.status === 200) {
      return {
        ok: true,
        wrote: false,
        status: 404,
        detail: 'database answered a read, but the keepalive_beat() function does not exist',
        latencyMs: Date.now() - start,
      };
    }
    return { ok: false, wrote: false, status: read.status, detail: describe(read), latencyMs: Date.now() - start };
  }

  return { ok: false, wrote: false, status: write.status, detail: describe(write), latencyMs: Date.now() - start };
}

/** One retry after a short wait. A rejected key (401/403) is not retried: it will not fix itself. */
async function heartbeat(env: Env, t: Timings): Promise<Heartbeat> {
  const first = await heartbeatOnce(env, t);
  if (first.ok || first.status === 401 || first.status === 403) return { ...first, attempts: 1 };
  await sleep(t.retryDelayMs);
  const second = await heartbeatOnce(env, t);
  return { ...second, attempts: 2 };
}

function managementHeaders(env: Env): Record<string, string> {
  return { Authorization: `Bearer ${env.SUPABASE_ACCESS_TOKEN ?? ''}`, Accept: 'application/json' };
}

async function getProjectState(env: Env, e: Endpoints, t: Timings): Promise<ProjectState> {
  if (!env.SUPABASE_ACCESS_TOKEN) return { kind: 'no-token' };
  const out = await timedFetch(
    `${e.managementApi}/v1/projects/${env.SUPABASE_PROJECT_REF}`,
    { method: 'GET', headers: managementHeaders(env) },
    t.requestTimeoutMs,
  );
  if (out.status === 200) {
    try {
      const status = (JSON.parse(out.text) as { status?: unknown }).status;
      if (typeof status === 'string' && status) return { kind: 'status', status };
    } catch {
      // fall through to the error below
    }
    return { kind: 'error', httpStatus: 200, detail: 'the answer had no project status in it' };
  }
  return { kind: 'error', httpStatus: out.status, detail: describe(out) };
}

async function requestRestore(env: Env, e: Endpoints, t: Timings): Promise<FetchOutcome> {
  return timedFetch(
    `${e.managementApi}/v1/projects/${env.SUPABASE_PROJECT_REF}/restore`,
    { method: 'POST', headers: managementHeaders(env) },
    t.requestTimeoutMs,
  );
}

function dashboardUrl(env: Env): string {
  return `https://supabase.com/dashboard/project/${env.SUPABASE_PROJECT_REF}`;
}

/** "2026-11-19 11:17 UTC (16:47 IST)" */
function formatTime(d: Date): string {
  const utc = d.toISOString().slice(0, 16).replace('T', ' ');
  const ist = new Date(d.getTime() + 330 * 60_000).toISOString().slice(11, 16);
  return `${utc} UTC (${ist} IST)`;
}

const MANUAL_RESUME = (env: Env): string[] => [
  'What to do:',
  `  1. Open ${dashboardUrl(env)}`,
  '  2. If it says the project is paused, press "Resume project" and wait about a minute.',
  'Nothing is lost while it is paused. Phones keep scanning offline and sync when it is back.',
];

const NEW_TOKEN = (): string[] => [
  'What to do:',
  '  1. Open https://supabase.com/dashboard/account/tokens and create a new access token.',
  '  2. In the supabase-keepalive folder run: npx wrangler secret put SUPABASE_ACCESS_TOKEN',
  '     and paste the new token.',
];

/**
 * The heartbeat failed. Find out why, repair what can be repaired, and
 * describe the result. Only a pause that was handed to the restore
 * endpoint is emailed straight away; every other case waits for a
 * reminder hour so one bad hour cannot produce a stream of emails.
 */
async function handleFailure(env: Env, beat: Heartbeat, when: Date, e: Endpoints, t: Timings): Promise<Alert> {
  const seen = [`Database request failed: ${beat.detail}`, `Checked at: ${formatTime(when)}`, ''];
  const state = await getProjectState(env, e, t);

  if (state.kind === 'no-token') {
    return {
      subject: '[RD Scanner] ACTION NEEDED: Supabase is not answering',
      sendNow: false,
      lines: [
        'The database is not answering, and the Worker has no Supabase access token,',
        'so it cannot tell whether the project is paused and cannot restore it.',
        '',
        ...seen,
        ...MANUAL_RESUME(env),
        '',
        'To let the Worker restore the project by itself next time, add the token:',
        ...NEW_TOKEN().slice(1),
      ],
    };
  }

  if (state.kind === 'error') {
    const rejected = state.httpStatus === 401 || state.httpStatus === 403;
    return {
      subject: '[RD Scanner] ACTION NEEDED: Supabase is not answering',
      sendNow: false,
      lines: [
        'The database is not answering, and the Worker could not check the project status.',
        '',
        ...seen,
        `Status check failed: ${state.detail}`,
        rejected
          ? 'Supabase rejected the access token. It has probably expired or been deleted.'
          : 'This may be a short Supabase problem. The Worker checks again every hour.',
        '',
        ...MANUAL_RESUME(env),
        ...(rejected ? ['', 'Then replace the token so the Worker can restore the project itself:', ...NEW_TOKEN().slice(1)] : []),
      ],
    };
  }

  const status = state.status;

  if (status === 'INACTIVE') {
    const restore = await requestRestore(env, e, t);
    if (restore.status < 200 || restore.status >= 300) {
      const reason =
        restore.status === 401 || restore.status === 403
          ? 'Supabase rejected the access token for this action. It may have expired or lack permission.'
          : restore.status === 402
            ? 'Supabase refused the restore for this organization (plan limit, overdue invoice or a restriction).'
            : restore.status === 429
              ? 'Supabase asked the Worker to slow down. It will try again next hour.'
              : 'The Worker will try again next hour.';
      return {
        subject: '[RD Scanner] ACTION NEEDED: Supabase is paused and could not be restored automatically',
        sendNow: false,
        lines: [
          'Supabase paused the project for inactivity. The Worker asked Supabase to restore it,',
          'but the request was refused.',
          '',
          ...seen,
          `Restore request failed: ${describe(restore)}`,
          reason,
          '',
          ...MANUAL_RESUME(env),
        ],
      };
    }

    // Restore accepted. Wait for the project to come back so that one
    // email can report the outcome. Each check is a single request:
    // the status until it turns ACTIVE_HEALTHY, then the database
    // itself (a restored project can take a few more minutes before
    // its address resolves again).
    let last = 'unknown';
    let lastBeatDetail = '';
    let active = false;
    for (let i = 1; i <= t.pollMaxAttempts; i++) {
      await sleep(t.pollIntervalMs);
      if (!active) {
        const now = await getProjectState(env, e, t);
        if (now.kind !== 'status') continue;
        last = now.status;
        if (last === 'RESTORE_FAILED') break;
        if (last !== 'ACTIVE_HEALTHY') continue;
        active = true;
      }
      const again = await heartbeatOnce(env, t);
      if (again.ok) {
        const minutes = Math.max(1, Math.round((i * t.pollIntervalMs) / 60_000));
        return {
          subject: '[RD Scanner] Supabase was paused - restored automatically',
          sendNow: true,
          recovered: { ...again, attempts: 1 },
          lines: [
            'Supabase paused the project for inactivity. The Worker noticed and restored it.',
            '',
            `Status now: back online after about ${minutes} minute${minutes === 1 ? '' : 's'}. The database is answering again.`,
            'You do not need to do anything. Phones sync the next time the app is opened.',
            '',
            `Detected at: ${formatTime(when)}`,
            `Project: ${dashboardUrl(env)}`,
            '',
            'If this email keeps coming every week or two, the hourly heartbeat is not being',
            'counted by Supabase as activity. The project still repairs itself each time.',
          ],
        };
      }
      lastBeatDetail = again.detail;
    }

    if (last === 'INACTIVE') {
      // Supabase accepted the request but the project never left the
      // paused state. Treat it like a refusal so the hourly retries do
      // not send an email every hour.
      return {
        subject: '[RD Scanner] ACTION NEEDED: Supabase is paused and could not be restored automatically',
        sendNow: false,
        lines: [
          'Supabase paused the project for inactivity. The Worker asked Supabase to restore it',
          'and the request was accepted, but the project is still paused.',
          '',
          ...seen,
          'The Worker tries again every hour.',
          '',
          ...MANUAL_RESUME(env),
        ],
      };
    }

    if (last === 'RESTORE_FAILED') {
      return {
        subject: '[RD Scanner] ACTION NEEDED: Supabase restore failed',
        sendNow: true,
        lines: [
          'Supabase paused the project for inactivity. The Worker started a restore,',
          'but Supabase reports that the restore failed.',
          '',
          `Detected at: ${formatTime(when)}`,
          '',
          ...MANUAL_RESUME(env),
          'If the dashboard cannot resume it either, contact Supabase support from that page.',
        ],
      };
    }

    const waited = Math.round((t.pollMaxAttempts * t.pollIntervalMs) / 60_000);
    return {
      subject: '[RD Scanner] Supabase was paused - restore started',
      sendNow: true,
      lines: [
        'Supabase paused the project for inactivity. The Worker noticed and started the restore.',
        '',
        `Status after ${waited} minutes: ${last}${lastBeatDetail ? ` (database request: ${lastBeatDetail})` : ''}.`,
        'Restores sometimes take longer. The Worker checks again every hour and will email',
        'again only if the project is still not working at the next reminder time.',
        '',
        `Detected at: ${formatTime(when)}`,
        `Project: ${dashboardUrl(env)}`,
      ],
    };
  }

  if (status === 'ACTIVE_HEALTHY' || status === 'ACTIVE_UNHEALTHY') {
    const keyRejected = beat.status === 401 || beat.status === 403;
    return {
      subject: '[RD Scanner] Supabase heartbeat is failing',
      sendNow: false,
      lines: [
        `Supabase says the project is running (${status}), but the Worker's database request fails.`,
        '',
        ...seen,
        keyRejected
          ? 'Supabase rejected the anon key. If the key was changed, copy the new one from the dashboard ' +
            '(Settings, API keys) and run: npx wrangler secret put SUPABASE_ANON_KEY'
          : 'This may be a short Supabase problem. If this email repeats, open the dashboard and check the project.',
        '',
        `Project: ${dashboardUrl(env)}`,
      ],
    };
  }

  // PAUSING, GOING_DOWN, RESTORING, COMING_UP, RESTARTING and the rest:
  // the project is between states or needs a person. Restoring is only
  // possible once it is fully paused, which the next hourly run handles.
  return {
    subject: `[RD Scanner] Supabase is not answering (status ${status})`,
    sendNow: false,
    lines: [
      `The database is not answering. Supabase reports the project status as ${status}.`,
      '',
      ...seen,
      'If the status is PAUSING, RESTORING or COMING_UP this usually clears by itself;',
      'once the project is fully paused the Worker restores it on the next hourly run.',
      'If this email repeats, open the dashboard:',
      `  ${dashboardUrl(env)}`,
    ],
  };
}

async function sendEmail(env: Env, e: Endpoints, t: Timings, subject: string, lines: readonly string[]): Promise<boolean> {
  const out = await timedFetch(
    `${e.resendApi}/emails`,
    {
      method: 'POST',
      headers: { 'content-type': 'application/json', Authorization: `Bearer ${env.RESEND_API_KEY}` },
      body: JSON.stringify({ from: env.RESEND_FROM, to: [env.RECIPIENT_EMAIL], subject, text: lines.join('\n') }),
    },
    t.requestTimeoutMs,
  );
  const sent = out.status >= 200 && out.status < 300;
  if (!sent) console.error(JSON.stringify({ event: 'email-failed', subject, result: describe(out) }));
  return sent;
}

interface DigestLine {
  ok: boolean;
  /** false for information that should not turn the subject to ATTENTION. */
  counts: boolean;
  text: string;
}

async function checkUrl(name: string, url: string, init: RequestInit, t: Timings): Promise<DigestLine> {
  const out = await timedFetch(url, init, t.requestTimeoutMs);
  const ok = out.status === 200;
  return { ok, counts: true, text: `${name}: ${ok ? 'HTTP 200' : describe(out)}` };
}

async function buildDigest(env: Env, beat: Heartbeat, e: Endpoints, t: Timings): Promise<{ subject: string; lines: string[] }> {
  const items: DigestLine[] = [];

  if (!beat.ok) {
    items.push({ ok: false, counts: true, text: `Supabase database: ${beat.detail}` });
  } else if (!beat.wrote) {
    items.push({
      ok: false,
      counts: true,
      text:
        'Supabase database: answering, but the keepalive_beat() function is missing.\n' +
        '       Run cloud/migrations/v14_keepalive_heartbeat.sql in the Supabase SQL editor.',
    });
  } else {
    const s = beat.stats!;
    const thin = s.yesterdayFull && s.beatsYesterday < MIN_BEATS_PER_FULL_DAY;
    items.push({
      ok: !thin,
      counts: true,
      text:
        `Supabase database: ${beat.detail} (${beat.latencyMs} ms)\n` +
        `       heartbeats recorded: ${s.beatsYesterday} yesterday, ${s.beats7d} in the last 7 days (24 a day expected)` +
        (thin ? '\n       Too few yesterday: the hourly schedule missed runs or the database was down.' : ''),
    });
  }

  const [state, portal, releases] = await Promise.all([
    getProjectState(env, e, t),
    checkUrl('Portal', env.PORTAL_URL, {}, t),
    checkUrl(
      'GitHub Releases',
      `${e.githubApi}/repos/${env.GITHUB_REPO}/releases/latest`,
      { headers: { 'User-Agent': 'rd-scanner-ops-worker', Accept: 'application/vnd.github+json' } },
      t,
    ),
  ]);

  if (state.kind === 'status') {
    items.push({
      ok: state.status === 'ACTIVE_HEALTHY',
      counts: true,
      text:
        state.status === 'ACTIVE_HEALTHY'
          ? 'Supabase project status: ACTIVE_HEALTHY. Automatic restore is ready.'
          : `Supabase project status: ${state.status}`,
    });
  } else if (state.kind === 'no-token') {
    items.push({
      ok: false,
      counts: false,
      text:
        'Automatic restore: OFF (no SUPABASE_ACCESS_TOKEN set). Heartbeats still run,\n' +
        '       but a paused project would need you to press Resume in the dashboard.',
    });
  } else {
    const rejected = state.httpStatus === 401 || state.httpStatus === 403;
    items.push({
      ok: false,
      counts: true,
      text:
        `Automatic restore: NOT WORKING. Status check failed: ${state.detail}` +
        (rejected ? '\n       The access token was rejected. Create a new one and run:\n       npx wrangler secret put SUPABASE_ACCESS_TOKEN' : ''),
    });
  }

  items.push(portal, releases);

  const allOk = items.every((i) => i.ok || !i.counts);
  const flag = (i: DigestLine) => (i.ok ? '[OK]  ' : i.counts ? '[FAIL]' : '[NOTE]');
  return {
    subject: allOk ? '[RD Scanner] Weekly health: all systems healthy' : '[RD Scanner] Weekly health: ATTENTION needed',
    lines: [
      allOk ? 'All systems healthy.' : 'ATTENTION: at least one item below needs a look.',
      '',
      ...items.map((i) => `${flag(i)} ${i.text}`),
      '',
      `Report time: ${formatTime(new Date())}`,
      'Next report: next Monday. Problems in between are emailed when they happen.',
      `Supabase dashboard: ${dashboardUrl(env)}`,
    ],
  };
}

/**
 * One scheduled run. `when` is the time the cron was scheduled for, so
 * a late delivery still maps to the hour it was meant for.
 */
export async function run(
  env: Env,
  when: Date,
  endpoints: Endpoints = DEFAULT_ENDPOINTS,
  timings: Timings = DEFAULT_TIMINGS,
): Promise<RunReport> {
  const hour = when.getUTCHours();
  const reminderHour = REMINDER_HOURS_UTC.includes(hour);
  const digestHour = when.getUTCDay() === DIGEST_DAY_UTC && hour === DIGEST_HOUR_UTC;

  let beat = await heartbeat(env, timings);
  let alert: Alert | undefined;

  if (!beat.ok) {
    alert = await handleFailure(env, beat, when, endpoints, timings);
    if (!alert.sendNow && reminderHour) {
      // About to send a reminder: give a short outage one more chance
      // to clear so a blip at exactly this hour does not alarm anyone.
      await sleep(timings.pollIntervalMs);
      const again = await heartbeat(env, timings);
      if (again.ok) {
        beat = again;
        alert = undefined;
      }
    } else if (alert.recovered) {
      // A restore brought it back. Use that state for the log and digest.
      beat = alert.recovered;
    }
  }

  if (!alert && beat.ok && !beat.wrote) {
    alert = {
      subject: '[RD Scanner] Setup step missing: heartbeat function',
      sendNow: false,
      lines: [
        'The Worker reaches the database, but the keepalive_beat() function does not exist,',
        'so no heartbeat row is written. Reads alone may not be enough to stop Supabase',
        'from pausing the project.',
        '',
        'What to do (once):',
        `  1. Open ${dashboardUrl(env)}/sql/new`,
        '  2. Paste the contents of cloud/migrations/v14_keepalive_heartbeat.sql and press Run.',
      ],
    };
  }

  let alertSent = false;
  if (alert && (alert.sendNow || reminderHour)) {
    alertSent = await sendEmail(env, endpoints, timings, alert.subject, alert.lines);
  }

  let digestSent = false;
  if (digestHour) {
    const digest = await buildDigest(env, beat, endpoints, timings);
    digestSent = await sendEmail(env, endpoints, timings, digest.subject, digest.lines);
  }

  const report: RunReport = {
    ok: beat.ok,
    wrote: beat.wrote,
    status: beat.status,
    attempts: beat.attempts,
    latencyMs: beat.latencyMs,
    alertSubject: alert?.subject,
    alertSent,
    digestSent,
  };
  const line = JSON.stringify({ event: 'run', scheduled: when.toISOString(), detail: beat.detail, ...report });
  if (beat.ok && beat.wrote) console.log(line);
  else console.error(line);
  return report;
}

export default {
  async scheduled(event: ScheduledEvent, env: Env, _ctx: ExecutionContext): Promise<void> {
    // Awaited directly (not waitUntil) so the run is allowed its full
    // 15 minutes while it waits for a restore to finish.
    await run(env, new Date(event.scheduledTime));
  },
};
