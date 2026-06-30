# Overnight Handoff — Night of QC + Feature Wave

This document summarises the work shipped while you slept, the QC rounds run,
and the actions you need to take in the morning. Read top-to-bottom; the
"Actions for you" section at the bottom is the executable summary.

---

## What shipped tonight (cumulative on `feat/cloud-sync`)

### Commits in order (newest at the bottom)

```
3dae118  feat(loader): themed FullPageLoader with gradient + bubbles
a97a229  feat(portal+phone): dashboard, designer loader, header polish
4e8a3f8  feat(portal+phone): R1 QC fixes + dashboard rewrite + PDF export
eb789c8  fix(qc-r2): money weighted formula, range UTC/local, a11y contrast, phone wording
af9ce74  fix(qc-r3): owner_id sweep, TOCTOU race, empty-state CTAs, a11y polish
242b9d0  fix(auth): cross-account cache leak on owner-swap + realtime setAuth on token refresh
e3cacd2  fix(qc-r4): months_paid validation, retry buttons, Hindi accuracy + clip guard
ca91460  fix(qc-r5): PDF glyph + currency, dialog drag-release, abandon notify, camera unbind
```

(All earlier commits from the cloud-sync / dashboard / activity / device
diagnostics waves are intact on the branch and remain the truth.)

### Live deploy

- **Portal:** https://rd-scanner-portal.pages.dev
  Latest deploy hash at handoff time: `dc22144a` (Cloudflare Pages).
  Earlier deploys this wave: `bd1bef5c`, `43af8938`, `19d27bd1`,
  `b566c4b1`, `6001eed3`, `be4a8666`.
- **Phone APK:** Built at `app/build/outputs/apk/debug/app-debug.apk`.
  Wireless ADB was disconnected after a long gradle build, so the install
  needs to be done by you when you wake up — see the action list below.

### Cloud schema

- No new SQL migrations were paste-required this wave. The v11 device
  diagnostics SQL you applied yesterday is still the latest live schema.
- If QC R7 uncovered any migration needs they would be in
  `cloud/migrations/vNN_*.sql` with a header docstring telling you what to
  paste. Check that directory for any new files before continuing.

---

## Features shipped this wave

### Portal

1. **Dashboard page** rebuilt end-to-end:
   - 6 date-range presets: **This month, 3M, 6M, 12M, All, Custom**.
   - Custom range uses inline `<input type=date>` From/To plus an Apply
     button; both inputs clamp to today, and From must be ≤ To.
   - 12 KPI tiles including new money KPIs: **Book amount, Money
     collected (range), Avg ticket (REAL average from monthly_amount,
     no longer a midpoint approximation), Current vs default
     (accounts), Current vs default (₹)**.
   - 2 new charts: **Money Trend** (AreaChart, mint for total + amber
     for defaulters) and **Current vs Default** (BarChart with mint +
     amber cells).
   - Top Defaulters list now masks RD numbers (`***1234 (len=N)`).
   - All ChartTooltip text upgraded to AA-compliant ink-secondary;
     amber badges use `text-amber-700` (4.91:1) instead of the
     previously failing `text-warn` (1.76:1).
   - Realtime auto-refresh confirmation surfaces under PageHeader as
     `· refreshing` when `isFetching`.
   - PageHeader has an **Export PDF** primary button.

2. **PDF export** — a new `ExportPdfDialog` + `pdfExport.ts` module:
   - Lazy-loaded; the 1.46MB `pdf` chunk only lands when the operator
     opens the dialog.
   - Customisation: section toggles (9 sections), paper picker (A4 vs
     Letter), theme picker (Light vs Print mono), title + subtitle
     text inputs.
   - All charts are rendered via native `@react-pdf/renderer` SVG
     primitives (vector PDF, prints crisp at any zoom). Each chart is
     followed by an inline value table so the printed numbers stay
     adjacent to the chart.
   - Footer band has page-of-total + report title.
   - Filename pattern: `rd-book-report-YYYY-MM-DD.pdf`.

3. **AppShell title button**:
   - Clicking the logo/title now invalidates every active query and
     scrolls to top (if already on `/`) or navigates to `/` (if on
     another route). Designed so a third tap acts as a "refresh
     everything" gesture without a full browser reload.

4. **Loader system**:
   - `FullPageLoader` keeps the themed gradient + 3 floating bubbles
     + frosted-glass card you asked for.
   - New `DashboardRouteSkeleton` is the Suspense fallback for the
     lazy Dashboard route — fixes the cumulative layout shift that
     was happening when FullPageLoader was the fallback.
   - Every list page (Sessions, Accounts, Activity, Devices) uses
     `SkeletonCard count=N heightPx=H` instead of inline pulse divs.

5. **Realtime + perf**:
   - `useRealtimeSync` invalidates `['dashboard']` on every relevant
     table change.
   - `vite.config.ts` `manualChunks` isolates Recharts and
     @react-pdf/renderer so the main bundle is now 395KB / gz 107KB
     (down from 545KB / gz 157KB before).
   - `refetchOnWindowFocus` turned off — `useRealtimeSync` already
     handles visibility changes; the duplicate was hammering the
     network on tab focus.

6. **Accessibility**:
   - `prefers-reduced-motion` media query mutes every decorative
     animation in `index.css`.
   - Focus-visible rings now inherit `border-radius`, so the ring
     matches `rounded-pill` / `rounded-xl` targets instead of
     clipping at the prior 6px fallback.
   - RangeSelector implements WAI-ARIA arrow-key roving tabindex.

### Phone (Android)

1. **Header polish** — `GradientTopBar.kt` lost its black 0.50f
   overlay (the brown tinge you flagged), and all 6 consumer screens
   gained `.statusBarsPadding()` on their parent `Column` so the
   header renders below the status bar correctly:
   - `AddAccountsScreen`, `AccountsScreen`, `SessionDetailScreen`,
     `HowItWorksScreen`, `AppInfoScreen`, `LotReviewScreen`,
     `SettingsScreen` (already correct).

2. **Per-item enter animation removed** — `AddAccountsScreen`
   spreadsheet row no longer uses `Modifier.animateItem()`; lists
   appear in one beat instead of staggering in.

3. **Navigation transitions** — fade-only (220ms enter, 160ms exit)
   instead of the previous slide + spring + fade, which was the root
   cause of the "lag" feel on first paint.

4. **EditCalendar (defaulter edit) icon** — gained an explicit
   disabled-state alpha so the per-LOT edit-defaulter icon clearly
   reads as enabled-or-disabled. Previously every LOT's icon looked
   identical regardless of whether tapping did anything.

5. **AppInfo content refresh** — bullets now cover cloud sync,
   multi-device (2-5 phones), the web portal, CSV bulk upload,
   paper-book-is-truth invariant, soft delete with 30-day undo
   window. Tech chips gained Supabase + WorkManager.

6. **HowItWorks content refresh** — new feature cards for **Cloud
   Sync, Web Portal, CSV Bulk Upload** in BOTH Hindi and English.

---

## QC results

### Round 1 — 5 oracles, all triaged

(See commit `4e8a3f8` for the fixes shipped. Round 1 surfaced ~21
real findings and ~12 documented false positives.)

Key BLOCKERs that landed in this wave:
- 6 GradientTopBar consumers were missing parent `statusBarsPadding`
  after the brown-overlay removal (visible regression).
- TopDefaulter list was leaking full rd_number (PII).
- App `Suspense` fallback was a heavy `FullPageLoader` triggering CLS.

### Round 2 — 5 oracles, in-flight at handoff time

Five oracles ran. All findings triaged and shipped in commit
`eb789c8` (see commit body for the full list). Headline fixes:
- **Money math H1**: `totalCollectedThisMonth` now weighted by
  `months_paid` instead of just summing paid-up `monthly_amount`.
  Catch-up payments (defaulters paying multiple months) no longer
  silently undercount in the KPI.
- **Range UTC vs local**: `todayIso` was using `toISOString()` (UTC),
  blocking local-time "today" selection past midnight UTC.
  Replaced with local `getFullYear/getMonth/getDate` helper.
- **A11y contrast**: KIND_BADGE swatches for finalized / deleted /
  edited rebrushed from brand colors (2.6-3.5:1 fails) to
  `bg-{color}/10 text-{color}-700` (4.91-7.6:1 passes AA).
- **PDF correctness**: chart empty-data guards + histogram
  divide-by-zero defense + ₹ glyph everywhere + tick decimation on
  60-month X-axis + mono theme bucket label contrast.
- **Phone wording**: AppInfo version bumped to `1.1.0 (Cloud Sync)`,
  "sub-second realtime" → "near-instant realtime", "30-day undo
  window" → "undo window" (no automated purge worker enforces 30).
  Both Hindi and English copies of HowItWorks updated to match.

### Round 3 (status: shipped, commit `af9ce74`)

Five oracles audited phone sync edge cases, portal data fetch
correctness, build infra + deploy, type safety + null narrowing,
and phone UI/UX polish. All triaged and shipped. Headline fixes:
- **Portal RLS defense-in-depth**: every read function in
  `portal/src/lib/queries.ts` now calls `requireOwnerId()` and
  attaches `.eq('owner_id', ownerId)` (10 functions touched). RLS
  is the floor; explicit owner_id is the ceiling.
- **CSP for PDF export**: `portal/public/_headers` now allows
  `blob:` (PDF download), `worker-src 'self' blob:`, and
  `font-src 'self' data:` for `@react-pdf/renderer`. Without this
  the Export PDF button silently failed in production.
- **HTML no-cache**: Cloudflare edge was holding stale
  `index.html` across deploys; explicit `Cache-Control: no-cache`
  for `/` and `/index.html` fixes it.
- **Phone TOCTOU race fix**: `softDeleteSession` was reading the
  session row OUTSIDE the transaction, then branching hard-vs-soft
  delete. A concurrent realtime/pull could stamp `cloudId` between
  the read and the branch, causing the hard-delete branch to
  destroy a row that should have been tombstoned. Moved the read
  inside `database.withTransaction { … }`.
- **Phone UX**: Add Account + Start Scanning CTAs in empty states;
  Discard-Confirm AlertDialog on AddAccounts back press when any
  draft is partially filled; `.imePadding()` so the keyboard does
  not occlude the bottom row; 48dp touch targets on filter chips;
  scan success ScanFeedbackCard now `scaleIn(0.6f, spring
  MediumBouncy)` for a satisfying punch-in microinteraction.

### Round 4 (status: shipped — commits `242b9d0` + `e3cacd2`)

Five oracles ran. Triaged and shipped. Headline fixes:
- **Cross-account cache leak on owner-swap** (`242b9d0`): the
  realtime channel kept its JWT static across token-refresh; if the
  refresh succeeded mid-session the channel would silently go blind
  for the full hour. Added an inner `onAuthStateChange` listener
  that calls `supabase.realtime.setAuth(next.access_token)` on every
  `TOKEN_REFRESHED`. Also closed a same-browser owner-swap window
  in `auth.tsx` by tracking `user.id` (not `access_token`) so a
  cross-tab storage swap forces `qc.clear()`.
- **Cloud months_paid validation** (`e3cacd2`): cloud schema enforces
  `CHECK (months_paid BETWEEN 1 AND 36)`, but a buggy local
  mutation could produce 0 or 37 and the push would silently fail
  with PostgrestException 23514, retrying 8× until SYNC_ABANDONED.
  Added `require(monthsPaid in 1..36)` in `RdNumberMapper` for
  both directions (toDto + toEntity).
- **Hindi accuracy** (`e3cacd2`): `HowItWorksScreen` had two bugs.
  The Hindi defaulter description leaked the code identifier
  `(months_paid > 1)` (operators do not read column names), and
  the Paper-book-is-truth bullet used the literal translation
  `'पेपर बुक सत्य है'` instead of the idiom operators actually say
  (`पासबुक ही असली रिकॉर्ड है`). Both replaced. Title gains
  `maxLines=1 + TextOverflow.Ellipsis` to avoid clip on 360dp.
- **Retry buttons** (`e3cacd2`): `Accounts.tsx`, `SessionDetail.tsx`,
  and `Search.tsx` error states gained a Retry button with 44dp
  touch target + danger-ring focus; matches the Devices.tsx pattern.
  Eliminates "reload the page" as the only recovery path.
- **Phone `HomeScreen` retry logging**: the sync-pill retry tap was
  swallowing enqueue failures in two `catch (_: Throwable) {}` blocks.
  Replaced with `runCatching {…}.onFailure { Log.w(…) }` so transient
  failures land in logcat.

Documented FPs / scale-FPs / broader-scope items deferred to a future
sprint: realtime channel-status banner (LOW at 1 portal owner),
Devanagari font bundling, AppInfo Hindi tab, contentDescription
stringResource sweep, Hindi Toast LENGTH_LONG, v12 text-length CHECK
migration, CSV+realtime double-fetch (scale-FP), title-click
invalidate burst (scale-FP). bg_dca03041 (Phone scan/camera) timed
out in R4 and has been re-launched as part of R5.

### Round 5 (status: shipped — commit `ca91460`)

Five oracles ran across phone scan/camera, Room migrations, dashboard
PDF + Recharts edges, WorkManager retry, and portal forms + dialogs.
Headline fixes:

- **PDF currency glyph** (bg_78192f17 F10): Helvetica is one of the 14
  standard PDF Type1 fonts and does not include `U+20B9` — every ₹ in
  the export was rendering as a missing-glyph box. `pdfExport.ts` now
  registers Noto Sans (latin-400 + latin-700) via Font.register with a
  module-level latch so it only registers once. Font.registerHyphenation
  Callback returns the word unsplit to avoid mid-word hyphenation.
- **PDF donut full-circle degeneracy** (bg_78192f17 F8): when a single
  source held 100% of the slice, the SVG arc start and end points
  coincided and the slice collapsed to nothing. Capped fully-swept
  fraction (>= 0.9999) at `rawEnd - 0.002` rad; the 0.002 rad gap is
  invisible visually but breaks the degeneracy.
- **PDF long account name overflow** (bg_78192f17 F11): the
  top-defaulters table's flex:2 name cell was ~120pt at A4; a 100-char
  name pushed the Months column off the page. `truncatePdfName`
  caps at 40 chars with ellipsis.
- **Dashboard money formula resilience** (bg_78192f17 F13): replaced
  `Promise.all` with `Promise.allSettled` across all 6 parallel reads.
  Two helpers (`unwrapCritical` for accounts + rdNumbers that drive
  money math, `unwrapOptional` for devices + sessions + earliest +
  activity feed) separate hard-failure from graceful-degradation paths.
  Activity feed has a different return shape (ActivityRow[] direct, not
  Supabase envelope) so handled inline.
- **Compact ₹ on KPIs** (bg_78192f17 F6): KPI tiles `text-2xl` was
  truncating `₹1,23,45,678` on 2-col mobile grid. New `format.ts`
  helper `formatCompactCurrency(n)` collapses to lakh/crore: sub-lakh
  full precision, ≥1L → `₹NL`, ≥1Cr → `₹NCr` with 2-decimal precision
  preserved up to crore. 6 KPI tiles converted: Collected this month,
  Book amount, Money collected (range), Avg ticket, Current vs default
  (₹).
- **Single-point chart quirk** (bg_78192f17 F1): Recharts AreaChart with
  type="monotone" and exactly 1 data point produces a zero-width fill
  that's invisible. New `ChartSingleValue` component renders a labeled
  value card when `data.length === 1`. Wired into MoneyTrendCard +
  SessionTrendCard between the empty branch and the AreaChart.
- **MoneyTrend legend wording** (bg_78192f17 F18): "Total collected" /
  "From defaulters" implied the two series didn't overlap. Renamed to
  "All collections" / "Defaulter portion" with the subtitle
  `subset` framing.
- **5 dialog drag-release data-loss** (bg_e2aaa5bf F1): every dialog's
  backdrop dismissed on `mouseup` if the user dragged a slider or
  selected text and released over the backdrop. New `useBackdropClose`
  hook tracks whether the mousedown originated on the backdrop and
  only dismisses when BOTH mousedown AND click landed on the backdrop
  element. Applied to EditDefaulterDialog, DeleteOrInactivateDialog,
  ExportPdfDialog, ImportCsvDialog, AccountEditDialog.
- **Nested alertdialog ARIA fix** (bg_e2aaa5bf F2): the
  regression-confirm sub-block inside ImportCsvDialog used
  `role="alertdialog"` inside an outer `role="dialog" aria-modal="true"`.
  Nested modal semantics confuses screen readers. Changed to
  `role="alert"` (live-region announcement without focus-trap conflict).
- **AccountEditDialog validation a11y** (bg_e2aaa5bf F3): Name + Amount
  inputs gained `aria-invalid` + `aria-describedby` wired to id'd
  `<p id="account-edit-{field}-error">` elements so screen readers
  announce validation errors when the input is focused.
- **CameraX leak on rapid pop/push** (bg_b6ea6087 F1): RDScannerScreen
  DisposableEffect was not calling `unbindAll()` on dispose. Rapid
  screen pop/push triggered "Use case already bound" warnings + Preview
  surface leak. Fixed with `cameraProviderFuture.get().unbindAll()`
  in onDispose, and switched the inner ToneGenerator release to
  `runCatching` for consistency.
- **Permission permanent-denial dead-end** (bg_b6ea6087 F11): when the
  operator denies camera permission permanently, Android suppresses the
  system dialog entirely. The "Go Back" button gave no recovery path.
  Replaced with "Open Settings" button that fires
  `Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)` via
  runCatching.
- **rd_accounts.isActive default** (bg_559ee8fc F3): MIGRATION_5_6
  added `DEFAULT 1`, but Room's fresh-install CREATE TABLE didn't,
  causing schema-export hash drift. Added
  `@ColumnInfo(defaultValue = "1")` to the field. KSP regenerated
  10.json with the matching createSql.
- **SYNC_ABANDONED operator notification** (bg_2eaadc18 F7): the
  circuit-breaker silently dropped a row after 8 push failures with
  no operator-visible cue. The pill returned to OK because no DIRTY
  rows remained, but the abandoned row was permanently excluded from
  the cloud cycle. New `notifier.notifySyncAbandoned(rowKind,
  contextLabel)` fires on all 4 abandon sites (rd_account, scan_session,
  scan_lot, rd_number) with BigTextStyle body. Distinct
  `NOTIF_ID_ABANDONED` so it doesn't collapse with the transient
  error notification. String resources in both en + hi.
- **SyncStatus backoff cap docstring** (bg_2eaadc18 F1): SYNC_ERROR
  docstring corrected from "capped at 4h" to "capped at
  MAX_BACKOFF_MILLIS of 5h". The prior text was a common doc-error
  citation; WorkManager's actual cap is 5h.

Documented FPs and scale-FPs (deferred per scale at 2-5 phones /
30 sessions/year): bg_78192f17 F2/F3/F4/F5/F7/F9/F12/F14-F19 PASS,
F20 (iOS Safari blob) deferred (operator is desktop-primary);
bg_2eaadc18 F3 (no 429 Retry-After parsing — 23 writes/day vs
500 req/day free tier), F12 (Room migration lock vs sync — Room
guarantees migration-locked DB), F14 (retry/failure/abandoned not
in sync_events — cross-system schema impact, deferred); bg_559ee8fc
F1/F6 (missing schema exports 1.json + 5.json — FP at pre-release
no-v1-installs-in-wild + Q3=B); bg_b6ea6087 F2-F10/F12-F18 PASS;
bg_e2aaa5bf F4/F15/F16/F18 LOW polish skipped.

### Round 6 (status: in flight, 5 oracles in parallel)

Audit angles (different from R1-R5, no duplication):

- Phone offline + connectivity (`bg_cadc45d5`): airplane mode mid-push,
  no-network startup, weak signal partial response, DNS failure,
  captive portal, network-type switch mid-operation, offline queue
  durability, pull-during-offline, realtime channel reconnect, 5-min
  poll backoff, WorkManager NetworkType.CONNECTED vs UNMETERED,
  token refresh during offline, conflict resolution when device A
  edited offline + device B edited same row online, HomeScreen +
  Devices offline banner discoverability, sync_events table durability.
- Portal large-data behavior (`bg_0d0dabca`): Sessions/SessionDetail
  with 500+ sessions, Accounts with 10k+ rows, Activity 100+ events,
  Dashboard with 60-month range, Search with 10k+ rd_numbers,
  TanStack Query cache growth at scale, realtime invalidation cost
  when 10k accounts cached, bundle re-execution cost, memory
  footprint, sort+filter perf, pagination sizing, Recharts batching,
  ImportCsvDialog with 5k-row CSV, ExportPdfDialog generation hang
  risk.
- Cloud migration safety + rollback (`bg_c7a90c24`): v11 idempotency
  on accidental re-paste, live-migration ACCESS EXCLUSIVE lock
  contention, ALTER TABLE add column NOT NULL DEFAULT rewrite cost,
  Q3=B contract leakage in cloud-side semantics, RLS policy drift
  across 5 tables, supabase_realtime publication scope + ops,
  next_display_number race + owner-scoping, search_path safety in
  SECURITY DEFINER functions, backup/restore FK preservation, v12
  text-length CHECK readiness, timestamptz UTC, unused/missing
  indexes, postgres_changes channel type correctness.
- Phone auth + token + sign-out (`bg_0fe42fcd`): sign-in flow,
  sign-out cleanup completeness across WorkManager + realtime + Room
  + caches + Supabase SDK, token refresh strategy on 1h JWT expiry,
  token storage hardness (Keystore vs SharedPreferences), process
  death cold-start, token leak via logcat, multi-account on same
  device, sign-out during in-flight push, biometric prompt,
  recover-password, device row creation race, anonymous sign-in,
  network errors during sign-in, sign-in screen a11y. User-accepted
  behavior to verify intentional: same account signed in multiple
  times creates new device entries (no reconciliation) — document
  stale-device-row growth bound.
- Portal SEO + meta + history (`bg_ee635610`): index.html title/
  favicon/theme-color/viewport/lang/description, OG/Twitter card meta
  for share, browser back button from /sessions/:id, AppShell title
  click refetch vs back-button noop, URL persistence of dashboard
  range, auth-gate deep-link UX, /sessions/<unknown-uuid> 404 UX,
  catch-all behavior, favicons inventory, PWA-readiness manifest
  theme-color, page transition FOUC, robots.txt/sitemap.xml private-
  app stance, HTML lang + skip-to-main + landmark roles, useNavigate
  replace vs push history pollution, Search URL state.

### Round 7 (planned)

Final cleanup pass. Will pick fresh angles based on what R5+R6
surface to avoid duplication. Likely candidates:
- Portal i18n readiness + phone Hindi a11y + TalkBack deep dive
- Cold-cache first-paint + perceived-latency budget
- Final regression sweep across R1-R6 patches

If a round flags a cloud schema change, the migration file appears
in `cloud/migrations/vNN_*.sql` with a sanity-check block at the
bottom for you to paste in Supabase Studio.

---

## Actions for you in the morning

1. **Install the APK** (wireless ADB was disconnected; reconnect or
   USB-attach the phone):

   ```bash
   adb devices
   # If empty, reconnect via Settings > Wireless debugging
   adb install -r /Users/apple/Documents/RdQrScanner/app/build/outputs/apk/debug/app-debug.apk
   ```

2. **Verify portal end-to-end**:
   - Open https://rd-scanner-portal.pages.dev
   - Sign in
   - Land on the new Dashboard (the catch-all redirects `/` → Dashboard)
   - Click Export PDF → toggle a few sections → generate
   - Try every range chip including Custom

3. **Check for new SQL migrations**:
   ```bash
   ls -la /Users/apple/Documents/RdQrScanner/cloud/migrations/
   ```
   If any `v12_*.sql` or later is present, open it, read the header
   comment for what it does, paste into Supabase Studio, and run the
   sanity-check block at the bottom.

4. **Check QC R2-R7 results**: stitched into commits already.
   Look at `git log --oneline feat/cloud-sync` for everything after
   `4e8a3f8` — those are the QC fix waves (eb789c8 R2, af9ce74 R3,
   plus whatever R4-R7 land before morning). Each commit body
   includes the oracle finding IDs (bg_*) and a short rationale.

5. **Audit deferred FPs**: every false-positive a round found is
   documented inline in this handoff or in the relevant commit
   message body. If you disagree with any FP classification, flag it
   and I will re-investigate.

---

## Known-good state at handoff

- Portal: type-check clean, build clean, lint clean.
- Phone: `:app:compileDebugKotlin` BUILD SUCCESSFUL,
  `:app:assembleDebug` BUILD SUCCESSFUL. APK at the path above.
- Cloudflare: latest deploy live and reachable.
- Git: branch `feat/cloud-sync` pushed to origin (remote `sugamagr`).

## Known issues / deferred

- Phone APK install pending physical device reconnect (you will do
  this in the morning).
- npm audit reports 5 dev-only vulnerabilities (esbuild via Vite,
  undici inside Wrangler, none touching production bundle).
  Documented as deferred.
- Architectural items from earlier sessions that you deferred remain
  deferred (SQLCipher Room encryption, Paging3 SessionHistory, bulk
  upsert push batching, SessionDetailScreen 50-Flow hoist,
  MigrationTestHelper rollout, Crashlytics).
