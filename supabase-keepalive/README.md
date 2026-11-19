# RD Scanner ops Worker

A Cloudflare Worker that keeps the free Supabase project from being
paused, restores it by itself if it gets paused anyway, and emails you
only when something happened. Runs on Cloudflare's free tier, $0/month.

## Why it exists

Supabase pauses a Free plan project that "does not receive sufficient
user database activity over the past week". Its documentation says
"typically a few user requests to the database each day" is enough and
adds that this is not a guarantee. A paused project is unreachable:
phones and the portal cannot sync until someone presses Resume.

The first version of this Worker sent one anonymous read every 3 days.
That is far below "a few requests each day", so Supabase kept pausing
the project while the Worker reported success. This version replaces it.

## What it does

One cron runs every hour at minute 17 (UTC).

| When | What |
|---|---|
| Every run | **Heartbeat.** Calls the database function `keepalive_beat()`, which writes a row, then reads the `devices` table. 24 writes and 24 reads a day. |
| Only if the heartbeat fails | **Self-heal.** Asks the Supabase Management API for the project status. If the project is paused it calls the restore endpoint, waits up to 7 minutes for it to come back, and emails the result. |
| 03:17 and 15:17 UTC (08:47 and 20:47 IST) | **Reminder.** If a problem is still unresolved and needs you, it is emailed at these two hours, so at most twice a day. |
| Monday 07:17 UTC (12:47 IST) | **Weekly email.** State of Supabase, the portal and GitHub Releases, plus how many heartbeats the database recorded. |

The Worker stores nothing between runs. A pause it repairs produces
one email, because the next run sees a project that is no longer paused.

## Emails you can get

| Subject | Meaning | What to do |
|---|---|---|
| `Weekly health: all systems healthy` | Normal Monday email. | Nothing. |
| `Weekly health: ATTENTION needed` | One line in the email is marked `[FAIL]`. | Read that line; it says what to do. |
| `Supabase was paused - restored automatically` | Supabase paused the project and the Worker brought it back. | Nothing. If it repeats every week or two, the heartbeat is not being counted; the project still repairs itself. |
| `Supabase was paused - restore started` | Restore was started but took longer than 7 minutes. | Nothing unless a reminder follows. |
| `ACTION NEEDED: Supabase is paused and could not be restored automatically` | Supabase refused the restore request. | Open the dashboard and press **Resume project**. |
| `ACTION NEEDED: Supabase is not answering` | The database is down and the Worker has no working access token to check or restore it. | Resume from the dashboard, then renew the token (see below). |
| `Supabase heartbeat is failing` | The project is running but the Worker's request is rejected, usually a changed anon key. | Update `SUPABASE_ANON_KEY`. |
| `Setup step missing: heartbeat function` | The SQL in step 1 below was never run. | Run it. |

No Monday email at all means the Worker itself stopped. Supabase says
it also emails its own warning about a week before it pauses a project,
which is a second line of warning if that ever happens.

## Setup

Do these in order. Steps 1 and 3 are done once.

### 1. Create the heartbeat function (Supabase)

Open the project's SQL editor, paste the contents of
[`../cloud/migrations/v14_keepalive_heartbeat.sql`](../cloud/migrations/v14_keepalive_heartbeat.sql)
and press Run. It adds one small table and one function, touches no app
data, and is safe to run again.

### 2. Log in to Cloudflare

```bash
cd supabase-keepalive
npm install
npx wrangler login
```

Pick the account that owns `rd-scanner-portal`. The API token in
`local.properties` is for the portal only and cannot deploy Workers.

### 3. Set the secrets

```bash
npx wrangler secret put SUPABASE_ANON_KEY       # from local.properties
npx wrangler secret put RESEND_API_KEY          # from resend.com, "Sending access"
npx wrangler secret put SUPABASE_ACCESS_TOKEN   # see below
```

Secrets already set on the Worker stay set; only new or changed ones
need this.

**Creating `SUPABASE_ACCESS_TOKEN`:**

1. Open <https://supabase.com/dashboard/account/tokens> and generate a
   new token.
2. Scope it to this project only and give it **Project Settings:
   Read-write**. That is the permission Supabase lists for reading the
   project status and for "Restore a project".
3. Choose the longest expiry offered ("never" if available).
4. Copy the token and paste it into the `wrangler secret put` prompt.

When the token expires or is deleted, the Monday email shows
`[FAIL] Automatic restore: NOT WORKING`. Create a new token and run the
`secret put` command again. Heartbeats do not depend on this token.

### 4. Deploy

```bash
npm run deploy
```

The output should list one schedule: `17 * * * *`. Cloudflare can take
up to 15 minutes to start running a changed schedule.

## Check that it works

- **Database:** in the Supabase SQL editor run
  `select * from public.keepalive_heartbeat order by day desc;`
  The `beats` number for today goes up by one every hour.
- **Worker:** Cloudflare dashboard, Workers & Pages,
  `rd-scanner-supabase-keepalive`, Observability. Each run logs one line
  starting `{"event":"run"`. A healthy run has `"ok":true,"wrote":true`.
- **Live log from the terminal:** `npm run tail`

### Proving the self-heal

The restore path cannot be exercised without a paused project. To see
it work once: pause the project yourself in the Supabase dashboard
(Project Settings, General, Pause project). At the next minute 17 the
Worker should restore it and send `Supabase was paused - restored
automatically`. Pausing and resuming keeps all data.

## Tests

```bash
npm test          # 20 cases against a local stand-in; sends nothing
npm run typecheck
```

The tests run the real Worker logic against a local server that plays
Supabase, the Management API, Resend, GitHub and the portal. They cover
the healthy run, retries, every pause and restore outcome, missing or
expired tokens, the reminder throttle and the weekly email. Needs
Node 23.6 or newer.

To run the Worker once locally against the real Supabase project:

```bash
npx wrangler dev --test-scheduled
# in another terminal:
curl 'http://localhost:8787/__scheduled?cron=17+*+*+*+*'
```

This uses the values in `.dev.vars` (git-ignored). It sends a real
email only if the run finds a problem at a reminder hour, restores a
paused project, or is made in the Monday 07:00 UTC hour.

## What this does not guarantee

- Supabase does not publish the exact amount of activity it requires.
  Hourly writes are well above what its documentation describes, but
  only a paid plan is exempt from pausing by rule.
- The restore call is documented by Supabase and covered by the tests
  here, but it has not yet been seen restoring this project for real.
  If Supabase refuses it, you get the `ACTION NEEDED` email and resume
  from the dashboard as before.
- A paused project can be resumed from the dashboard for a limited
  time (Supabase currently says up to 1 year). After that only the
  backups can be downloaded.

## Cost

- Cloudflare Workers free tier: 100,000 requests a day and 5 cron
  triggers per account. This Worker uses 1 cron trigger and about 24
  runs a day, each making 2 outgoing requests when healthy (at most
  about 25 while restoring; the limit is 50).
- Resend free tier: 100 emails a day. Normal use is one email a week.
