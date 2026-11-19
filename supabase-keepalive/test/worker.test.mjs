// Runs the Worker's real logic against a local stand-in for Supabase,
// the Supabase Management API, Resend, GitHub and the portal. Nothing
// leaves this machine and no real email is sent.
//
//   npm test        (Node 23.6 or newer: it loads the .ts file directly)

import { test, before, after, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { run } from '../src/index.ts';

const REF = 'abcdefghijklmnopqrst';
const TOKEN = 'sbp_test_token';

/** Everything the stand-in server remembers; reset before each test. */
let world;
let server;
let base;

function freshWorld() {
  return {
    db: 'up', // up | down | no-function | bad-key
    dbFailuresLeft: 0, // the next N database requests get no answer
    project: 'ACTIVE_HEALTHY',
    restoreStatus: 200, // HTTP status answered to a restore request
    afterRestore: [], // statuses returned one by one after a restore
    dbRecoversWhenActive: true,
    beatsYesterday: 24,
    yesterdayFull: true,
    beats: 0,
    restoreCalls: 0,
    statusCalls: 0,
    emails: [],
    calls: [],
  };
}

function json(res, status, body) {
  res.writeHead(status, { 'content-type': 'application/json' });
  res.end(JSON.stringify(body));
}

function handle(req, res, body) {
  const url = new URL(req.url, 'http://x');
  const path = url.pathname;
  world.calls.push(`${req.method} ${path}`);

  // --- Supabase database API -------------------------------------------
  if (path.startsWith('/rest/v1/')) {
    if (world.dbFailuresLeft > 0) {
      world.dbFailuresLeft--;
      return req.socket.destroy();
    }
    if (world.db === 'down') return req.socket.destroy();
    if (world.db === 'bad-key' || req.headers.apikey !== 'anon-key') {
      return json(res, 401, { message: 'Invalid API key' });
    }
    if (path === '/rest/v1/rpc/keepalive_beat') {
      if (req.method !== 'POST') return json(res, 405, {});
      if (world.db === 'no-function') return json(res, 404, { code: 'PGRST202', message: 'function not found' });
      world.beats++;
      return json(res, 200, {
        server_time: new Date().toISOString(),
        beats_today: world.beats,
        beats_yesterday: world.beatsYesterday,
        beats_7d: world.beatsYesterday * 6 + world.beats,
        yesterday_full: world.yesterdayFull,
      });
    }
    if (path === '/rest/v1/devices') return json(res, 200, []);
    return json(res, 404, {});
  }

  // --- Supabase Management API -----------------------------------------
  if (path === `/v1/projects/${REF}` || path === `/v1/projects/${REF}/restore`) {
    if (req.headers.authorization !== `Bearer ${TOKEN}`) return json(res, 401, { message: 'Unauthorized' });
    if (path.endsWith('/restore')) {
      world.restoreCalls++;
      if (world.restoreStatus !== 200) return json(res, world.restoreStatus, { message: 'restore refused' });
      if (world.afterRestore.length) world.project = world.afterRestore.shift();
      return json(res, 200, {});
    }
    world.statusCalls++;
    const status = world.project;
    if (status === 'ACTIVE_HEALTHY' && world.dbRecoversWhenActive && world.restoreCalls > 0) world.db = 'up';
    // advance to the next state for the following status check
    if (world.restoreCalls > 0 && world.afterRestore.length) world.project = world.afterRestore.shift();
    return json(res, 200, { ref: REF, status });
  }

  // --- Resend ------------------------------------------------------------
  if (path === '/emails' && req.method === 'POST') {
    world.emails.push(JSON.parse(body));
    return json(res, 200, { id: 'email-1' });
  }

  // --- GitHub + portal ---------------------------------------------------
  if (path === '/repos/owner/repo/releases/latest') return json(res, 200, { tag_name: 'v1' });
  if (path === '/portal') {
    res.writeHead(200, { 'content-type': 'text/html' });
    return res.end('<html></html>');
  }
  return json(res, 404, {});
}

before(async () => {
  server = http.createServer((req, res) => {
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => handle(req, res, body));
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  base = `http://127.0.0.1:${server.address().port}`;
});

after(() => server.close());

beforeEach(() => {
  world = freshWorld();
});

const env = (over = {}) => ({
  SUPABASE_URL: base,
  SUPABASE_PROJECT_REF: REF,
  SUPABASE_ANON_KEY: 'anon-key',
  SUPABASE_ACCESS_TOKEN: TOKEN,
  PORTAL_URL: `${base}/portal`,
  GITHUB_REPO: 'owner/repo',
  RECIPIENT_EMAIL: 'owner@example.com',
  RESEND_FROM: 'ops@example.com',
  RESEND_API_KEY: 're_test',
  ...over,
});

const endpoints = () => ({ managementApi: base, githubApi: base, resendApi: base });
const timings = { requestTimeoutMs: 2000, retryDelayMs: 5, pollIntervalMs: 5, pollMaxAttempts: 6 };

const ORDINARY_HOUR = new Date('2026-11-19T11:17:00Z'); // Thursday
const REMINDER_HOUR = new Date('2026-11-19T03:17:00Z'); // Thursday
const DIGEST_HOUR = new Date('2026-11-23T07:17:00Z'); // Monday

const go = (when, over) => run(env(over), when, endpoints(), timings);

/** Cloudflare's Free plan allows 50 outgoing requests per run. */
const withinRequestBudget = () => assert.ok(world.calls.length < 50, `too many requests: ${world.calls.length}`);

test('healthy: one write and one read, no email, no status check', async () => {
  const r = await go(ORDINARY_HOUR);
  assert.equal(r.ok, true);
  assert.equal(r.wrote, true);
  assert.equal(r.attempts, 1);
  assert.deepEqual(world.calls, ['POST /rest/v1/rpc/keepalive_beat', 'GET /rest/v1/devices']);
  assert.equal(world.emails.length, 0);
});

test('one failed request is retried and causes no email', async () => {
  world.dbFailuresLeft = 1;
  const r = await go(ORDINARY_HOUR);
  assert.equal(r.ok, true);
  assert.equal(r.attempts, 2);
  assert.equal(world.statusCalls, 0);
  assert.equal(world.emails.length, 0);
});

test('paused: restores the project and sends exactly one email straight away', async () => {
  world.db = 'down';
  world.project = 'INACTIVE';
  world.afterRestore = ['RESTORING', 'COMING_UP', 'ACTIVE_HEALTHY'];
  const r = await go(ORDINARY_HOUR);
  assert.equal(world.restoreCalls, 1);
  assert.equal(r.ok, true, 'database answers again after the restore');
  assert.equal(world.emails.length, 1);
  assert.equal(world.emails[0].subject, '[RD Scanner] Supabase was paused - restored automatically');
  assert.match(world.emails[0].text, /You do not need to do anything/);
  assert.deepEqual(world.emails[0].to, ['owner@example.com']);
  withinRequestBudget();
});

test('paused, restore refused: silent at an ordinary hour, emailed at a reminder hour', async () => {
  world.db = 'down';
  world.project = 'INACTIVE';
  world.restoreStatus = 402;

  let r = await go(ORDINARY_HOUR);
  assert.equal(r.ok, false);
  assert.equal(world.restoreCalls, 1, 'still tries to restore every hour');
  assert.equal(world.emails.length, 0);

  r = await go(REMINDER_HOUR);
  assert.equal(world.restoreCalls, 2);
  assert.equal(world.emails.length, 1);
  assert.match(world.emails[0].subject, /ACTION NEEDED: Supabase is paused and could not be restored/);
  assert.match(world.emails[0].text, /HTTP 402/);
  assert.match(world.emails[0].text, new RegExp(`supabase.com/dashboard/project/${REF}`));
  withinRequestBudget();
});

test('paused, restore accepted but project stays paused: no hourly email storm', async () => {
  world.db = 'down';
  world.project = 'INACTIVE'; // afterRestore empty: status never changes
  const r = await go(ORDINARY_HOUR);
  assert.equal(r.ok, false);
  assert.equal(world.restoreCalls, 1);
  assert.equal(world.emails.length, 0);
  await go(REMINDER_HOUR);
  assert.equal(world.emails.length, 1);
  assert.match(world.emails[0].text, /accepted, but the project is still paused/);
});

test('paused, restore is slow: one "restore started" email', async () => {
  world.db = 'down';
  world.project = 'INACTIVE';
  world.afterRestore = ['RESTORING', 'COMING_UP']; // stays COMING_UP
  const r = await go(ORDINARY_HOUR);
  assert.equal(r.ok, false);
  assert.equal(world.emails.length, 1);
  assert.equal(world.emails[0].subject, '[RD Scanner] Supabase was paused - restore started');
  assert.match(world.emails[0].text, /COMING_UP/);
  withinRequestBudget();
});

test('paused, project active again but database slow to answer: stays within the request budget', async () => {
  world.db = 'down';
  world.project = 'INACTIVE';
  world.afterRestore = ['ACTIVE_HEALTHY'];
  world.dbRecoversWhenActive = false;
  await run(env(), ORDINARY_HOUR, endpoints(), { ...timings, pollMaxAttempts: 16 });
  assert.equal(world.emails.length, 1);
  assert.equal(world.emails[0].subject, '[RD Scanner] Supabase was paused - restore started');
  withinRequestBudget();
});

test('paused, Supabase reports the restore failed', async () => {
  world.db = 'down';
  world.project = 'INACTIVE';
  world.afterRestore = ['RESTORING', 'RESTORE_FAILED'];
  await go(ORDINARY_HOUR);
  assert.equal(world.emails.length, 1);
  assert.match(world.emails[0].subject, /ACTION NEEDED: Supabase restore failed/);
});

test('project is still pausing: no restore attempt yet, no email at an ordinary hour', async () => {
  world.db = 'down';
  world.project = 'PAUSING';
  await go(ORDINARY_HOUR);
  assert.equal(world.restoreCalls, 0);
  assert.equal(world.emails.length, 0);
});

test('database down and access token rejected: reminder explains the token', async () => {
  world.db = 'down';
  world.project = 'INACTIVE';
  await go(ORDINARY_HOUR, { SUPABASE_ACCESS_TOKEN: 'expired' });
  assert.equal(world.emails.length, 0);
  await go(REMINDER_HOUR, { SUPABASE_ACCESS_TOKEN: 'expired' });
  assert.equal(world.restoreCalls, 0);
  assert.equal(world.emails.length, 1);
  assert.match(world.emails[0].text, /rejected the access token/);
  assert.match(world.emails[0].text, /wrangler secret put SUPABASE_ACCESS_TOKEN/);
});

test('database down and no access token: never calls the Management API, reminder says how to resume', async () => {
  world.db = 'down';
  world.project = 'INACTIVE';
  await go(REMINDER_HOUR, { SUPABASE_ACCESS_TOKEN: undefined });
  assert.equal(world.statusCalls, 0);
  assert.equal(world.restoreCalls, 0);
  assert.equal(world.emails.length, 1);
  assert.match(world.emails[0].text, /no Supabase access token/);
  assert.match(world.emails[0].text, /Resume project/);
});

test('anon key rejected while the project is running: not retried, reminder names the key', async () => {
  world.db = 'bad-key';
  const r = await go(REMINDER_HOUR);
  assert.equal(r.attempts, 1);
  assert.equal(world.restoreCalls, 0);
  assert.equal(world.emails.length, 1);
  assert.equal(world.emails[0].subject, '[RD Scanner] Supabase heartbeat is failing');
  assert.match(world.emails[0].text, /SUPABASE_ANON_KEY/);
});

test('a short outage exactly at a reminder hour does not send an email', async () => {
  world.dbFailuresLeft = 2; // both normal attempts fail, the extra check succeeds
  const r = await go(REMINDER_HOUR);
  assert.equal(r.ok, true);
  assert.equal(world.emails.length, 0);
});

test('heartbeat function missing: a read still happens, reminder asks for the SQL step', async () => {
  world.db = 'no-function';
  let r = await go(ORDINARY_HOUR);
  assert.equal(r.ok, true);
  assert.equal(r.wrote, false);
  assert.ok(world.calls.includes('GET /rest/v1/devices'));
  assert.equal(world.emails.length, 0);
  r = await go(REMINDER_HOUR);
  assert.equal(world.emails.length, 1);
  assert.equal(world.emails[0].subject, '[RD Scanner] Setup step missing: heartbeat function');
  assert.match(world.emails[0].text, /v14_keepalive_heartbeat\.sql/);
});

test('weekly digest, all healthy', async () => {
  const r = await go(DIGEST_HOUR);
  assert.equal(r.digestSent, true);
  assert.equal(world.emails.length, 1);
  const mail = world.emails[0];
  assert.equal(mail.subject, '[RD Scanner] Weekly health: all systems healthy');
  assert.match(mail.text, /heartbeats recorded: 24 yesterday/);
  assert.match(mail.text, /ACTIVE_HEALTHY\. Automatic restore is ready/);
  assert.match(mail.text, /\[OK\]\s+Portal: HTTP 200/);
  assert.match(mail.text, /\[OK\]\s+GitHub Releases: HTTP 200/);
  assert.equal(world.statusCalls, 1, 'the digest verifies the access token once a week');
  assert.equal(world.restoreCalls, 0);
});

test('weekly digest flags a day with too few heartbeats', async () => {
  world.beatsYesterday = 7;
  await go(DIGEST_HOUR);
  assert.equal(world.emails[0].subject, '[RD Scanner] Weekly health: ATTENTION needed');
  assert.match(world.emails[0].text, /Too few yesterday/);
});

test('weekly digest does not judge the first, partial day', async () => {
  world.beatsYesterday = 7;
  world.yesterdayFull = false;
  await go(DIGEST_HOUR);
  assert.equal(world.emails[0].subject, '[RD Scanner] Weekly health: all systems healthy');
});

test('weekly digest without a token: noted, but not an alarm', async () => {
  await go(DIGEST_HOUR, { SUPABASE_ACCESS_TOKEN: undefined });
  assert.equal(world.emails[0].subject, '[RD Scanner] Weekly health: all systems healthy');
  assert.match(world.emails[0].text, /\[NOTE\] Automatic restore: OFF/);
});

test('weekly digest with a rejected token: alarm', async () => {
  await go(DIGEST_HOUR, { SUPABASE_ACCESS_TOKEN: 'expired' });
  assert.equal(world.emails[0].subject, '[RD Scanner] Weekly health: ATTENTION needed');
  assert.match(world.emails[0].text, /Automatic restore: NOT WORKING/);
});

test('weekly digest while the database is down still goes out', async () => {
  world.db = 'down';
  world.project = 'PAUSING';
  const r = await go(DIGEST_HOUR);
  assert.equal(r.digestSent, true);
  assert.equal(world.emails.length, 1);
  assert.equal(world.emails[0].subject, '[RD Scanner] Weekly health: ATTENTION needed');
  assert.match(world.emails[0].text, /\[FAIL\] Supabase project status: PAUSING/);
});
