# Supabase keep-alive

Cloudflare Worker cron that pings the RD Scanner's Supabase project
every 3 days so the free tier doesn't auto-pause it.

## Why this exists

Supabase free tier auto-pauses a project after 7 days without database
activity. When paused:

- DNS entry is removed (`otkhvevvddxclbormkfo.supabase.co` returns
  NXDOMAIN)
- Every phone opening the app sees "not online" errors
- Every portal user sees connection failed
- The `releases/latest` GitHub API still returns whatever tag is
  live, so the force-update gate says "you're up to date" — but the
  app doesn't work anyway because the backend is gone

This happened once in the wild (see main repo's compressed session
history, block b25). Recovery was fast (click Resume on the Supabase
dashboard) but it's a real operational risk for a 2-5 phone shop
that goes quiet over long weekends.

The cron fires every 3 days at 03:00 UTC (08:30 IST) via Cloudflare's
scheduler. Each firing does one authenticated HEAD request against
the devices table, which counts as DB activity and resets the
inactivity timer.

## First deploy

```bash
cd supabase-keepalive
npm install

# Log in to Cloudflare (browser opens; select the account that owns
# the rd-scanner-portal project so both live under the same account).
npx wrangler login

# Set the Supabase anon key as a secret. Value comes from the
# SUPABASE_ANON_KEY line in local.properties at the repo root.
# (The anon key is technically public — it's baked into the APK —
# but Workers secrets get audit logs, so we treat it as one anyway.)
npx wrangler secret put SUPABASE_ANON_KEY
# Paste the value when prompted.

# Deploy the Worker.
npm run deploy
# Prints the Worker URL, something like
# https://rd-scanner-supabase-keepalive.<subdomain>.workers.dev
```

## Verify it works

Two independent checks — do both:

```bash
# 1. Manual HTTP trigger. Fires the ping right now, waits for the
#    result, returns JSON. Should return 200 + succeeded=true.
curl https://rd-scanner-supabase-keepalive.<subdomain>.workers.dev/

# 2. Manual cron trigger via wrangler. Runs the scheduled handler
#    against local dev (also connects to real Supabase).
npx wrangler dev --test-scheduled
# Then in another terminal:
curl 'http://localhost:8787/__scheduled?cron=0+3+*/3+*+*'
# Watch the dev terminal for the log line.
```

Expected success log line:

```
{"trigger":"cron","succeeded":true,"attempts":1,"finalStatus":200,
 "totalLatencyMs":230,"results":[...],"at":"2026-07-21T03:00:00.000Z"}
```

Failure log lines are structured JSON with `"succeeded":false` and
show up as `console.error` — surfaces as errors in the Cloudflare
Worker analytics dashboard.

## Monitoring

Log tail from CLI:

```bash
npm run tail
```

Or the Cloudflare dashboard: Workers & Pages -> your Worker ->
Observability -> Logs. Filter by `error` level to see only failures.

The cron history is visible under: Workers & Pages -> your Worker ->
Triggers -> Cron Triggers -> Recent invocations.

## What happens if a ping fails

Fail-safe by design:

- **One transient 5xx or timeout**: retries after 2 seconds. If the
  retry succeeds you get a warning log but the project stays alive.
- **Two transient failures**: log an error. Next cron in 3 days will
  try again. Supabase's pause threshold is 7 days so we've got 4
  days of slack before an outage.
- **Auth failure (401/403)**: means SUPABASE_ANON_KEY drifted (rotated,
  or the project was recreated with a new one). Fix:
  `npx wrangler secret put SUPABASE_ANON_KEY` with the new value from
  the Supabase dashboard -> Settings -> API.
- **Worker itself crashes**: Cloudflare's cron retry policy invokes
  it again at the next scheduled time. No data loss because the
  Worker is stateless.
- **Cloudflare has an outage**: extremely rare, and if it lasts
  longer than 4 days simultaneously with Supabase's activity check
  running... we've got bigger problems. Manual dashboard resume is
  still available as backup.

## Rotating the anon key

If Supabase rotates the anon key (Settings -> API -> Rotate anon
key):

```bash
cd supabase-keepalive
npx wrangler secret put SUPABASE_ANON_KEY
# Paste new key when prompted.
```

No redeploy needed. Secrets take effect immediately for the next
invocation.

## Cost

Cloudflare Workers free tier:
- 100,000 requests/day (cron uses ~10/month = 0.033% of daily quota)
- 3 million CPU-ms/day (each ping uses ~50 CPU-ms = negligible)
- Free for scheduled/cron triggers

So: zero cost, expected to stay zero cost even if we increased the
ping frequency to hourly.
