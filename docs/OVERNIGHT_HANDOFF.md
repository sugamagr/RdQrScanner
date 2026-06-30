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
ad66b4e  fix(qc-r6): HTTP timeouts, device dedup, deep-link recovery, URL search, a11y, SEO
72b5964  fix(qc-r7): scan a11y blocker, Back/Undo/Save/End localization, DeviceRow deleted_at, first-paint shell, edit-collision banner
e8f2fc1  feat(cloud): v12 text-length CHECK + redundant index cleanup (optional, not gating R7)
```

(All earlier commits from the cloud-sync / dashboard / activity / device
diagnostics waves are intact on the branch and remain the truth.)

### Live deploy

- **Portal:** https://rd-scanner-portal.pages.dev
  Latest deploy hash at handoff time: `2852d67a` (Cloudflare Pages,
  ships the R7 first-paint shell + AccountEditDialog stale banner
  + DeviceRow deleted_at type).
  Earlier deploys this wave: `debae220`, `dc22144a`, `bd1bef5c`,
  `43af8938`, `19d27bd1`, `b566c4b1`, `6001eed3`, `be4a8666`.
- **Phone APK:** Built at `app/build/outputs/apk/debug/app-debug.apk`.
  Wireless ADB was disconnected after a long gradle build, so the install
  needs to be done by you when you wake up — see the action list below.

### Cloud schema

- No new SQL migrations are paste-required for R7. The v11 device
  diagnostics SQL you applied yesterday is still the latest live
  schema and nothing R7-shipped depends on a newer one.
- `cloud/migrations/v12_text_length_index_hardening.sql` was drafted
  in commit `e8f2fc1` and pushed, but is **OPTIONAL**. It banks two
  R4/R6 deferred hardening items (text-length CHECKs + redundant
  single-column owner_id index cleanup) so the paste-ready SQL
  exists when you want it. Apply at your discretion; the live cloud
  works correctly without it.

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

### Round 6 (status: shipped — commit `ad66b4e`)

Headline fixes from R6 oracles (all 5 returned, triaged, fix non-FPs
applied, FPs documented):

- **SupabaseCloudClient HTTP timeouts** (bg_cadc45d5 F1): added Ktor
  HttpTimeout install via `httpConfig { install(HttpTimeout) { ... } }`
  with 30s request / 15s connect / 30s socket. Without these, weak
  cellular signal mid-push could hang the WorkManager job for the
  2-min OS TCP default. Requires `@OptIn(SupabaseInternal::class)`
  because supabase-kt 3.x marks the httpConfig block internal.
- **NetworkCallback reconnect catch-up** (bg_cadc45d5 F2): MainActivity
  registers a `ConnectivityManager.registerDefaultNetworkCallback` in
  `onCreate` (outside lifecycleScope so it survives brief
  backgrounding) that fires `enqueuePull()` the instant the OS hands
  us a usable default network. Unregistered cleanly in `onDestroy`.
  Without this, airplane-mode toggle catch-up waited for the 5-min
  backstop poll or realtime self-reconnect (both lag the network event).
- **Device dedup on re-install** (bg_0fe42fcd R6-01): CloudClient gains
  `findExistingDevice(ownerId, deviceModel, deviceName): DeviceDto?`.
  AuthAwareRoot FirstRunSetup looks up an existing devices row by
  (owner_id, device_model, device_name) BEFORE generating a fresh
  UUID. Respects `deleted_at IS NULL` so operator-tombstoned phantoms
  are NOT silently resurrected. Six sign-ins from the same phone now
  produce one row, not six.
- **SignInScreen errorMessage liveRegion** (bg_0fe42fcd R6-16):
  `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` on the
  error Text so TalkBack announces auth failures (WCAG 4.1.3).
- **Pill ERROR tap enriches Toast** (bg_cadc45d5 F6): SyncPillState.ERROR
  taps now show the truncated lastErrorMessage (≤120 chars) so
  operators can distinguish weak-signal vs auth-expired vs
  RLS-rejection failure modes.
- **Accounts useDeferredValue** (bg_0d0dabca F2): per-keystroke renders
  on the O(n log n) filter+sort pipeline now run at low priority via
  `useDeferredValue(search)`; input stays controlled by `useState`.
- **dashboardQueries safety cap** (bg_0d0dabca F4): `.limit(50000)` on
  rd_numbers reads protects the browser tab from corrupt-clock-skew
  pathological queries.
- **Search URL persistence** (bg_ee635610): `useSearchParams` `?q=`
  with `replace:true` so per-keystroke history pollution is avoided;
  back-nav from a session-detail restores the filtered view.
- **Deep-link auth recovery** (bg_ee635610): `DeepLinkSignInRedirect`
  catch-all preserves `pathname + search + hash` via Navigate
  `state.from` so unauthenticated deep links resume at the requested
  page after sign-in (SignIn.tsx already read `location.state.from`).
- **index.html meta + favicons** (bg_ee635610): added meta description,
  `robots noindex, nofollow`, `apple-touch-icon`, alternate-icon link
  tags; generated favicon-16/32 + favicon.ico + apple-touch-icon
  (180×180) from existing favicon.svg via sips.
- **robots.txt Disallow:/** (bg_ee635610): private portal hardening.
- **Per-page document.title** (bg_ee635610): new
  `portal/src/lib/useDocumentTitle.ts` hook wired to all 7 pages so
  tab title + history + screen-reader landmark all reflect the
  current page.
- **v11 migration begin/commit wrapper** (bg_c7a90c24 F19): future
  re-paste safety + crash-mid-migration partial-apply protection.
  Idempotent so already-applied state needs no re-paste.

Documented FPs / scale-FPs (intentionally NOT fixed):

- Accounts virtualization at 10K rows (200 accounts/month scale-FP)
- Accounts pagination (scale-FP)
- SessionDetail unbounded chip rendering (scale-FP)
- Search ilike trigram index (already documented as Phase 5 hardening)
- iOS Safari Blob download quirk (operator on desktop primarily)
- 5-min poll backoff during sustained offline (scale-FP)
- Captive portal / DNS classification (low marginal value)
- Redundant single-column owner_id indexes (v12 micro-opt)
- Text-length CHECK constraints (v12 hardening)
- Sign-out-everywhere / password recovery (design gaps, documented)

### Round 7 (status: shipped — commit pending push at handoff)

Final cleanup pass. 5 oracles in parallel, all orthogonal to the prior
6 rounds (zero topology overlap). 4 of 5 returned cleanly; the 5th
(regression sweep) tripped the 200K-token prompt ceiling on first
launch and was relaunched lean — both runs accounted for below.

Headline fixes (all 4 non-FP findings + 1 BLOCKER, fix-then-verify):

- **A11y BLOCKER fixed: ScanFeedbackCard had no live region**
  (`bg_63210027` #17). The success/duplicate/invalid card that pops
  on every scan was visually-only — a blind operator hears nothing
  when a scan completes. RDScannerScreen now wires
  `Modifier.semantics(mergeDescendants = true) { liveRegion =
  LiveRegionMode.Assertive; contentDescription = "<title>,
  <spoken-subtitle>" }` on the Card. The spoken subtitle joins the
  12-digit RD number with spaces (`"1 2 3 4 ..."`) so TalkBack
  reads it digit-by-digit instead of as a 12-billion-something
  integer. Assertive (not Polite) because operators need immediate
  feedback to decide whether to move on or rescan.

- **A11y: 6 hardcoded English `contentDescription = "Back"`
  localized** (`bg_63210027` #1, expanded from oracle's 4 sites to
  the actual 6 found in the tree). AppInfoScreen, RDScannerScreen,
  AddAccountsScreen, SessionDetailScreen, HowItWorksScreen,
  AccountsScreen all now use `stringResource(R.string.content_desc_back)`
  (existing key, already had Hindi value `वापस`). Hi-locale users
  now hear the localized name in TalkBack instead of English "Back".

- **A11y: 3 hardcoded English RDScanner action button labels
  localized** (`bg_63210027` #2). The bottom-bar `weight(1f) × 3`
  row's `Undo` / `Save` / `End` button text now uses
  `stringResource(R.string.scanner_action_{undo,save,end})`. New
  Hindi values `अनडू` / `सेव` / `बंद` (transliteration over the
  Sanskrit-formal `पूर्ववत्` because mobile-UI familiarity matters
  more, and reusing `वापस` for Undo would collide with the
  back-button TalkBack reading).

- **Wire contract: DeviceRow TS type missing `deleted_at`**
  (`bg_1608a30c` F1). Portal's `RdAccountRow / ScanLotRow /
  ScanSessionRow / RdNumberRow` all already carry `deleted_at`;
  DeviceRow was the lone omission (cloud added it in the v9
  soft-delete migration). No current portal code path reads it, but
  the type is the canonical wire contract — future audit / join
  consumers stay sound. Added with a paragraph documenting why it
  stays on the type even with no current consumer.

- **Cold-cache first-paint white flash + unstyled-spinner**
  (`bg_c5ffb9ee` F1+F3). On a cold cache the portal had a
  200-400ms white flash before the Tailwind CSS in the JS bundle
  applied. `portal/index.html` now ships an inline `<style>` block
  with the design-system tokens hand-mirrored from
  `tailwind.config.ts` (surface.alt `#F9FAFB`, ink.primary
  `#111827`, brand `#FF9F43`) plus a minimal CSS-only spinner shell
  inside `#root` (auto-replaced when React mounts). The spinner
  honours `prefers-reduced-motion`. Documented the token mirror so
  a future palette change doesn't silently drift the first-paint
  shell out of sync with the post-mount tree.

- **Concurrency: AccountEditDialog stale-data silent overwrite**
  (`bg_50bb21c1` F4). The dialog held form state in `useState`
  initialized from the `account` prop at mount. If realtime sync
  (`useRealtimeSync.ts:95`) invalidated `['accounts']` while the
  operator was typing — because a phone pushed an edit to the same
  account — the typed values would silently overwrite the phone's
  edit on save. Now the dialog snapshots `account.updated_at` on
  mount (in a ref to survive re-renders), subscribes to the
  `['accounts']` query cache, and surfaces a `warn`-toned banner if
  a fresher row for the same `rd_number` lands. Two affordances:
  **Reload latest** (resets form state to cloud truth, advances
  baseline) and **Keep my edits** (last-write-wins per documented
  R5 conflict policy; advances baseline so the banner doesn't
  re-prompt on the same revision). The banner also lists which
  fields drifted (`"A phone changed the name, monthly amount while
  you were editing."`).

Deferred (handoff-tracked, not silently dropped):

- bg_63210027 #4 (Toast-instead-of-LiveRegion for critical
  feedback), #6 (FilterChip selected-state announcement), #5
  (Hindi/Latin numeral reading-order on mixed-script labels), #14
  (MonthBar selectable + range announcement) — polish-tier. None
  block the core scanning workflow now that the
  ScanFeedbackCard BLOCKER is fixed.
- bg_c5ffb9ee F7 (Recharts modulepreload) — manifest-extraction
  overhead exceeds the perceived gain at 1-portal-owner scale.
- bg_50bb21c1 F1 (two-phone same-session LOT collision) — cloud's
  `UNIQUE(session_id, lot_number)` rejects with 409, phone marks
  `SYNC_ERROR`, the R5 `notifySyncAbandoned` notification path
  fires after the abandon threshold. Defended end-to-end already;
  no further fix needed.
- The remaining ~15 oracle findings across the 4 deep runs were
  empirical false positives (verified against actual code) and
  correctly-by-design decisions (e.g. Dashboard intentionally
  fetched post-auth for security, defaultCount mapper placeholder
  intentional, font system stack is intentionally instant).

Regression sweep (`bg_a859d85c` → relaunched as `bg_275e42c3`
lean): **19/19 R1-R6 fixes VERIFIED in current HEAD, no
regressions**. Items covered: GradientTopBar scrim removal + 7
consumer `statusBarsPadding`, `totalCollectedThisMonth` weighted
formula + `en-IN` locale, 21 `.eq('owner_id', ownerId)` calls
in `queries.ts` + SyncRepository TOCTOU fix (getSessionById
inside `withTransaction`), `require(monthsPaid in 1..36)` in both
`toDto()` and `toEntity()`, 3 portal Retry buttons,
`useBackdropClose` in 5 dialogs, Noto Sans font + Promise.allSettled
+ notifySyncAbandoned, HttpTimeout @OptIn + NetworkCallback
+ `findExistingDevice` ordering in AuthAwareRoot
+ useDocumentTitle in 7 pages + robots.txt + v11 begin/commit.

### v12 cloud schema hardening (status: DRAFTED but NOT APPLIED)

A `cloud/migrations/v12_text_length_index_hardening.sql` file was
pre-drafted in the working tree to bank two deferred R4/R6 oracle
items so the SQL is ready whenever you want to paste it. **It is
NOT required for R7 ship** — no R7 finding gated on it. Apply at
your discretion in Supabase Studio. Contents:

1. **Text-length CHECK constraints** (R4 #15 deferred):
   - `devices.device_name` ≤ 100
   - `devices.last_sync_error` ≤ 4000 (16× headroom over phone's
     240-char `DEVICE_ERROR_MESSAGE_MAX_CHARS` truncation)
   - `scan_sessions.operator_name` ≤ 100
   - `rd_accounts.name` ≤ 200
   - `rd_accounts.last_paid_through` regex
     `^[0-9]{4}-(0[1-9]|1[0-2])$` (locks the YYYY-MM
     lexical=chronological invariant that the phone DAO
     `WHERE lastPaidThrough < :newMonth` and portal
     `dashboardQueries last_paid_through < currentMonth`
     defaulter compute both depend on).

2. **Redundant single-column `owner_id` index cleanup**
   (R6 #15 deferred): drops `scan_lots_owner_idx`,
   `rd_numbers_owner_idx`, `rd_accounts_owner_idx` (each subsumed
   by the v10-added composite `(owner_id, updated_at)` index;
   planner uses leftmost-prefix for equality-only `owner_id`
   lookups). Preserves `devices_owner_idx` (different shape).

Wrapped in `begin; ... commit;` with idempotent `do $$ ... if not
exists ... end $$` guards on the constraints, and a sanity-check
block at the bottom that verifies each constraint exists, no
existing rows violate, dropped indexes are gone, and the subsuming
composite indexes are still present. `cloud/schema.sql` already
reflects the v12 state (constraints inlined, redundant indexes
removed) so fresh installs match upgraded installs.

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

3. **OPTIONAL — apply v12 cloud hardening** (`e8f2fc1`):
   ```bash
   cat /Users/apple/Documents/RdQrScanner/cloud/migrations/v12_text_length_index_hardening.sql
   ```
   Paste the file into Supabase Studio's SQL editor and run. The
   sanity-check block at the bottom verifies each new CHECK exists,
   no existing row violates them, the 3 redundant single-column
   owner_id indexes are gone, and the subsuming composite indexes
   are still present. Idempotent — safe to re-paste. Skip if you
   don't want to touch the live schema; nothing in R7 ship depends
   on v12.

4. **Check QC R2-R7 results**: stitched into commits already.
   Look at `git log --oneline feat/cloud-sync` for everything after
   `4e8a3f8` — those are the QC fix waves (eb789c8 R2, af9ce74 R3,
   e3cacd2 R4, ca91460 R5, ad66b4e R6, 72b5964 R7, e8f2fc1 v12).
   Each commit body includes the oracle finding IDs (bg_*) and a
   short rationale.

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
