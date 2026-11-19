# RD Scanner — Portal

Web companion to the RD Book QR Scanner Android app. Lets the shop
owner sign in from any browser, browse every finalized scanning
session across all phones, see a dashboard of collections and
defaulters, search accounts, manage account profiles (including CSV and
PDF bulk import), export XLSX and PDF, edit defaulter months, and see
which phones are currently active.

Stack: Vite 5 + React 18 + TypeScript (strict) + Tailwind v3 +
TanStack Query v5 + supabase-js v2 + react-router-dom v6 + Recharts +
@react-pdf/renderer.
Hosted on Cloudflare Pages free tier. Reads the same Supabase project
the phones write to.

## Quick start (local dev)

```bash
cd portal
cp .env.example .env.local
# fill in VITE_SUPABASE_URL + VITE_SUPABASE_ANON_KEY from
# ../local.properties (same values as the phones)
npm install
npm run dev
```

Open http://localhost:5173. Sign in with the owner account created in
Supabase Studio → Authentication → Users.

## Deploy to Cloudflare Pages

One-time setup:
1. `npm install` (installs wrangler as a dev dep).
2. `wrangler login` interactively, OR export
   `CLOUDFLARE_API_TOKEN` + `CLOUDFLARE_ACCOUNT_ID` in your shell.
3. In the Cloudflare Pages dashboard for the new project, add the
   same `VITE_SUPABASE_URL` and `VITE_SUPABASE_ANON_KEY` env vars
   under Settings → Environment Variables.

Subsequent deploys:

```bash
npm run build
npm run deploy
```

`npm run deploy` runs `wrangler pages deploy dist --project-name=rd-scanner-portal`.

## Architecture

```
src/
├── App.tsx                    auth-aware router (signin vs shell)
├── main.tsx                   QueryClient + AuthProvider boot
├── lib/
│   ├── supabase.ts            singleton supabase client (anon key only)
│   ├── auth.tsx, useAuth.ts   AuthProvider + useAuth + expiry handling
│   ├── queries.ts             PostgREST queries (sessions, lots,
│   │                          rd_numbers, accounts, devices, search)
│   ├── dashboardQueries.ts    KPI + chart aggregation for the dashboard
│   ├── useRealtimeSync.ts     Realtime subscription → query invalidation
│   ├── csvParser.ts           accounts CSV validation
│   ├── pdfAccountsParser.ts   reads the post office Deposit Accounts
│   │                          PDF report into account rows
│   ├── pdfExport.ts           dashboard PDF report (@react-pdf/renderer)
│   ├── monthYear.ts           TS mirror of MonthYear.kt
│   ├── xlsx.ts                hand-rolled OOXML matching XlsxExporter.kt
│   ├── format.ts              i18n date + number formatters
│   └── useDocumentTitle.ts    per-page browser tab title
├── components/
│   ├── AppShell.tsx           sticky header + nav + signout
│   ├── PageHeader.tsx         page title + subtitle + action slot
│   ├── Loader.tsx             full-page loader + skeletons
│   ├── ErrorBoundary.tsx      render-error fallback
│   ├── EditDefaulterDialog.tsx  bottom-sheet defaulter editor
│   ├── ImportCsvDialog.tsx    CSV / PDF bulk import for accounts
│   ├── AccountEditDialog.tsx  name + amount + active toggle
│   ├── DeleteOrInactivateDialog.tsx  two-path delete with verbatim spec copy
│   ├── ExportPdfDialog.tsx    dashboard PDF options (lazy-loaded)
│   └── useBackdropClose.ts    shared click-outside-to-close behaviour
├── pages/
│   ├── SignIn.tsx             /signin    email + password + expiry hint
│   ├── Dashboard.tsx          /          KPIs, charts, date ranges, PDF export
│   ├── Sessions.tsx           /sessions  table + search, infinite scroll
│   ├── SessionDetail.tsx      /sessions/:sessionId  LOT/RD breakdown + XLSX + edit
│   ├── Search.tsx             /search    account search by name or RD number
│   ├── Accounts.tsx           /accounts  profiles, edit, import, delete
│   ├── Activity.tsx           /activity  feed of changes
│   └── Devices.tsx            /devices   phone cards with active/idle/dormant
└── types/db.ts                hand-written types matching cloud/schema.sql
```

`scripts/smoke-parse-pdf.mjs` runs the PDF accounts parser against a
local file from Node, for checking parser changes without the browser.

## Security

The anon key shipped in the client bundle is intentional and safe
because every table has Row-Level Security enforcing
`owner_id = auth.uid()` (see `cloud/schema.sql`, §13 of the spec).
A malicious user with the anon key cannot read or write data they
don't own.

`portal/.env.local` is gitignored. `_headers` sets
`X-Frame-Options: DENY` + `X-Content-Type-Options: nosniff` +
`Referrer-Policy: strict-origin-when-cross-origin` on every response.

## Two-way sync

Defaulter month edits made in the portal flow back to the phones via
Supabase Realtime + the 5-min foreground poll backstop. The phone's
`SyncRepository.mergeRdNumbers` performs last-writer-wins by
`updated_at` (server-side trigger guarantees the timestamp).
Edits arrive on phones as a Channel C tray notification ("Owner edited
Session #N") and a banner entry on Home.

## Brand cohesion

`tailwind.config.ts` mirrors `app/src/main/java/com/qrscanner/app/ui/theme/Color.kt`
1:1 (PrimaryOrange, AccentMint, AccentCoral, WarningAmber, ErrorRed).
Type scale uses Inter. Radius scale: pill (9999), 2xl (16), xl (12),
lg (8).

## License

© 2026 Sugam Agrawal. All Rights Reserved. Same proprietary terms as
the Android app — see repository root README.
