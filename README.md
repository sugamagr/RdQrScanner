# RD Book QR Scanner

A small system for a shop that collects Recurring Deposit (RD) payments.
Operators scan the QR code on each customer's RD book with an Android
phone, group the scans into LOTs inside a session, and the owner sees
everything in a web portal — no manual file sharing.

![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)
![React](https://img.shields.io/badge/React-20232A?style=for-the-badge&logo=react&logoColor=61DAFB)
![Supabase](https://img.shields.io/badge/Supabase-3ECF8E?style=for-the-badge&logo=supabase&logoColor=white)

## What is in this repository

| Part | Folder | What it is | How it ships |
|---|---|---|---|
| Phone app | `app/` | Android app the operators scan with | Signed APK on GitHub Releases, with an in-app update check |
| Owner portal | `portal/` | Web app for browsing, searching, editing and exporting | Cloudflare Pages, deployed by hand |
| Ops Worker | `supabase-keepalive/` | Hourly database heartbeat, automatic restore if Supabase pauses the project, weekly health email | Cloudflare Workers, deployed by hand |
| Cloud schema | `cloud/` | Postgres tables, security policies and migrations | SQL pasted into Supabase Studio |
| Docs | `docs/`, `RELEASE.md` | Design spec, migration playbook, release recipe | — |

Everything runs on free tiers (Supabase, Cloudflare, GitHub) and is
sized for 2–5 phones and one portal user.

```
 Operator phones (Android)            Supabase                 Owner browser
 ─ Room database, offline-first ───►  ─ Postgres        ◄───  Portal (React)
 ─ background sync (WorkManager) ◄──  ─ Auth                   on Cloudflare Pages
                                      ─ Realtime
                                          ▲
                                          │ heartbeat every hour
                                   Ops Worker (Cloudflare cron)
```

## How it works

- **Offline-first.** The phone's local database is what the app shows.
  Scanning never waits on the network; sync runs in the background and
  catches up when a connection returns.
- **Sessions and LOTs.** A session is one sitting of scanning. Inside it,
  scans are grouped into LOTs. A session that is still in progress stays
  on the phone that started it; once finished it syncs to the cloud and
  appears on every other phone and in the portal.
- **Accounts.** Each RD number has a profile: holder name, monthly
  amount, and the month it is paid through. Profiles are created on the
  phone or bulk-imported in the portal.
- **One shared sign-in.** All phones and the portal use the same owner
  account. Each phone records an operator name so the owner can see who
  scanned what.
- **Conflicts.** If the same record is edited in two places, the most
  recent edit wins. Deletes are kept as markers so a deleted session
  cannot reappear from another phone.
- **No server code.** The rules live in the phone app and the portal;
  the database enforces only who may read and write which rows.

## Phone app features

### Scanning
- Real-time QR scanning (CameraX + ML Kit) with haptic and audio feedback
- RD numbers must be 9–15 digits; duplicates within a LOT or session are caught
- Manual entry for accounts that have no QR
- Undo last scan, resume an interrupted session
- LOT review before saving, including months paid per account and a
  ₹20,000 per-LOT limit

### Accounts
- Add accounts in a spreadsheet-style screen (name, RD number, monthly amount)
- Browse, search, sort and filter; edit, mark inactive, or delete
- Per-account payment history
- Print QR codes: A4 PDF, 20 per page (5 × 4), each about 3.7 cm so it
  fits the passbook stamp cell, captioned with name, RD number and amount

### Session history
- Search and date filters (All / Today / This Week / This Month)
- Swipe to delete, long-press to multi-select and bulk-delete
- Export a session as XLSX or TXT, copy a LOT to the clipboard, or share
  a LOT image to WhatsApp and similar apps

### Sync and updates
- Sync status indicator, sync history, and a banner for changes made
  elsewhere since the app was last opened
- Notifications when the owner edits a session from the portal
- Update check on launch against GitHub Releases: an optional banner by
  default, or a blocking screen for releases marked mandatory

### Language
- English and Hindi, including the in-app help

## Portal features

- **Dashboard** — KPIs, money and defaulter charts over a selectable date
  range, export as PDF
- **Sessions** — every finished session from every phone, with LOT and
  RD breakdown, XLSX export, and editing of months paid
- **Search** — find an account by name or RD number across all sessions
- **Accounts** — edit profiles, mark inactive or delete, import from CSV
  or from a post office Deposit Accounts PDF report
- **Activity** — feed of what changed and when
- **Devices** — which phones are active

More detail in [`portal/README.md`](portal/README.md).

## Tech stack

**Phone:** Kotlin, Jetpack Compose + Material 3, CameraX, ML Kit Barcode
Scanning, ZXing (QR generation), Room, WorkManager, Coroutines/Flow,
supabase-kt (Postgrest, Auth, Realtime) over Ktor.

**Portal:** Vite, React 18, TypeScript, Tailwind, TanStack Query,
supabase-js, Recharts, @react-pdf/renderer.

**Cloud:** Supabase (Postgres, Auth, Realtime, row-level security),
Cloudflare Pages and Workers, Resend for the health email.

## Requirements

- Android 11 (API level 30) or higher, with a camera
- A Supabase project with `cloud/schema.sql` applied
- Node 20+ for the portal and the Worker

## Building

### Phone app

1. Clone the repository and open it in Android Studio.
2. Add the Supabase values to `local.properties` at the repo root:

   ```properties
   SUPABASE_URL=https://<project>.supabase.co
   SUPABASE_ANON_KEY=<anon key>
   ```

3. Build and run:

   ```bash
   git clone https://github.com/sugamagr/RdQrScanner.git
   cd RdQrScanner
   ./gradlew :app:assembleDebug
   ```

Release builds need the signing keystore; see [`RELEASE.md`](RELEASE.md).

### Portal

```bash
cd portal
cp .env.example .env.local   # fill in the same Supabase URL and anon key
npm install
npm run dev
```

### Ops Worker

See [`supabase-keepalive/README.md`](supabase-keepalive/README.md).

## Project structure

```
app/src/main/java/com/qrscanner/app/
├── data/            Room entities, DAOs, migrations (AppDatabase.kt)
│   └── sync/        SyncRepository — push, pull and merge logic
├── cloud/           Supabase client, DTOs, local↔cloud mappers
├── work/            WorkManager jobs (push, pull, event pruning)
├── notifications/   Sync notifications
├── update/          GitHub Releases update check
├── navigation/      Navigation graph
├── ui/
│   ├── screens/     Home, scanner, LOT review, history, accounts, help
│   ├── components/  Dialogs, sheets, top bar, sync indicators
│   ├── auth/        Sign-in and first-run setup
│   ├── settings/    Settings
│   ├── update/      Update banner and blocking update screen
│   └── theme/       Colors, type, theme
└── util/            XLSX/TXT export, QR PDF, LOT image, month maths

portal/src/          pages/, components/, lib/ (queries, parsers, exports)
supabase-keepalive/  src/index.ts (heartbeat, restore, weekly email)
cloud/               schema.sql, migrations/
docs/                CLOUD_SYNC_SPEC.md, CLOUD_MIGRATION_PLAYBOOK.md
```

## Documentation

| Document | Read it when |
|---|---|
| [`docs/CLOUD_SYNC_SPEC.md`](docs/CLOUD_SYNC_SPEC.md) | You need to know how sync works or why a design decision was made |
| [`docs/CLOUD_MIGRATION_PLAYBOOK.md`](docs/CLOUD_MIGRATION_PLAYBOOK.md) | You are about to change the cloud schema |
| [`RELEASE.md`](RELEASE.md) | You are cutting a phone release |
| [`portal/README.md`](portal/README.md) | You are working on or deploying the portal |
| [`supabase-keepalive/README.md`](supabase-keepalive/README.md) | You are working on the cron Worker |
| [`docs/OVERNIGHT_HANDOFF.md`](docs/OVERNIGHT_HANDOFF.md) | Historical record of the June 2026 QC rounds |

## Development notes

- Work happens on the `feat/cloud-sync` branch and releases are cut from it.
- There is no CI. Builds, portal deploys, release tags and cloud SQL are
  all run by hand.
- Some rules exist in both Kotlin and TypeScript (for example
  `MonthYear.kt` and `monthYear.ts`, `XlsxExporter.kt` and `xlsx.ts`).
  Change both sides together.
- Secrets stay out of git: `local.properties`, `keystore.jks`,
  `keystore.properties*`, `portal/.env.local` and the Worker's
  `.dev.vars` are all ignored.

## License

**© 2026 Sugam Agrawal. All Rights Reserved.**

This project is proprietary software. You may **not** copy, modify, distribute, or use this code for any purpose without explicit written permission from the author.

This repository is public for portfolio/showcase purposes only.

## Developer

**Sugam Agrawal**
