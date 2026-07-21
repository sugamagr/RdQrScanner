# RD Scanner ops Worker

Cloudflare Worker with two cron jobs. Runs on Cloudflare's free tier,
$0/month.

## Cron 1 — Supabase keep-alive (every 3 days)

**Schedule**: `0 3 */3 * *` (03:00 UTC = 08:30 IST every 3 days)

Fires an authenticated REST HEAD against Supabase to prevent the free
tier's 7-day auto-pause. When Supabase auto-pauses:

- DNS returns NXDOMAIN for the project URL
- Every phone opening the app sees "not online" errors
- Every portal user sees connection failed
- Force-update gate itself keeps working (GitHub is a separate
  dependency) but the app is unusable because the backend is gone

This happened once in the wild (2026-07-02). Recovery was fast
(dashboard resume in 60 seconds) but the Worker prevents recurrence.

3-day cadence gives 4-day safety margin against a single missed
execution before hitting the pause threshold.

## Cron 2 — Weekly health digest (Mondays)

**Schedule**: `0 8 * * 1` (08:00 UTC Monday = 13:30 IST Monday)

Runs three independent checks in parallel:

1. Supabase REST HEAD (same as keep-alive)
2. Portal HTML fetch (`https://rd-scanner-portal.pages.dev`, expects 200)
3. GitHub Releases API (`sugamagr/RdQrScanner/releases/latest`, expects 200)

Then sends a plain-text digest email to `sugamagr@gmail.com` via
Resend. Email is sent regardless of outcome — a Monday afternoon
with NO email means the Worker itself broke, which is diagnostic.

Subject lines make the digest triageable from inbox preview:

- All healthy: `[RD Scanner] Weekly health: all systems healthy`
- Any failure: `[RD Scanner] Weekly health: ATTENTION needed`

## First deploy

```bash
cd supabase-keepalive
npm install

# Log in to Cloudflare (browser opens; pick the account that owns
# rd-scanner-portal so all infra stays under one account).
npx wrangler login

# Set the Supabase anon key. Value comes from SUPABASE_ANON_KEY in
# local.properties at repo root. Anon key is public (baked into APK)
# but Workers secrets get audit logs and rotation UI, so we treat it
# as one anyway.
npx wrangler secret put SUPABASE_ANON_KEY
# Paste the value when prompted.

# Set the Resend API key. See "Resend setup" below to get one.
npx wrangler secret put RESEND_API_KEY
# Paste the re_... key when prompted.

# One-time subdomain registration prerequisite for cron triggers.
# Even though this Worker is cron-only (workers_dev = false in
# wrangler.toml), Cloudflare requires the subdomain to exist as an
# account setting before it accepts ANY cron trigger. Visit:
#   https://dash.cloudflare.com/<account-id>/workers/onboarding
# Pick any subdomain name or accept Cloudflare's generated one.

npm run deploy
# Confirms both crons registered:
#   schedule: 0 3 */3 * *
#   schedule: 0 8 * * 1
```

## Resend setup

Free tier of Resend (`https://resend.com`) gives 100 emails/day and
a sandbox sender `onboarding@resend.dev`. Weekly cadence = 1
email/week = 1% of daily quota, massive headroom.

**Sandbox sender rule**: `onboarding@resend.dev` only delivers to the
Resend account owner's verified email address. If the Resend account
is `sugamagr@gmail.com` and `RECIPIENT_EMAIL` in `wrangler.toml` is
`sugamagr@gmail.com`, delivery works. To deliver to a different
address, verify a real sender domain on Resend (10 minutes of DNS
records via Cloudflare) and set `RESEND_FROM` in `wrangler.toml` to
`alerts@yourdomain.com`.

Steps:

1. Sign up at `https://resend.com` with `sugamagr@gmail.com`
2. Verify the sign-up email if Resend sends one
3. Visit `https://resend.com/api-keys`
4. Create API key: name it "RD Scanner ops Worker", scope "Sending
   access"
5. Copy the `re_...` key
6. Run `npx wrangler secret put RESEND_API_KEY` and paste

## Verify it works

Two independent checks:

```bash
# 1. Manual cron trigger against real infrastructure. Local dev server
#    runs on port 8787, /__scheduled endpoint accepts a cron string.
npx wrangler dev --test-scheduled

# In another terminal, trigger the Supabase keep-alive path:
curl 'http://localhost:8787/__scheduled?cron=0+3+*/3+*+*'
# Watch the dev terminal for the log line - should show
# succeeded=true, HTTP 200, low latency

# Trigger the weekly-email path (WILL send a real email if
# RESEND_API_KEY is set):
curl 'http://localhost:8787/__scheduled?cron=0+8+*+*+1'
# Check your inbox at sugamagr@gmail.com within 30 seconds
# (may land in Gmail spam on first delivery - mark "Not spam" once)
```

```bash
# 2. Confirm crons are registered on Cloudflare's side. Get an API
#    token from https://dash.cloudflare.com/profile/api-tokens then:
curl -sS -H "Authorization: Bearer <api-token>" \
  "https://api.cloudflare.com/client/v4/accounts/<account-id>/workers/scripts/rd-scanner-supabase-keepalive/schedules" \
  | python3 -m json.tool
# Should return both crons with created_on timestamps.
```

Expected keep-alive success log:

```json
{"trigger":"supabase-keepalive","succeeded":true,"attempts":1,
 "finalStatus":200,"totalLatencyMs":230,...}
```

Expected weekly-email success logs (two lines per fire):

```json
{"trigger":"weekly-email","emailStatus":200}
{"trigger":"weekly-email","allOk":true,"components":[...]}
```

## Monitoring

```bash
npm run tail
```

Or Cloudflare dashboard: Workers & Pages → `rd-scanner-supabase-keepalive`
→ Observability → Logs. Filter by `error` level to see only failures.

Cron history: same Worker → Triggers → Cron Triggers → Recent
invocations. Shows execution timestamps and outcome.

## Failure modes

**Supabase keep-alive**:

- **Single transient 5xx or timeout**: retries after 2s. If retry
  succeeds, warning-level log, project stays alive.
- **Two transient failures**: error log. Next cron in 3 days will try
  again. Supabase pauses at 7 days so we have 4 days of slack.
- **Auth failure (401/403)**: means `SUPABASE_ANON_KEY` drifted
  (rotated or project recreated). Fix:
  `npx wrangler secret put SUPABASE_ANON_KEY` with new value from
  Supabase dashboard → Settings → API. NO retry because retrying a
  bad key just doubles log noise.
- **Cloudflare outage**: extremely rare; fallback is manual dashboard
  resume of Supabase (60 seconds).

**Weekly email**:

- **Any component reports FAIL**: email STILL sent with FAIL flag in
  subject line. Silence is the danger; the whole point is to get a
  signal in the inbox every Monday.
- **Resend API 4xx/5xx**: logged, no retry (next Monday is 7 days
  away). If you notice a missed email, check CF Worker logs.
- **RESEND_API_KEY rotated or disabled**: email fails, health-check
  results still land in CF Worker logs. Fix: get new key from Resend
  dashboard, run `npx wrangler secret put RESEND_API_KEY`.
- **First email lands in Gmail spam**: mark "Not spam" once. Gmail
  learns and future messages arrive in the inbox. Verified domain
  sender doesn't have this issue (upgrade path: verify domain on
  Resend + update `RESEND_FROM` in `wrangler.toml`).

## Rotating secrets

Either key:

```bash
cd supabase-keepalive
npx wrangler secret put SUPABASE_ANON_KEY
# or
npx wrangler secret put RESEND_API_KEY
# Paste new value when prompted.
```

No redeploy needed. Secrets take effect immediately for the next
invocation.

## Cost

Cloudflare Workers free tier:
- 100,000 requests/day
- 3 million CPU-ms/day
- Free for scheduled/cron triggers

Our usage:
- ~10 keep-alive invocations/month (3-day cadence)
- ~4 weekly-email invocations/month (Monday cadence)
- ~50 CPU-ms per invocation

Total: 14 invocations/month vs 100,000/day quota = 0.0005% utilization.
Expected to stay at $0/month indefinitely.

Resend free tier:
- 100 emails/day
- 3000 emails/month

Our usage: 4 emails/month. 0.13% utilization. Also $0/month.

## Dispatch mechanism (implementation note)

The Worker has ONE `scheduled` handler; it matches `event.cron` against
the two expected strings to dispatch. This is deliberate — a future
third cron expression won't silently take one of the existing branches.
If you add a cron entry to `wrangler.toml`, you MUST also add the
matching string to the dispatch switch in `src/index.ts`, or the new
cron will hit the "unknown-cron" error branch and log a console.error
every time it fires.
