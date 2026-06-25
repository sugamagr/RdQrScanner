# RD Book QR Scanner — Cloud Sync & Portal Spec

> **Status:** APPROVED for build, not yet implemented.
> **Document owner:** Sugam Agrawal.
> **Last updated:** see git log on this file.
> **Audience:** any engineer (including future you) dropping into this work after a break of hours, days, or weeks. Read this end-to-end before touching code.

This document is the **single source of truth** for the cloud-sync + portal feature. Every line of code that follows must reconcile against this spec. If a decision feels wrong while implementing, fix it *here first*, get re-approval, then write the code.

---

## Table of contents

1. [Vision and scope](#1-vision-and-scope)
2. [What is explicitly NOT in scope](#2-what-is-explicitly-not-in-scope)
3. [Architecture at a glance](#3-architecture-at-a-glance)
4. [Decision log](#4-decision-log)
5. [Cloud data model (Postgres on Supabase)](#5-cloud-data-model-postgres-on-supabase)
6. [Local data model (Room on Android, schema v6)](#6-local-data-model-room-on-android-schema-v6)
7. [How local maps to cloud](#7-how-local-maps-to-cloud)
8. [Sync state machine](#8-sync-state-machine)
9. [Auth and identity](#9-auth-and-identity)
10. [Multi-device sync rules](#10-multi-device-sync-rules)
11. [Conflict resolution](#11-conflict-resolution)
12. [API contract](#12-api-contract)
13. [Row-level security (RLS) policies](#13-row-level-security-rls-policies)
14. [Realtime channel design](#14-realtime-channel-design)
15. [Android-side architecture](#15-android-side-architecture)
16. [Portal architecture](#16-portal-architecture)
17. [Account profiles (rd_accounts)](#17-account-profiles-rd_accounts)
18. [Migration plan (v5 → v6)](#18-migration-plan-v5--v6)
19. [Phase-by-phase build plan](#19-phase-by-phase-build-plan)
20. [Acceptance criteria per phase](#20-acceptance-criteria-per-phase)
21. [Runbook (setup from scratch)](#21-runbook-setup-from-scratch)
22. [Failure modes and recovery](#22-failure-modes-and-recovery)
23. [Cost and free-tier limits](#23-cost-and-free-tier-limits)
24. [Open questions / deferred items](#24-open-questions--deferred-items)
25. [Glossary](#25-glossary)

---

## 1. Vision and scope

### The problem today

Operators scan RD books on phones. The only way owner sees the data is via WhatsApp XLSX exports. Workflow is manual, lossy, depends on operator remembering to send, and there's no single place to look up "which session was that account in?"

### What we are building

A cloud-backed, multi-device, offline-first sync system plus a web portal so the owner can see all scanned data in real-time from any browser, without anyone manually sharing files.

### The five concrete user stories this satisfies

1. **As an operator** I scan a book, finish a LOT, and never have to share the data manually — the owner sees it.
2. **As an operator on Phone B** I open the app and see all sessions Phone A has finalized, even if Phone A is offline right now.
3. **As an operator** I scan offline (in a basement, on a flight, wherever) and trust that everything will sync when network returns. No data loss, no manual retry.
4. **As the owner** I open `myrdscanner.example.com`, log in, and browse every session, drill into LOTs, see defaulters with their months, search for a specific RD number, export XLSX, and edit defaulter months if needed.
5. **As the owner** I know which phone (and which operator working that phone at the time) scanned each session.

### The non-negotiable constraints

- **Free forever** on a realistic operating scale (2–5 phones, 1 portal user, up to ~100k RD scans/year).
- **Offline-first** on the phone. Scanning never blocks on network. Sync is invisible to the operator on the happy path.
- **No regressions** to the existing v1.0.0 phone behavior. Existing flows (scan, finish-LOT, defaulter dialog, exports, share image, history) must keep working as they do today.
- **No vendor lock-in deeper than "we'd have to write a new portal."** Our cloud data is in Postgres; worst case we self-host or migrate.

---

## 2. What is explicitly NOT in scope

These come up naturally in conversation. They are deferred to a hypothetical v2 (or never). Saying no now is how we ship v1.

- **Push notifications to operators.** ("Owner edited a defaulter on session #47.") Not built.
- **Real-time collaboration on an active session.** Two phones cannot scan into the same in-progress session. Active sessions are device-private.
- **Operator-level identity / per-operator login.** All operators on all phones share one owner account. We track operator *name* as a string for accountability, not as an auth principal.
- **Role-based access control.** All signed-in clients (phones, portal) have the same permissions on their own data, mediated by RLS but not differentiated by role.
- **Backup / restore of the cloud DB.** Supabase has automatic backups on free tier; we trust them. No custom backup logic.
- **Search across multiple owner accounts.** No multi-tenant federation. One owner = one account = one isolated dataset.
- **Operator chat / comments / annotations on sessions.** Defaulter months is the only editable metadata.
- **Aggregate reporting beyond the v1 portal dashboard.** No PDF reports, no scheduled emails, no charts beyond the in-portal dashboard.
- **iOS app.** Android only.
- **Editing of RD numbers themselves post-scan.** Defaulter months are editable. The scanned number itself is immutable. (Mistakes are corrected by deleting and rescanning.)
- **Conflict resolution UI ("Phone A's edit conflicts with Phone B's, pick one").** Last-writer-wins, silent loser. Documented limitation.
- **Self-hosted deployment instructions.** We use Supabase Cloud. Self-hosting is theoretically possible but not documented here.
- **Two-factor auth.** Out of scope.
- **GDPR / data deletion workflows.** Owner can delete any session from the portal; that's the data deletion story.

---

## 3. Architecture at a glance

```
┌─────────────────────────┐                ┌────────────────────────────┐                ┌─────────────────────┐
│   Operator phone A      │   HTTPS         │     Supabase project       │   HTTPS         │   Owner browser     │
│  (Android, Room v6)     │ ─────────────► │   ─ Postgres (data)        │ ◄───────────── │   Portal (React)    │
│                         │ ◄───────────── │   ─ Auth (email/password)  │ ─────────────► │   on Cloudflare     │
│  - Scanning, dialogs    │   Realtime     │   ─ Realtime (broadcast)   │   Realtime     │   Pages             │
│  - Sync queue           │   over WSS     │   ─ Storage (unused v1)    │   over WSS     │                     │
│  - WorkManager          │                │                            │                │                     │
└─────────────────────────┘                └────────────────────────────┘                └─────────────────────┘
        ▲                                            ▲
        │                                            │
┌─────────────────────────┐                          │
│   Operator phone B      │ ─────────────────────────┘
│  (same architecture)    │
└─────────────────────────┘
```

**Key invariants:**

- The **phone's local Room DB is still the source of truth for the user-visible state on that phone**. Sync is a background process that mirrors local → cloud and merges cloud → local. Nothing in the UI ever waits on network.
- The **cloud Postgres DB is the source of truth across devices**. The portal reads from it directly. When phones sync, they push their local state up and pull other devices' state down.
- **Active in-progress sessions never sync.** They become syncable the instant they're finalized.
- **There is no server-side application logic for v1.** All business rules live in the client (Android + Portal). Supabase serves Postgres + Auth + Realtime; RLS policies enforce authorization. Future Edge Functions could be added if we need server-side validation, but not in v1.

---

## 4. Decision log

Every architectural choice that an outsider would reasonably question. If you find yourself second-guessing one of these mid-build, re-read the rationale.

| # | Decision | Rationale | Alternatives considered |
|---|---|---|---|
| D1 | **Supabase over Firebase** | Relational data model matches our schema 1:1. SQL-shaped queries for the portal. No metered reads. Owner-portable Postgres. | Firebase Firestore was the alternative; lost on data-model fit and aggregate-query support. See §23 for cost comparison. |
| D2 | **One shared owner account, all phones sign in with same credentials** | Simplest. Matches "small shop with 2 trusted operators." Per-operator auth is overkill at this scale. | Per-operator sub-accounts (deferred to v2). Anonymous device-only auth (rejected — recovery story is brittle). |
| D3 | **Operator name captured as a free-text string per phone, not as an auth principal** | Lets us track "Ravi scanned this" without the complexity of per-user auth. Operator-switching is a one-tap action in settings. | Real per-user auth (D2 alternative). |
| D4 | **Two-way sync, last-writer-wins on `updatedAt`** | Owner explicitly needs to edit defaulter months from the portal. Last-writer-wins is acceptable because real-world conflict probability is ≈ 0 for a 2-phone shop. | One-way phone→cloud (rejected — owner needs portal edits). Operational transformation (rejected — massive overkill). Hybrid lock-on-dirty (rejected — adds code for negligible benefit at this scale). |
| D5 | **Realtime + 5-min poll fallback** | Realtime is on free tier and gives ~1s cross-device latency. Poll catches the case where Realtime is disconnected. | Polling only (worse UX). Realtime only (no fallback when WebSocket flaky). |
| D6 | **Active sessions are device-private, finalized sessions are global** | Avoids the entire concurrent-edit-on-active-session problem. Matches reality: only one operator scans into a session at a time. | "Active session co-edited across phones" (rejected — operationally bizarre and a hard sync problem). |
| D7 | **All phones cache all sessions forever** | RD scans are ~50 bytes each. Even 5 years of data is < 50 MB. Bounding the cache adds eviction code for marginal gain. | Last-90-days cache (rejected — extra code, no real benefit). On-demand fetch (rejected — defeats offline-first). |
| D8 | **Tombstones for deletes, never hard-delete from cloud** | Without tombstones, deleted sessions resurrect from other phones' caches. | Hard delete with broadcast (fragile, fails if other phones offline). |
| D9 | **Schema v5 → v6 adds sync metadata to existing tables, no separate sync queue table** | Each row carries its own `cloudId`, `syncStatus`, `updatedAt`. WorkManager scans for `syncStatus != SYNCED` rows. Simpler than a dedicated outbox table. | Outbox/eventlog pattern (overkill for our row counts). |
| D10 | **Cloud schema mirrors local schema, with the same column names where possible** | Mental model parity. The transformation layer (local↔cloud) is dumb-simple. | Renamed cloud schema with mapping layer (extra code for no benefit). |
| D11 | **Cloud `id` is a UUID, generated client-side at row creation** | Lets phones reference rows by their final id BEFORE talking to the server. Means no two-phase "create row → get id → use id" choreography. Idempotent uploads (same UUID = same row, regardless of how many times we push). | Server-generated bigint id (requires roundtrip per row, breaks offline-first). |
| D12 | **Local Room PK stays `id: Long autoincrement`; cloud UUID is a separate column** | Don't change every FK in the local DB. Local stays fast and integer-keyed. Cloud uses UUIDs only for cross-device identity. | Migrate local to UUIDs (massive churn for no benefit; local is single-device-scoped). |
| D13 | **One Supabase project, shared across all owner accounts** (currently just one owner: you) | Cheaper, simpler. Multi-owner is a v2 concern. | One project per owner (operationally heavy). |
| D14 | **Portal hosted on Cloudflare Pages** | True unlimited bandwidth on free, edge-fast, great DX. | Vercel (also fine; CF wins on bandwidth limit). Netlify (build minutes can run out). |
| D15 | **Portal stack: Vite + React + TypeScript + TanStack Query + Supabase JS SDK + Tailwind** | Boring, fast, well-documented, type-safe. TanStack Query handles the cache + realtime invalidation pattern elegantly. | Next.js (overkill, we don't need SSR). Svelte (fine but smaller talent pool if you ever hire). Plain HTML+vanilla JS (cute but loses type-safety guarantees). |
| D16 | **No CI/CD in v1.** Manual deploy of phone APK; manual deploy of portal via `npm run build && wrangler pages deploy` | We're shipping fast. CI/CD can come once the system is stable. | GitHub Actions (deferred). |
| D17 | **All times stored as UTC `timestamptz` in cloud, `Long epoch millis` locally** | UTC eliminates timezone bugs in conflict resolution. Phones display in local zone. | Storing local timezone offsets (rejected — invites bugs). |
| D18 | **`updatedAt` resolution is millisecond, sourced from the writer's clock** | Phones may have skewed clocks. We accept this; the worst case is a wrong-order resolution of an extremely rare conflict. | Server-side `updatedAt` (requires roundtrip; breaks offline edits). NTP enforcement (overkill). |
| D19 | **Composite PK `(owner_id, rd_number)` for `rd_accounts`, no separate UUID** (v8) | The RD number string is already globally unique within an owner's book (regex-enforced). A synthetic UUID adds indirection without identity gain and complicates the "cloudId = rdNumber" mental model. See §17. | Synthetic UUID PK matching D11 pattern (rejected — extra indirection for no benefit; the entity isn't created by multiple writers before sync the way sessions/lots are). |
| D20 | **Portal CSV bulk upload always wins on conflict** (v8) | CSV upload is an authoritative bulk import from the owner's master list. Server-stamps `updated_at` at upsert time, which is by definition newer than any prior phone edit on the same `rd_number`. Phone pulls the new name/amount on next sync. See §17. | Phone edits win (rejected — CSV is the owner's source of truth). Manual merge dialog per row (rejected — overkill for the 2-phone use case). |
| D21 | **`last_paid_through` is monotonic-only on push** (v8) | Defends against out-of-order replay overwriting a more recent payment with a stale one. Enforced client-side at `RdAccountDao.updateLastPaidThroughMonotonic` with `WHERE lastPaidThrough IS NULL OR lastPaidThrough < :newMonth` — only strictly-greater values reach the cloud upsert at all. Cloud-side `SET last_paid_through = GREATEST(...)` trigger was considered but rejected: client-side prevents the write entirely rather than silently discarding it (safer pattern for a single-owner deployment + makes the regression attempt observable via DAO no-op rowsAffected = 0). See §17. | Allow regression (rejected — violates payment history integrity). Cloud-side GREATEST trigger (rejected — silent discard is less debuggable than client-side block). Server-side Edge Function validation (deferred per Q9). |
| D22 | **Portal NEVER edits `last_paid_through`** (v8) | The field is a phone-derived signal only — it's the receipt of payment, which only happens at scan time. Letting the portal edit it would create a "who's authoritative?" ambiguity with no clear winner and zero operator benefit. The edit dialog form has no field for it; the `updateAccount` query payload never includes it. See §17. | Portal can edit (rejected — creates conflict surface with no clear winner). |
| D23 | **Operator-explicit `last_paid_through` regression IS allowed via confirm UX** (v8.1) | User contract: *"paper book is truth"* — when last month's record was wrong, the operator must be able to correct backward. D21's strict-monotonic guard silently dropped past-month corrections, violating that contract. Now: (1) `RdAccountDao.setLastPaidThroughExplicit()` writes any value without the `WHERE < :newMonth` guard; (2) `RdAccountDao.clearLastPaidThrough()` resets to NULL ("never paid"); (3) both the LOT review (RDScannerScreen) and the Accounts edit dialog detect regressions via `LotReviewRow.isRegression` / `PaidTillEdit.SetTo.isRegression` and surface a confirm modal before writing. **D21's monotonic rule now applies only to auto-scan-driven advances via `updateLastPaidThroughMonotonic`; operator-explicit edits bypass it after confirmation.** D22 still holds — portal stays read-only on this field. | Silent regression (rejected — operator must confirm). Block all regression (rejected — violates "paper book is truth"). Cloud-side LWW with portal-edit allowed (rejected — would re-open the D22 conflict surface). |
| D24 | **Per-LOT ₹20,000 total cap enforced client-side only** (v8.2) | Mirrors the portal's per-LOT total limit on the phone so over-limit LOTs are caught before sync would reject them at the portal-edit boundary. Cap rule: `Σ (RdAccount.monthlyAmount × monthsPaid) <= 20,000` (strict `>` boundary — exactly ₹20,000 is allowed) across every row in a LOT, summing the *verified* total only — rows without a profile are counted separately and surfaced to the operator with a "real figure may be higher" hint. Enforced on `LotReviewScreen` with `LotReviewMode.FreshScan` (in-flight Confirm → Cancel or Rescan-this-LOT actions, same LOT number preserved on rescan) and on `LotReviewScreen` with `LotReviewMode.RecordedEdit` for post-finalize edits (close-only popup since the session is already finalized). The two paths were unified into a single screen in commit 8821852, replacing the prior `DefaulterEditDialog`. Client-side only in v1 — a modified APK could in theory bypass by direct cloud upsert. Acceptable for the single-owner trusted-employee threat model per §5; revisit in v2 with a Postgres CHECK constraint or Edge Function if untrusted devices become a concern. See §15.5.12 + §15.5.13 (live total chip) + §15.5.14 (unified editor architecture) for the visual + semantic contracts. | Server-side CHECK constraint (deferred to v2 if untrusted devices become a concern). Supabase Edge Function validation (deferred — same v2 trigger). Cloud-side soft cap with warning-only (rejected — phone catching this pre-sync is the right UX, not after-the-fact rejection). |

---

## 5. Cloud data model (Postgres on Supabase)

All tables live in schema `public`. Naming: snake_case. Every table has `id uuid PRIMARY KEY DEFAULT gen_random_uuid()`, `owner_id uuid REFERENCES auth.users`, `created_at timestamptz`, `updated_at timestamptz`, `deleted_at timestamptz` (NULL = live, non-NULL = tombstone).

### Table: `devices`

A registered phone. One row per phone that has ever signed in.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | uuid | PK, default gen_random_uuid() | |
| `owner_id` | uuid | NOT NULL, REFERENCES auth.users(id) ON DELETE CASCADE | The owner account this phone belongs to. |
| `device_name` | text | NOT NULL | User-set on first launch ("Counter Phone"). |
| `device_model` | text |  | Android model string, auto-detected. |
| `first_seen_at` | timestamptz | NOT NULL, default now() | |
| `last_seen_at` | timestamptz | NOT NULL, default now() | Updated on every sync push. |
| `app_version` | text |  | versionName from gradle, for diagnostics. |
| `created_at` | timestamptz | NOT NULL, default now() | |
| `updated_at` | timestamptz | NOT NULL, default now() | |

Indexes: `(owner_id)`, `(owner_id, last_seen_at DESC)`.

### Table: `scan_sessions`

Mirrors local `scan_sessions` but only for **finalized** sessions (`is_active = false`).

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | uuid | PK, default gen_random_uuid() | Generated by the phone at finalize time. |
| `owner_id` | uuid | NOT NULL, REFERENCES auth.users(id) ON DELETE CASCADE | |
| `device_id` | uuid | NOT NULL, REFERENCES devices(id) ON DELETE RESTRICT | Which phone owned/scanned this. |
| `operator_name` | text |  | Free-text. Captured at finalize. |
| `display_number` | int | NOT NULL | Server-assigned at create-time. See §10. |
| `start_time` | timestamptz | NOT NULL | From local `startTime`. |
| `end_time` | timestamptz | NOT NULL | From local `endTime`. NOT NULL because we only sync finalized. |
| `total_lots` | int | NOT NULL, default 0 | Denormalized for portal list. |
| `total_rd_numbers` | int | NOT NULL, default 0 | Denormalized for portal list. |
| `default_count` | int | NOT NULL, default 0 | Denormalized; SUM across LOTs of `monthsPaid > 1` count. |
| `created_at` | timestamptz | NOT NULL, default now() | |
| `updated_at` | timestamptz | NOT NULL, default now() | |
| `deleted_at` | timestamptz |  | Tombstone marker. |

Indexes: `(owner_id, end_time DESC)`, `(owner_id, deleted_at)`, `(owner_id, display_number)`.

### Table: `scan_lots`

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | uuid | PK, default gen_random_uuid() | |
| `owner_id` | uuid | NOT NULL, REFERENCES auth.users(id) ON DELETE CASCADE | |
| `session_id` | uuid | NOT NULL, REFERENCES scan_sessions(id) ON DELETE CASCADE | |
| `lot_number` | int | NOT NULL | 1-based within the session. |
| `timestamp` | timestamptz | NOT NULL | From local. |
| `created_at` | timestamptz | NOT NULL, default now() | |
| `updated_at` | timestamptz | NOT NULL, default now() | |

Indexes: `(session_id, lot_number)`, `(owner_id)`.

UNIQUE constraint: `(session_id, lot_number)` — no two LOTs in a session share a number.

### Table: `rd_numbers`

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | uuid | PK, default gen_random_uuid() | |
| `owner_id` | uuid | NOT NULL, REFERENCES auth.users(id) ON DELETE CASCADE | |
| `lot_id` | uuid | NOT NULL, REFERENCES scan_lots(id) ON DELETE CASCADE | |
| `number` | text | NOT NULL | The RD account number string. |
| `position` | int | NOT NULL | Order within the LOT. |
| `scanned_at` | timestamptz | NOT NULL | From local. |
| `months_paid` | int | NOT NULL, default 1, CHECK (months_paid BETWEEN 1 AND 36) | |
| `months_list` | text |  | Same encoding as local: comma-separated `YYYY-MM`. NULL when months_paid = 1. |
| `created_at` | timestamptz | NOT NULL, default now() | |
| `updated_at` | timestamptz | NOT NULL, default now() | |

Indexes: `(lot_id, position)`, `(owner_id)`, `(owner_id, number)` for cross-session RD number search.

### Table: `rd_accounts`

Account profiles. See §17 for the full contract; included here for the cloud-data-model index.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `rd_number` | text | PK part (composite with owner_id) | The visible identity. `^\d{9,15}$`. |
| `owner_id` | uuid | PK part, NOT NULL, REFERENCES auth.users(id) ON DELETE CASCADE | |
| `name` | text | NOT NULL | Holder name. |
| `monthly_amount` | int | NOT NULL, CHECK (> 0) | Rupees per month. |
| `last_paid_through` | text |  | `YYYY-MM`, phone-derived, monotonic-only on push. |
| `source` | text | NOT NULL, CHECK IN ('MANUAL', 'CSV') | Origin. CSV locks phone edit affordance. |
| `is_active` | boolean | NOT NULL, default true | Soft state. Auto-reactivates on scan. |
| `account_opened_date` | date |  | Schema-only in v8. |
| `account_closing_date` | date |  | Schema-only in v8. Independent of `is_active`. |
| `last_editor_device_id` | uuid | REFERENCES devices(id) | NULL = portal edit. |
| `created_at` | timestamptz | NOT NULL, default now() | |
| `updated_at` | timestamptz | NOT NULL, default now() | LWW basis. |
| `deleted_at` | timestamptz |  | Tombstone marker. NULL = live. |

Indexes: `(owner_id, rd_number)` (the composite PK), `(owner_id, is_active)`, GIN `pg_trgm` on `name` for portal search.

### Database triggers

A single trigger on each of `devices`, `scan_sessions`, `scan_lots`, `rd_numbers`, `rd_accounts`: `BEFORE UPDATE`, `SET updated_at = now()`. This guarantees `updated_at` is always touched on UPDATE, regardless of whether the client remembered to set it. (Client also sets it for the optimistic local copy.)

```sql
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_devices_updated_at BEFORE UPDATE ON devices
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
-- repeat for scan_sessions, scan_lots, rd_numbers
```

### Display number assignment

Done by a Postgres function `next_display_number(p_owner_id uuid) RETURNS int` that locks via advisory lock and returns `COALESCE(MAX(display_number), 0) + 1` for the owner's sessions. Called inside the INSERT path on the phone (RPC) so the server, not the phone, assigns the number. Eliminates the "two phones both pick #47" race.

```sql
CREATE OR REPLACE FUNCTION next_display_number(p_owner_id uuid) RETURNS int AS $$
DECLARE
  v_next int;
BEGIN
  PERFORM pg_advisory_xact_lock(hashtext(p_owner_id::text));
  SELECT COALESCE(MAX(display_number), 0) + 1
    INTO v_next
    FROM scan_sessions
    WHERE owner_id = p_owner_id AND deleted_at IS NULL;
  RETURN v_next;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;
```

Locally the phone still assigns a `displayNumber` for offline UI, but it's treated as "tentative" until cloud assigns the real one and the phone updates its local copy.

---

## 6. Local data model (Room on Android, schema v6)

We extend the existing v5 schema. **No tables are renamed.** New columns added to support sync state.

### Changes to `RdNumber`

```kotlin
data class RdNumber(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lotId: Long,
    val number: String,
    val position: Int,
    val scannedAt: Long = System.currentTimeMillis(),
    val monthsPaid: Int = MONTHS_DEFAULT,
    val monthsList: String? = null,

    // NEW in v6 — sync metadata
    val cloudId: String? = null,            // UUID assigned at row creation (when LOT is created), used as the cloud PK
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY,
    val updatedAt: Long = System.currentTimeMillis(),
    val syncedAt: Long? = null,             // last successful push timestamp
    val lastSyncError: String? = null       // short error string for diagnostics
)
```

### Changes to `ScanLot` and `ScanSession`

Same 5 sync-metadata columns added to both:

- `cloudId: String?` (UUID, nullable until the LOT/session is finalized; for ScanSession, this is `null` until "End Session" and a remote insert succeeds and we backfill it)
- `syncStatus: SyncStatus`
- `updatedAt: Long`
- `syncedAt: Long?`
- `lastSyncError: String?`

`ScanSession` additionally gets:

- `deviceCloudId: String?` — which device row in the cloud this session was originated from. NULL until first sync.
- `operatorName: String?` — captured at finalize from the current operator-name setting. NULL for sessions finalized before v6.
- `deletedAt: Long?` — tombstone marker on the local side too. Soft delete locally so the row stays around to push the deletion up to cloud.

### Changes to `ScanLot`

- `deletedAt: Long?` — same role as above (CASCADE deletes locally should set this, not hard-delete, until pushed).

### Changes to `RdNumber`

- `deletedAt: Long?` — same.

### New enum: `SyncStatus`

```kotlin
enum class SyncStatus {
    LOCAL_ONLY,      // Never been pushed. Active session state.
    DIRTY,           // Has local changes that need pushing. Includes "I just got modified" and "I'm new and ready to push."
    SYNCING,         // Currently being pushed by a WorkManager job.
    SYNCED,          // In sync with cloud at last check.
    SYNC_ERROR,      // Last push failed; will be retried.
    SYNC_ABANDONED   // Circuit-breaker terminal state — see retryCount + PUSH_ABANDON_THRESHOLD below.
}
```

State transitions:

- New row created: `LOCAL_ONLY` (if part of active session) or `DIRTY` (if part of finalized session, or a defaulter edit on already-synced row).
- Active session finalized: all its rows flip `LOCAL_ONLY → DIRTY`.
- Push starts: `DIRTY → SYNCING`.
- Push succeeds: `SYNCING → SYNCED`, `syncedAt = now()`.
- Push fails: `SYNCING → SYNC_ERROR`, `lastSyncError = msg`, `retryCount += 1`. Will be retried.
- Subsequent local edit of a synced row: `SYNCED → DIRTY`, `retryCount = 0` (fresh circuit-breaker window).
- After `retryCount >= PUSH_ABANDON_THRESHOLD` (default 8): `SYNC_ERROR → SYNC_ABANDONED`. The row stops counting toward the pill's pending count, stops being re-promoted, and stops being retried until a user-initiated reset clears it. This protects against structurally-unpushable rows (cloud schema drift, FK constraints we can't satisfy, etc.) silently burning battery on infinite retry.
- Remote pulled row that doesn't exist locally: created as `SYNCED`.
- Remote pulled row that differs from local synced: see §11 (conflict resolution).

**Sync metadata fields shared by every syncable entity** (ScanSession, ScanLot, RdNumber, RdAccount):

| Field | Purpose |
|---|---|
| `cloudId: String?` | UUID assigned at row creation, the cloud-side PK. Null only on rows created before v6. |
| `syncStatus: SyncStatus` | Per-row lifecycle (see above). |
| `updatedAt: Long` | Epoch millis of most recent local mutation; LWW tiebreaker (§11). |
| `syncedAt: Long?` | Epoch millis of last successful push. |
| `lastSyncError: String?` | Short error string from the last failed push. |
| `deletedAt: Long?` | Tombstone marker. Null while alive. |
| `retryCount: Int` | Consecutive-failure counter for the circuit breaker. Reset to 0 on `SYNCED`, on local edits, and after a `mergeFromCloud` write — each gives the row a fresh circuit-breaker window. |
| `lastEditorDeviceId: String?` | Cloud `devices.id` of whoever last wrote this row. Stamped by phones on push; null for portal writes so the merge attribution code can render "edited by Portal" badges. Added in v9 for RdNumber + RdAccount; ScanSession + ScanLot have it from v6. |

### New entity: `RdAccount` (v8)

Added in schema v8 to support account profiles (see §17 for the full contract). Composite PK on `(rdNumber)` locally — there's no autogen Long PK; the RD number string *is* the identity.

```kotlin
@Entity(
    tableName = "rd_accounts",
    indices = [Index("isActive"), Index("syncStatus")],
)
data class RdAccount(
    @PrimaryKey val rdNumber: String,
    val name: String,
    val monthlyAmount: Int,
    val lastPaidThrough: String? = null,    // "YYYY-MM" — phone-derived only
    val source: AccountSource = AccountSource.MANUAL,
    val isActive: Boolean = true,
    val accountOpenedDate: String? = null,  // ISO date, schema-only in v8
    val accountClosingDate: String? = null, // ISO date, independent of isActive
    val lastEditorDeviceId: String? = null,

    // Sync metadata (same shape as other entities)
    val cloudId: String? = null,            // = rdNumber for this entity
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY,
    val updatedAt: Long = System.currentTimeMillis(),
    val syncedAt: Long? = null,
    val lastSyncError: String? = null,
    val deletedAt: Long? = null,
)

enum class AccountSource { MANUAL, CSV }
```

DAO highlights (full surface in `RdAccountDao.kt`):

- `findByRdNumber(rdNumber)` — filters tombstones; used by the scan path + defaulter auto-suggest.
- `findByRdNumberIncludingDeleted(rdNumber)` — used only by the CSV resurrect path.
- `resurrectTombstone(rdNumber)` — clears `deletedAt = NULL` + flips `syncStatus = DIRTY`; phone-side mirror of the portal's `bulkUpsertAccounts` `deleted_at: null` stamp.

### New table: `device_settings`

A single-row table holding per-phone settings:

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER PRIMARY KEY | Always = 1 (enforced via CHECK on upgraded DBs; not on fresh installs because Room's entity-generated CREATE TABLE doesn't emit CHECK constraints — DAO surface enforces id = 1 in every query so the divergence is invisible at runtime). |
| `deviceCloudId` | TEXT | Our row in the cloud `devices` table. Null until first sync. |
| `deviceName` | TEXT | "Counter Phone" |
| `operatorName` | TEXT | "Ravi" — current operator |
| `ownerId` | TEXT | auth.users.id of the signed-in account |
| `lastPulledAt` | INTEGER | Epoch millis of last successful pull cursor. Reset to 0 on sign-out (via `DeviceSettingsDao.clearOwner`) so a different owner signing in on the same device doesn't inherit the prior cursor and silently miss historical rows. |
| `lastPullErrorAt` | INTEGER | NULL if last pull succeeded |
| `lastPullError` | TEXT | NULL if last pull succeeded |
| `lastBannerSeenAt` | INTEGER | Epoch millis the in-app recent-changes banner was last dismissed/acknowledged. The banner composer reads `SyncEvent` rows since this watermark to decide whether to render. Reset to 0 on sign-out. Read with a `coerceAtMost(System.currentTimeMillis())` clamp so a clock skew (NTP correction backward after a phone with a fast clock) doesn't permanently freeze the unread badge. |

### New DAO methods (sketched, full list in the impl phase)

```kotlin
@Dao interface SyncDao {
    @Query("SELECT * FROM rd_numbers WHERE syncStatus = 'DIRTY' OR syncStatus = 'SYNC_ERROR' LIMIT :limit")
    suspend fun getDirtyRdNumbers(limit: Int = 500): List<RdNumber>

    @Query("UPDATE rd_numbers SET syncStatus = :status, syncedAt = :syncedAt, cloudId = COALESCE(cloudId, :cloudId), lastSyncError = NULL WHERE id = :id")
    suspend fun markRdNumberSynced(id: Long, status: SyncStatus, syncedAt: Long, cloudId: String?)

    // analogous methods for lots, sessions
}
```

---

## 7. How local maps to cloud

A flat reference. For every local table, the cloud table, and how each column translates.

| Local (Room v6) | Cloud (Postgres) | Transform |
|---|---|---|
| `scan_sessions.id` (Long PK) | not stored | local-only |
| `scan_sessions.cloudId` (UUID) | `scan_sessions.id` | direct |
| `scan_sessions.startTime` (epoch ms) | `start_time` (timestamptz) | `to_timestamp(ms / 1000.0)` |
| `scan_sessions.endTime` (epoch ms, nullable locally) | `end_time` (timestamptz, NOT NULL in cloud) | only synced when non-null |
| `scan_sessions.isActive` (Boolean) | not stored | only `isActive=false` rows ever sync |
| `scan_sessions.totalLots` | `total_lots` | direct |
| `scan_sessions.totalRdNumbers` | `total_rd_numbers` | direct |
| `scan_sessions.displayNumber` | `display_number` | server-assigned, see §10 |
| `scan_sessions.deviceCloudId` | `device_id` | direct (FK to devices) |
| `scan_sessions.operatorName` | `operator_name` | direct |
| `scan_sessions.activeLotId` | not stored | local-only (active session state) |
| `scan_sessions.updatedAt` | `updated_at` | epoch ms ↔ timestamptz |
| `scan_sessions.deletedAt` | `deleted_at` | epoch ms ↔ timestamptz, NULL means alive |
| `scan_lots.id` (Long PK) | not stored | local-only |
| `scan_lots.cloudId` (UUID) | `scan_lots.id` | direct |
| `scan_lots.sessionId` (Long FK) | not stored directly | resolved via cloudId to `session_id` |
| `scan_sessions.cloudId` of the parent | `session_id` (FK) | join on the local sessionId → look up local row → use its cloudId |
| `scan_lots.lotNumber` | `lot_number` | direct |
| `scan_lots.timestamp` | `timestamp` | direct |
| `rd_numbers.id` (Long PK) | not stored | local-only |
| `rd_numbers.cloudId` (UUID) | `rd_numbers.id` | direct |
| `rd_numbers.lotId` (Long FK) | not stored directly | resolved via cloudId to `lot_id` |
| `rd_numbers.number` | `number` | direct |
| `rd_numbers.position` | `position` | direct |
| `rd_numbers.scannedAt` | `scanned_at` | epoch ms ↔ timestamptz |
| `rd_numbers.monthsPaid` | `months_paid` | direct |
| `rd_numbers.monthsList` | `months_list` | direct |
| `device_settings.deviceCloudId` | `devices.id` | direct |
| `device_settings.deviceName` | `devices.device_name` | direct |
| `rd_accounts.rdNumber` (PK) | `rd_accounts.rd_number` (PK part) | direct |
| `rd_accounts.cloudId` | (= `rd_number`) | identity — no separate UUID |
| `rd_accounts.name` | `name` | direct |
| `rd_accounts.monthlyAmount` | `monthly_amount` | direct |
| `rd_accounts.lastPaidThrough` | `last_paid_through` | direct (text `YYYY-MM`) |
| `rd_accounts.source` | `source` | enum name (`MANUAL` / `CSV`) |
| `rd_accounts.isActive` | `is_active` | direct |
| `rd_accounts.accountOpenedDate` | `account_opened_date` | ISO date string ↔ date |
| `rd_accounts.accountClosingDate` | `account_closing_date` | ISO date string ↔ date |
| `rd_accounts.lastEditorDeviceId` | `last_editor_device_id` | direct, NULL = portal edit |
| `rd_accounts.updatedAt` | `updated_at` | epoch ms ↔ timestamptz |
| `rd_accounts.deletedAt` | `deleted_at` | epoch ms ↔ timestamptz, NULL = live |

**Invariant:** every row's `cloudId` is generated by the **originating phone** at row-creation time (using `UUID.randomUUID().toString()`). This means a row knows its cloud identity before it ever touches the network, which makes uploads idempotent. Replaying the same INSERT (with `ON CONFLICT DO UPDATE`) is safe.

---

## 8. Sync state machine

### Per-row lifecycle

```
        new (active session)
              │
              ▼
       ┌─────────────┐
       │ LOCAL_ONLY  │  ← created, not eligible to sync yet
       └─────────────┘
              │
              │ parent session finalized
              ▼
       ┌─────────────┐  ← also entered when user
       │   DIRTY     │     edits a SYNCED row
       └─────────────┘
              │
              │ WorkManager picks it up
              ▼
       ┌─────────────┐
       │   SYNCING   │
       └─────────────┘
            ↙   ↘
    success     failure
        │           │
        ▼           ▼
  ┌─────────┐ ┌──────────────┐
  │ SYNCED  │ │  SYNC_ERROR  │  ← will be retried by next worker run
  └─────────┘ └──────────────┘
        │           │
        └─────┬─────┘
              │ user edits or remote update
              ▼
          (DIRTY)
```

### Per-batch worker lifecycle

```
WorkManager job triggered:
    - finalize end-session
    - defaulter edit save
    - app foregrounded after being backgrounded > 5 min
    - 5-min foreground tick
    - manual retry from settings (future)
    - network constraint changed from "no" to "yes"

Job behavior:
    1. Check auth session validity; refresh if needed.
    2. PUSH PHASE:
       a. Select all DIRTY/SYNC_ERROR rows, ordered by (sessions, lots, rd_numbers) so parents land first.
       b. For each, mark SYNCING, perform upsert (see §12), on success mark SYNCED, on failure mark SYNC_ERROR with msg.
       c. Stop early on auth failure; surface to UI.
    3. PULL PHASE:
       a. Query cloud for any session/lot/rd_number/device with `updated_at > device_settings.lastPulledAt OR deleted_at > device_settings.lastPulledAt` AND `owner_id = me`.
       b. For each row: see §11 (merge).
       c. Update lastPulledAt = max(updated_at, deleted_at, lastPulledAt) seen.
    4. Update device.last_seen_at on cloud.
```

### Push order

Parents before children, always. The order is:

1. `devices` (only if our local device row isn't yet pushed)
2. `scan_sessions` (only those with `cloudId` set or freshly generated; never push active)
3. `scan_lots`
4. `rd_numbers`

Within each table, dirty rows are pushed individually (one UPSERT per row) in v1. Batch upserts are a future optimization.

### Pull order

Same as push: parents first. Otherwise we get foreign-key violations when inserting a lot whose session hasn't been pulled yet. The pull cursor query orders by `(updated_at, table_priority)` so we always have parents before children.

### Retry policy

- WorkManager backoff: `BackoffPolicy.EXPONENTIAL`, initial 30s, capped at 4 hours.
- Network constraint: `NetworkType.CONNECTED`.
- On auth failure (401/403): worker exits with `Result.failure()` and surfaces to UI; user is prompted to re-sign-in. No retries until they do.
- On any other failure: marked `SYNC_ERROR` with the error message, next worker run retries.

---

## 9. Auth and identity

### Sign-up flow

For v1, **the owner manually creates their account once** via the portal. There is no in-app signup; phones only sign in.

1. Owner visits `myrdscanner.example.com/signup` (or runs SQL once in Supabase) and creates `owner@example.com` / strong password.
2. (Optional) Owner sets up Supabase Auth's email confirmation; we keep it off for now to avoid friction.

### Sign-in on a phone

1. Phone opens the app for the first time after upgrade to v6.
2. If `device_settings.ownerId` is null → show **Sign-in screen** (full-screen, blocks scanner).
   - Email + password input.
   - "Sign in" button.
3. On successful sign-in:
   - Phone stores Supabase session in encrypted SharedPreferences (via Supabase Kotlin SDK's storage).
   - Phone generates a fresh `deviceCloudId = UUID.randomUUID()`.
   - Phone prompts "Name this phone" (text input, default suggestion: device model).
   - Phone prompts "Your name" (text input for operator name).
   - Both stored in `device_settings`.
   - First sync: insert `devices` row into cloud.
4. Pull all existing cloud data (other devices' sessions etc.) into the local Room cache.
5. Show normal home screen.

### Sign-out

A settings option. Clears `device_settings.ownerId` and session token. Local data is **not** deleted (we keep the cache so a re-sign-in is fast). Sync workers are cancelled.

### Auth token refresh

Supabase JWT tokens have 1-hour expiry, refresh tokens are long-lived. We use the Supabase Kotlin SDK's built-in auto-refresh. If a refresh fails (e.g. password was changed elsewhere), we surface to UI and force re-sign-in.

### Operator switching

A "Switch operator" entry in the app's settings menu. Tap → prompt for new operator name → updates `device_settings.operatorName`. From that moment on, finalized sessions are tagged with the new name. **Does not change `deviceCloudId`** — the phone is still the same phone.

---

## 10. Multi-device sync rules

### The active-session rule

Only one phone owns a session at any time. The "owner" is the phone that called `startFreshSession()` on it. While `is_active = true` (locally), the session **does not appear in cloud at all**. It cannot be seen by Phone B, by the portal, or by anyone. The session row is `LOCAL_ONLY`.

The instant "End Session" runs:
1. Local row's `endTime` is set, `isActive = false`, `activeLotId = null` (we already do this).
2. Local row's `syncStatus` flips `LOCAL_ONLY → DIRTY`.
3. Local row's `cloudId` is assigned if not already (`UUID.randomUUID()`).
4. Local row's `deviceCloudId` is set to our `device_settings.deviceCloudId`.
5. Local row's `operatorName` is set from `device_settings.operatorName`.
6. Local row's `updatedAt = now()`.
7. All local LOTs and RD numbers for that session also flip to `DIRTY` (with their own cloudIds generated if absent).
8. WorkManager enqueued (one-shot, NetworkType.CONNECTED).

### Display number assignment

Local code assigns a tentative display number using the existing `getNextDisplayNumber()` query, immediately for UI continuity. Cloud assigns the **canonical** display number when the session row is INSERTed (via the `next_display_number()` RPC). The phone updates its local `displayNumber` to match if it differs.

This means: while offline, you might see "Session #14" locally; when sync runs, it may turn into "Session #17" because Phone B already pushed sessions #14–16 from earlier. That's expected and correct.

### Pull strategy

Triggered by:
- App launch (always)
- Opening Session History (always)
- Every 5 min while foregrounded (configurable; not user-visible in v1)
- Realtime channel message (see §14)

Pull query:
```sql
-- pseudo-Postgrest call
SELECT * FROM scan_sessions
WHERE owner_id = :me
  AND (updated_at > :lastPulledAt OR deleted_at > :lastPulledAt);

-- Same pattern for scan_lots, rd_numbers, devices.
```

Each row is merged per §11.

### Realtime channel subscription

When the app is foregrounded, the phone subscribes to:
- `realtime:public:scan_sessions:owner_id=eq.<me>`
- `realtime:public:scan_lots:owner_id=eq.<me>`
- `realtime:public:rd_numbers:owner_id=eq.<me>`
- `realtime:public:devices:owner_id=eq.<me>`
- `realtime:public:rd_accounts:owner_id=eq.<me>`  (v8 — see §17)

On any payload, the phone runs a targeted pull for the affected row. This gives ~1s cross-device latency on the happy path. The 5-min poll is a backstop.

When backgrounded, the channels are closed. They reopen on next foreground.

---

## 11. Conflict resolution

### The rule

**Last-writer-wins, by `updated_at`. The losing side silently loses the data.**

**Tie-breaker (Phase 5 T5.7 amendment, F5 finding):** when
`remote.updated_at == local.updatedAt` exactly to the millisecond, **the
local row wins.** Pull-merge DAO queries use `WHERE updatedAt < :remote`
(strict less-than), not `<=`. Rationale: an equal-ms tie almost always
means the local DIRTY row is the same logical edit as the cloud row
that just echoed back via realtime — preserving local avoids clobbering
a pending push with its own future state.

Concretely, on pull, for each remote row:

```
local = SELECT * FROM <table> WHERE cloudId = remote.id

if local is null:
    INSERT a new local row mirroring remote, with syncStatus = SYNCED
    cascade: ensure parent row exists locally (FK), if not, defer until next pull cycle

else if local.syncStatus in (DIRTY, SYNCING, SYNC_ERROR):
    -- We have local changes that haven't been pushed yet.
    if remote.updated_at > local.updatedAt:   -- STRICT >
        -- Remote wins. Our local changes are silently dropped.
        OVERWRITE local with remote, mark SYNCED.
    else:
        -- We win (including ties). Skip; we'll push our version on next push cycle.
        do nothing.

else:  -- local.syncStatus == SYNCED
    if remote.updated_at > local.updatedAt:   -- STRICT >
        OVERWRITE local with remote.
    else:
        do nothing.

if remote.deleted_at is not null:
    -- Tombstone. Cascade delete locally too.
    -- Treat as a write: any local DIRTY descendants are lost.
    soft-delete the local row (set deletedAt) and its children.
```

### Why we accept silent-loser

For a 2-phone shop where each operator works one phone at a time, two writers editing the same defaulter row within a sub-second of each other is functionally impossible. The cost of building merge UI for an event that won't happen in practice is not justified in v1.

### Clock-skew clamp on inbound `updated_at` (round 5 hardening)

A phone with a wrong clock — manually set forward, broken NTP, restored from an old backup with stale system time — would push rows whose `updated_at` is months or years in the future. Strict LWW would then make those rows win every subsequent merge forever, including correct portal edits with the server clock.

The `clamp_updated_at()` trigger on `BEFORE INSERT` of every syncable table (`devices`, `scan_sessions`, `scan_lots`, `rd_numbers`, `rd_accounts`) clamps inbound `updated_at` to `LEAST(NEW.updated_at, now() + interval '1 hour')`. The 1-hour grace window allows for legitimate NTP drift and timezone math but rejects gross skew and pins it to `now()`. The clamp event is logged via `RAISE NOTICE` so Supabase Studio logs preserve a breadcrumb when this fires — a corrupted clock becomes visible during routine ops rather than discovered after data corruption.

The existing `set_updated_at()` trigger on `BEFORE UPDATE` already unconditionally overwrites with `now()`, so the clamp only matters for fresh INSERTs from the phone push path.

### Soft-delete propagation requires `ON DELETE RESTRICT` (round 5 hardening)

`scan_lots.session_id` and `rd_numbers.lot_id` are `ON DELETE RESTRICT`, not `CASCADE`. A cascade on hard-delete would silently drop child rows without producing tombstones, so phones that hadn't pulled yet would never learn the rows were deleted. Soft-delete (`deleted_at`) is the only supported deletion path — RESTRICT enforces that contract at the FK boundary so even a maintainer running raw SQL in Studio can't accidentally break it.

### What we MUST log

Every silent loss must produce an Android Log line at WARN level:
```
[Sync] Silent overwrite: rd_numbers.cloudId=<uuid>
       local.updatedAt=2025-10-04T12:34:56.789Z (status=DIRTY)
       remote.updatedAt=2025-10-04T12:34:57.123Z
       Discarded local change: monthsPaid 3->5, monthsList [Jun,Jul,Aug]
```

This is debug-only, never user-visible, but indispensable for diagnosing "wait my edit vanished" complaints.

---

## 12. API contract

We use Supabase PostgREST as our API. Every operation is a REST call to `https://<project>.supabase.co/rest/v1/<table>`. Auth via `Authorization: Bearer <jwt>` and `apikey: <anon_key>`.

### Endpoints we use

| Operation | HTTP | URL | Notes |
|---|---|---|---|
| List sessions (delta pull) | GET | `/rest/v1/scan_sessions?or=(updated_at.gt.X,deleted_at.gt.X)&order=updated_at.asc&limit=500` | Paginated. Same pattern for lots, rd_numbers, devices. |
| Upsert session | POST | `/rest/v1/scan_sessions` with `Prefer: resolution=merge-duplicates,return=representation` | PK conflict on `id` → merge. Returns the canonical row including server-assigned `display_number`. |
| Upsert lot | POST | `/rest/v1/scan_lots` | |
| Upsert rd_number | POST | `/rest/v1/rd_numbers` | |
| Mark session deleted | PATCH | `/rest/v1/scan_sessions?id=eq.<uuid>` body `{"deleted_at": "..."}` | We don't DELETE; we tombstone. |
| Get next display number | POST | `/rest/v1/rpc/next_display_number` body `{"p_owner_id": "..."}` | Called as part of the session-create flow. |
| Upsert device | POST | `/rest/v1/devices` | |

### Request body shapes

**Upsert session (request):**

```json
{
  "id": "8a7bf3c0-...",                    // local cloudId
  "owner_id": "...",                       // current auth user id
  "device_id": "...",                      // device_settings.deviceCloudId
  "operator_name": "Ravi",
  "display_number": 17,                    // tentative; server may overwrite via trigger? No — we set it post-RPC.
  "start_time": "2025-10-04T09:30:00Z",
  "end_time":   "2025-10-04T18:45:00Z",
  "total_lots": 12,
  "total_rd_numbers": 247,
  "default_count": 4,
  "updated_at": "2025-10-04T18:45:01Z"
}
```

**Upsert response:** same body, plus `created_at` and any server-assigned fields.

### Error contract

Standard Postgres / PostgREST errors. Our worker code maps:
- `401` → auth expired → trigger re-sign-in flow
- `403` → RLS denied → log + mark `SYNC_ERROR`
- `409` (rare): conflict → re-pull and retry
- `5xx` → retry with backoff
- Network failure → `SYNC_ERROR`, retry on next worker run

---

## 13. Row-level security (RLS) policies

RLS is enabled on all four data tables. Every query is filtered by `owner_id = auth.uid()`.

```sql
ALTER TABLE devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE scan_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE scan_lots ENABLE ROW LEVEL SECURITY;
ALTER TABLE rd_numbers ENABLE ROW LEVEL SECURITY;
ALTER TABLE rd_accounts ENABLE ROW LEVEL SECURITY;  -- v8

-- SELECT: see your own data
CREATE POLICY "owner can read own devices" ON devices
  FOR SELECT USING (owner_id = auth.uid());
CREATE POLICY "owner can read own sessions" ON scan_sessions
  FOR SELECT USING (owner_id = auth.uid());
-- ... and so on for lots, rd_numbers, rd_accounts

-- INSERT: only into your own rows
CREATE POLICY "owner can insert own devices" ON devices
  FOR INSERT WITH CHECK (owner_id = auth.uid());
-- ... and so on (including rd_accounts)

-- UPDATE: only update your own rows
CREATE POLICY "owner can update own devices" ON devices
  FOR UPDATE USING (owner_id = auth.uid()) WITH CHECK (owner_id = auth.uid());
-- ... and so on (including rd_accounts)

-- DELETE: we never DELETE from the client side; only set deleted_at.
-- No DELETE policies. Hard deletes are admin-only (via Supabase dashboard).
```

`next_display_number()` is `SECURITY DEFINER` and explicitly accepts `p_owner_id`. We assert inside the function that `p_owner_id = auth.uid()` to prevent cross-account leakage.

---

## 14. Realtime channel design

Subscribe in the Android app:

```kotlin
val sessionsChannel = supabase.channel("rt:scan_sessions:$ownerId") {
    postgresChange<ScanSessionDto>(
        schema = "public",
        table = "scan_sessions",
        filter = "owner_id=eq.$ownerId",
        events = listOf(INSERT, UPDATE, DELETE)
    ) { change ->
        syncRepository.handleRealtimeChange(change)
    }
}
sessionsChannel.subscribe()
```

`handleRealtimeChange` does a **targeted pull** for the changed row by id, then runs the merge logic in §11. We don't trust the realtime payload alone (it can be incomplete in some edge cases); we use it as a "go look" trigger.

Channels are subscribed when the app comes to foreground (in `MainActivity.onStart()`) and unsubscribed in `onStop()`. We don't keep them alive in the background — that's what WorkManager + the 5-min poll on next foreground is for.

---

## 15. Android-side architecture

### Package layout

```
com.qrscanner.app
├── data
│   ├── ... existing entities, DAOs, AppDatabase
│   ├── RdAccount.kt                  ← v8 (see §17)
│   ├── RdAccountDao.kt               ← v8
│   ├── AccountSource.kt              ← v8 (MANUAL | CSV)
│   ├── sync                          ← NEW PACKAGE
│   │   ├── SyncStatus.kt
│   │   ├── SyncDao.kt
│   │   ├── DeviceSettings.kt
│   │   ├── DeviceSettingsDao.kt
│   │   └── SyncRepository.kt
│   └── cloud                         ← NEW PACKAGE
│       ├── CloudClient.kt            (thin wrapper over Supabase client)
│       ├── dto                       (data transfer objects mirroring cloud schema)
│       │   ├── ScanSessionDto.kt
│       │   ├── ScanLotDto.kt
│       │   ├── RdNumberDto.kt
│       │   ├── DeviceDto.kt
│       │   └── RdAccountDto.kt       ← v8
│       └── mappers                   (Entity ↔ DTO)
│           ├── ScanSessionMapper.kt
│           ├── RdAccountMapper.kt    ← v8
│           └── ... etc
├── work
│   ├── SyncPushWorker.kt             (one-shot, push only)
│   ├── SyncPullWorker.kt             (one-shot, pull only)
│   └── SyncWorkScheduler.kt          (knows which to enqueue, when)
└── ui
    ├── auth
    │   ├── SignInScreen.kt           ← NEW
    │   └── FirstRunSetupScreen.kt    ← NEW (device + operator name)
    ├── settings
    │   ├── SettingsScreen.kt         ← NEW
    │   ├── SwitchOperatorDialog.kt   ← NEW
    │   └── SyncStatusCard.kt         ← NEW (badge + retry-now button)
    └── ... existing screens (Home, RDScanner, etc.) get a small "pending sync N" badge
```

### Dependencies to add

Versions verified against supabase-kt 3.1.4 (Kotlin 2.2.x compatible). The
`gotrue-kt` module was renamed to `auth-kt` in 3.0.0; using the old name will
fail to resolve. Ktor engine is **okhttp** (not android) because we need
WebSocket support for Realtime.

```kotlin
// in app/build.gradle.kts

plugins {
    kotlin("plugin.serialization") version "2.2.10"
}

dependencies {
    // Supabase BOM aligns all -kt module versions
    implementation(platform("io.github.jan-tennert.supabase:bom:3.1.4"))
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:auth-kt")        // renamed from gotrue-kt
    implementation("io.github.jan-tennert.supabase:realtime-kt")

    // Ktor okhttp engine (WebSocket-capable, required for Realtime)
    implementation("io.ktor:ktor-client-okhttp:3.2.0")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")

    // Encrypted session storage for the Supabase JWT
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Settings backing for the encrypted SessionManager
    implementation("com.russhwolf:multiplatform-settings:1.2.0")
    implementation("com.russhwolf:multiplatform-settings-no-arg:1.2.0")

    // Sync workers
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Lifecycle-aware Flow collection in Compose
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
}
```

Plus the existing Room + Compose stack stays unchanged.

**EncryptedSharedPreferences fallback note:** Some API 26–28 devices ship with
broken Keystore implementations that throw at `MasterKey.Builder.build()`. The
`SecureSessionStorage` wrapper catches the exception and falls back to plain
`SharedPreferences` with a WARN log entry. Acceptable degradation: on those
devices the session token is unencrypted but otherwise identical. Documented
because the silent fallback is not obvious.

### Manifest changes

```xml
<uses-permission android:name="android.permission.INTERNET" />  <!-- already have -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />  <!-- NEW -->

<application>
    <!-- existing entries -->

    <provider
        android:name="androidx.startup.InitializationProvider"
        android:authorities="${applicationId}.androidx-startup"
        android:exported="false"
        tools:node="merge">
        <meta-data android:name="androidx.work.WorkManagerInitializer"
            android:value="androidx.startup" tools:node="remove" />
    </provider>
</application>
```

(WorkManager initialization will be explicit in `QRScannerApp.onCreate()`.)

### Configuration

Cloud project URL and anon key live in `local.properties` (NOT committed) and are surfaced to BuildConfig:

```properties
# local.properties
SUPABASE_URL=https://xxxxx.supabase.co
SUPABASE_ANON_KEY=eyJ...
```

```kotlin
// app/build.gradle.kts (relevant snippet)
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.let { load(it.inputStream()) }
}
defaultConfig {
    buildConfigField("String", "SUPABASE_URL", "\"${localProps.getProperty("SUPABASE_URL", "")}\"")
    buildConfigField("String", "SUPABASE_ANON_KEY", "\"${localProps.getProperty("SUPABASE_ANON_KEY", "")}\"")
}
buildFeatures.buildConfig = true
```

For release builds we'll bake them in the same way; the anon key is by design safe to ship (RLS protects the data).

### UI surface — minimal changes

- **HomeScreen**: small status pill in the top-right corner: green dot ("All synced"), amber dot ("3 pending"), red dot ("Sync error — tap to retry").
- **SettingsScreen** (new, accessible from a gear icon on Home): operator name, device name, sign-out, sync status (last successful push, pending count, errors).
- **SignInScreen** (new): shown when not signed in. Email + password.
- **FirstRunSetupScreen** (new): shown after first successful sign-in. Two text inputs (device name, operator name) + Continue.

The scanner, history, detail, generator, help, info screens are **functionally unchanged**. They render data from local Room exactly as today. The only difference is that the local Room data may now arrive via sync rather than direct scanning.

### State coordination

A single `SyncRepository` (singleton, injected) owns:
- Knowledge of "are we signed in?"
- Knowledge of "what's our current sync status?" (exposed as `Flow<SyncSummary>`)
- The method to enqueue work, force pull, force push, etc.

UI observes `syncRepository.summaryFlow` for the status pill. Workers call `syncRepository.runPush()` and `runPull()`.

---

## 15.5 Notifications (in-app banner + system tray)

Two surfaces. Both required. Both held to the same Dribbble-level bar as the rest of the app (PrimaryOrange / WarningAmber / AccentMint palette, spring motion tokens, 24dp corner radius, no slop).

### 15.5.1 In-app "recent changes since last open" banner

**Surface:** dismissible horizontal card under `HomeScreen` top bar.

**Trigger:** on `HomeScreen` first composition (or return to foreground after >5 min), compute the `SyncEvent` log delta since `device_settings.lastBannerSeenAt`. If empty → no banner. If non-empty → render banner, set `lastBannerSeenAt` only when user dismisses or navigates from it (not on first render — gives the user a chance to actually read).

**Visual contract:**
- 12dp inset from screen edges, 16dp internal padding.
- Background: `PrimaryOrange.copy(alpha = 0.08f)` on white card.
- Leading icon: `Icons.Default.NotificationsActive`, tint `PrimaryOrange`, 24dp.
- Title (`titleSmall`, `FontWeight.SemiBold`, `PrimaryOrange`): 1 line, e.g. "3 recent updates".
- Body (`bodySmall`, `TextSecondary`): max 2 lines, ellipsized. Aggregated summary: `"Counter Phone finalized Session #47 · Ravi marked 2 defaulters · Owner edited Session #42"`.
- Trailing dismiss `IconButton` (`Icons.Default.Close`, 20dp, `TextSecondary`).
- Tap the body → navigate to most-recent event's target screen (session detail / history).
- Animations: enter `slideInVertically + fadeIn` spring `DampingRatioMediumLow`. Exit `slideOutVertically + fadeOut` tween 220ms.
- Auto-dismiss after 8s of visibility OR on swipe-right (use `swipeable` modifier).
- `rememberSaveable` for visibility flag.

**Aggregation rule:** events from the same `deviceCloudId` within a 60s window collapse into one line. Defaulter edits from the same operator within 60s collapse into one line ("Ravi marked 2 defaulters"). Maximum 3 lines in the body; older events drop off but remain in a "view all" detail view (deferred to v2).

**Event types feeding the banner:**
- Cross-device session finalized (`scan_sessions` insert from `device_id != mine`).
- Cross-device defaulter edit (`rd_numbers` update where `monthsPaid` or `monthsList` changed, `deviceId != mine`).
- Portal defaulter edit (`rd_numbers` update where source is not a phone).
- Session deletion by another device.

**NOT feeding the banner:**
- Your own phone's events. The status pill is your feedback.
- Active-session events (active sessions don't sync, so this is moot).
- Sync errors. Those go to the status pill + system notification per below.

### 15.5.2 System-tray (OS-level) notifications

Three triggers ship in v1, all selected by owner. Each is a distinct channel for user-level control.

**Channel A — `sync_success` (NotificationManager.IMPORTANCE_LOW):**
- Fires when **this phone's** push succeeds for a finalized session.
- Title: `"Session #47 synced"`.
- Body: `"12 LOTs, 247 RD numbers uploaded by Ravi"` — `%3$s` is the actor label (operator name preferred, device name fallback, blank if neither). Format updated in a9a53b2 from `· Counter Phone` (device name only) to `by Ravi` (operator-prefer chain matching `RemoteEditNotice.originLabel`).
- Tap → opens app to that session's detail.
- Silent (no sound), no vibration.
- Auto-cancel on tap.

**Channel B — `sync_error` (NotificationManager.IMPORTANCE_DEFAULT):**
- Fires after 3 consecutive failed push retries on the same session.
- Title: `"Sync paused"`.
- Body: `"3 sessions waiting · tap to retry"`.
- Tap → opens app to Settings → Sync diagnostics, where "Retry now" is the primary action.
- Default sound, no vibration.
- Auto-cancel on tap.
- Re-fires every additional 6 failures (not every retry) to avoid spam.

**Channel C — `remote_edit` (NotificationManager.IMPORTANCE_LOW):**
- Fires when a remote-originated edit lands on this phone via pull/realtime. Three event types are supported (`SyncEventType.REMOTE_SESSION_FINALIZED`, `REMOTE_DEFAULTER_EDIT` / `PORTAL_DEFAULTER_EDIT`, `REMOTE_SESSION_DELETED`).
- Titles differ per type — see `notif_remote_session_finalized_title`, `notif_remote_defaulter_edit_title`, `notif_remote_session_deleted_title`.
- Bodies use the operator-prefer originLabel:
  - Finalized: `"Ravi finished a session on another phone."` (`notif_remote_session_finalized_body`)
  - Defaulter edit: `"Ravi edited defaulter months."` (`notif_remote_defaulter_edit_body`)
  - Deleted: `"Removed by Ravi."` (`notif_remote_session_deleted_body`)
- Tap → opens app.
- Silent, no vibration.
- Auto-cancel on tap.
- Notification id is deterministic from `(type.ordinal, displayNumber % 100_000)` so successive edits to the same (type, session) tuple collapse rather than stack.

**Reversal of the v1 "no cross-device session-finalized" decision** (a9a53b2): Phone A finalizing a session DOES now push a Channel C notification to Phone B (`REMOTE_SESSION_FINALIZED`). The original "banner is enough" stance turned out to under-surface critical handoffs in 2-phone shops. The banner remains the primary in-app affordance; the tray notification is the persistent record. Multi-edit aggregation to the same session within 30s still applies.

### 15.5.3 Permission flow

Android 13+ (API 33+) requires the `POST_NOTIFICATIONS` runtime permission. Manifest:

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

**Flow:**
1. User signs in successfully on a fresh install.
2. `FirstRunSetupScreen` captures device name + operator name.
3. After "Continue" tap, navigate to a new `NotificationPermissionScreen`.
4. Full-screen rationale: bell icon (96dp, PrimaryOrange.copy(alpha=0.12f) circle background), title "Stay in sync", body "We'll let you know when other phones in your shop upload new sessions or when the owner edits anything from the web."
5. Two buttons: "Enable notifications" (primary, PrimaryOrange) → calls `ActivityResultContracts.RequestPermission()` for `POST_NOTIFICATIONS`. "Skip" (text button, TextSecondary) → proceeds without prompting; user can enable later from Settings.
6. After permission resolves (granted, denied, or skipped) → HomeScreen.
7. If denied: a one-time soft prompt in Settings ("Enable notifications" row with `Switch` that opens system settings on tap). No nagging in HomeScreen.

For API < 33: skip the screen entirely. Notifications work without runtime permission on older Android.

### 15.5.4 Notification content & i18n

All strings live in `res/values/strings.xml` (English) and `res/values-hi/strings.xml` (Hindi). Pattern:

```xml
<string name="notif_sync_success_title">Session #%1$d synced</string>
<string name="notif_sync_success_body">%1$d LOTs, %2$d RD numbers uploaded by %3$s</string>
```

```xml
<!-- values-hi -->
<string name="notif_sync_success_title">सेशन #%1$d सिंक हो गया</string>
<string name="notif_sync_success_body">%3$s ने %1$d LOT, %2$d RD नंबर अपलोड किए</string>
```

The Hindi body uses positional reordering (`%3$s` before `%1$d`) so the ergative "ने" particle attaches to the actor name correctly — verified by C11-P5 native-speaker review and reinforced by the §15.5.11 design rule.

`NotificationCompat.Builder` reads from string resources via `context.getString(R.string.xxx, args...)`. Locale is auto-resolved from device settings; no manual locale handling needed.

### 15.5.5 Sync event log (powers the banner)

New Room table `sync_events`:

```kotlin
@Entity(tableName = "sync_events")
data class SyncEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val occurredAt: Long,                  // epoch ms, server-side updated_at preferred
    val type: SyncEventType,               // enum (REMOTE_*, PORTAL_*, LOCAL_*)
    val sessionCloudId: String?,           // FK by cloud id; nullable for device events
    val rdNumberCloudId: String?,
    val originDeviceCloudId: String?,      // who caused it; null for portal
    val originDeviceName: String?,         // denormalized for fast banner render
    val originOperatorName: String?,       // denormalized
    val payloadSummary: String              // pre-rendered "marked 2 defaulters" etc.
)

enum class SyncEventType {
    // Remote-originated — landed via pull / realtime
    REMOTE_SESSION_FINALIZED,
    REMOTE_DEFAULTER_EDIT,
    PORTAL_DEFAULTER_EDIT,
    REMOTE_SESSION_DELETED,

    // Locally-originated — this device performed the action. These events
    // exist only on this phone's bell history (never pushed to cloud) so
    // the operator can review their own timeline alongside remote events.
    // Filtered out of the banner + unread badge (the action's own UI
    // already confirmed it; a banner saying "you finalized this session"
    // is noise) but visible in the full SyncHistorySheet for context.
    LOCAL_SESSION_FINALIZED,
    LOCAL_ACCOUNTS_ADDED,
    LOCAL_DEFAULTER_EDIT
}
```

REMOTE_* and PORTAL_* events are populated by `SyncRepository.handleRemoteChange()` after a pull/realtime payload is merged. LOCAL_* events are inserted by the UI code that performed the action (`RDScannerScreen.finalizeSession`, `LotReviewPersister.persist`, `AddAccountsScreen.persistAll`) — they never go through the sync pipeline because they have no cloud-side counterpart.

**Release-build breadcrumb logs (round 5):** the sync pipeline emits three INFO-level breadcrumbs on every cycle so production support cases ("portal edit didn't reach phone B", "phone is stuck on stale data") can be debugged from `adb logcat` alone:

- `pull start: sinceCursor=<ms> ownerId=<uuid>` — emitted at the top of every `runPull` so the cursor is in the log trail
- `push start: sessions=N accounts=M` — emitted once per `runPush` after the dirty-row sweep so "how many rows are pending?" has an answer
- `realtime <event> on <table> cloudId=<uuid>` — emitted at INFO level, rate-limited to one entry per 5-second window per table so bulk CSV imports (100+ events in seconds) don't spam logcat. DEBUG builds still log every realtime payload via the adjacent `Log.d` for dev-time tracing

These complement the existing silent-overwrite WARN logs (spec §11 line 626 contract) so every LWW loser produces a structured breadcrumb with full before/after state.

Bounded by **two independent rules applied as AND** in the prune query: **keep at most the 100 most-recent rows** AND **drop anything older than 7 days regardless of count**. Both must hold for deletion, which is the more conservative semantics (preserves more rows than an OR would). The `SyncEventPruneWorker` runs this query on a daily periodic schedule with `KEEP` policy (subsequent enqueues are no-ops) and a `setRequiresBatteryNotLow` constraint so background cleanup doesn't compete with foreground sync for battery.

`payloadSummary` is pre-rendered in English at insert time (e.g. `"finalized Session #47 (12 LOTs)"`). i18n is deferred — half-localizing at insert time would mix locales unpredictably in the bell history; the proper fix is a typed payload schema with template lookup at render time. Tracked as an open question (§24).

**Origin attribution rule (Phase 5 T5.6 amendment, F9 finding):** the
`originDeviceCloudId` field is derived from the *row's own*
`last_editor_device_id` cloud column, NOT inferred from the parent
session's `device_id`. The parent session's `device_id` is the device
that originally *scanned* the row, which is wrong for an edit that
happened later from a different device — especially the portal, which
never appears in `devices` at all. The cloud schema adds
`rd_numbers.last_editor_device_id` (nullable, FK to `devices.id`).
Phones stamp their own `deviceCloudId` on every push; the portal
writes `null`. The merge then classifies events as follows:

| `last_editor_device_id` | origin | event type |
|---|---|---|
| equals own deviceCloudId | self | suppressed (no banner, no Channel C) |
| equals another device | another phone | `REMOTE_*` with `originLabel = operatorName` |
| `null` | portal | `PORTAL_DEFAULTER_EDIT` with `originLabel = "Portal"` |

Legacy rows from pre-T5.6 phones (column `null`, parent session has a
non-blank `device_id`) fall back to the parent session's device for
attribution to avoid mislabeling cross-device sync echoes as portal
edits.

The banner reads:

```kotlin
@Query("SELECT * FROM sync_events WHERE occurredAt > :since ORDER BY occurredAt DESC LIMIT 20")
suspend fun getEventsSince(since: Long): List<SyncEvent>
```

Banner composer reads the events, aggregates per §15.5.1, renders the card.

### 15.5.6 Notification channels (Android 8+)

Created once in `QRScannerApp.onCreate()`:

| Channel id | Importance | Sound | Vibration | User label |
|---|---|---|---|---|
| `sync_success` | LOW | none | none | "Sync confirmations" |
| `sync_error` | DEFAULT | default | none | "Sync problems" |
| `remote_edit` | LOW | none | none | "Updates from owner" |

User can disable per channel via system Settings → Notifications. We respect those preferences.

### 15.5.7 SCHEMA_MISSING pill state (Phase 5 T5.1 amendment)

When the cloud schema hasn't been applied yet (owner forgot to paste
`cloud/schema.sql` into Supabase Studio), PostgREST returns
`PGRST205` / `PGRST202` or Postgres surfaces `42P01` / `42883`. The
sync repository routes these to a distinct pill state `SCHEMA_MISSING`
rather than the generic `ERROR`. Differences:

- **Dot color:** `PrimaryOrange` (calm) instead of `ErrorRed`.
- **Label:** "Cloud setup needed" instead of "Sync error · tap".
- **Tray notification suppression:** the every-3rd-failure
  `notifySyncError` is never fired — the pill copy already tells the
  user what to do, and a tray alert about an unstarted system is
  noise. Push/pull workers keep retrying with exponential backoff so
  recovery is automatic once the schema is applied.

Tap routes to the diagnostics screen (Phase 5 backlog) which renders a
"paste cloud/schema.sql into Supabase Studio" hint.

### 15.5.8 Drain-until-empty pull loop (Phase 5 T5.4 amendment)

The `CloudClient.pullChangesSince` contract gains a `pageWasFull`
boolean on `CloudDelta`. `SyncRepository.runPull` wraps its merge
phase in a `while(pageWasFull && highWaterMark advanced && pages <
MAX_DRAIN_PAGES)` loop so first-run sign-in with thousands of cloud
rows drains in a single `runPull` cycle instead of waiting 10+
foreground-poll ticks. `MAX_DRAIN_PAGES = 20` (10k row safety cap) +
the `highWaterMark > prior` guard prevents infinite loops on a
runaway cursor (clock skew producing identical timestamps).

### 15.5.9 Mid-edit tombstone guard (Phase 5 T5.5 amendment)

`SessionDetailScreen` observes a `Flow<ScanSession?>` from
`ScanSessionDao.observeSessionById(id)` (filters
`deletedAt IS NULL`). If a delete from another device arrives via
pull while the user is on this screen, the Flow emits `null`. After
the first non-null emission seeded the local state
(`sessionEverLoaded = true`), any later null Toast-shows
"Session deleted by another device" and calls `onNavigateBack()`.
Prevents the user from editing a tombstoned session and pushing
orphan edits.

### 15.5.10 Concurrency: serialized push + pull (Phase 5 T5.2 amendment)

`SyncRepository` holds a single `kotlinx.coroutines.sync.Mutex`
shared across `runPush` and `runPull`. Realtime payload handler
(which calls `runPull` directly), the 5-min foreground poll, the
WorkManager push/pull workers, and one-shot enqueue calls all
contend for the same lock. This eliminates two race classes:

1. Two concurrent `runPull` invocations both writing
   `device_settings.lastPulledAt` — if the slower finished last with
   an older `highWaterMark`, the cursor regressed and rows were
   re-processed.
2. A pull's merge phase observing a push half-way through promoting
   orphan-finalized sessions to DIRTY.

Critical sections are bounded (~one delta page = <2s in normal
network conditions), so the non-fair mutex doesn't risk starvation.

### 15.5.11 Bell icon + sync history sheet (v8.2 amendment)

User-explicit history surface added in commit `a9a53b2` so the operator can answer "who did what" without waiting for the in-app banner to surface (banner is bounded to 3 lines and only shows events since `lastBannerSeenAt`). The bell is **always visible** next to the SyncStatusPill on HomeScreen.

**Visual contract — `ui/components/BellIcon.kt`:**
- 44dp WCAG-compliant round white chip (`Color.White.copy(alpha = 0.92f)`), `CircleShape`.
- `Icons.Default.Notifications` glyph, 22dp, `TextSecondary` tint.
- Badge overlay (top-right, 4dp inset): `AccentCoral` rounded pill, 16dp min, 10sp white bold text.
- Badge cap: counts > 9 render as `"9+"`.
- Badge animation: `scaleIn` + `fadeIn` (spring `DampingRatioMediumBouncy`); exit `scaleOut` + `fadeOut`.
- Press feedback: 92% spring scale matching SyncStatusPill so the two read as one row.
- A11y: `contentDescription` reads `bell_a11y_description_unread` (with count) when `unreadCount > 0`, else `bell_a11y_description`.

**Visual contract — `ui/components/SyncHistorySheet.kt`:**
- Material3 `ModalBottomSheet`, `skipPartiallyExpanded = true`, `SurfaceWhite` container.
- Custom drag handle: 36×4dp pill, `TextSecondary.copy(alpha = 0.3f)`.
- Header: 40dp orange-tinted bell medallion + `sync_history_title` + subtitle (`sync_history_subtitle` with count or `sync_history_subtitle_empty`).
- Empty state: 72dp peach-tinted `EventNote` circle + `sync_history_empty_title` + body explainer.
- Per-row layout: 40dp circular actor avatar tinted by event type (mint=finalized, amber=defaulter edit, red=delete) + actor name + relative time on the right + action template below the actor.
- Max list height: 520dp; scrolls inside `LazyColumn` with 8dp spacing.

**Actor label resolution** (matches `RecentChangesBanner.originLabel` and `RemoteEditNotice.originLabel` exactly — the 3 surfaces share semantics):
1. `originDeviceCloudId == null` → `sync_history_row_actor_portal` ("Portal")
2. `!originOperatorName.isNullOrBlank()` → operator name
3. `!originDeviceName.isNullOrBlank()` → device name
4. else → `sync_history_row_actor_other_phone` ("Another phone")

**Action templates** (rendered as a second line below the actor; per-locale grammar must work standalone — the actor is NOT inlined into the template at runtime):
- `sync_history_row_session_finalized` — `"finalized Session #%1$s"` / Hindi: `"सेशन #%1$s फ़ाइनल किया"`
- `sync_history_row_defaulter_edit` — `"updated defaulters on Session #%1$s"` / Hindi: `"सेशन #%1$s के डिफ़ॉल्टर अपडेट किए"`
- `sync_history_row_session_deleted` — `"deleted Session #%1$s"` / Hindi: `"सेशन #%1$s डिलीट किया"`

NB on Hindi: ergative "ने" deliberately omitted from the templates above. The standalone past-participle form (`फ़ाइनल किया` / `डिलीट किया` / `अपडेट किए`) reads correctly without a preceding subject because the actor is in its own `Text` composable above. An earlier draft that started the templates with `ने सेशन ...` was rejected (C11-P5 oracle HIGH finding) since the ergative marker requires the subject to be glued to the same visual unit, which our row layout doesn't provide.

**Relative time formatting** (composable `formatRelativeTime(timestamp, now)`):
| Delta from now | Format |
|---|---|
| < 2 min | `sync_history_relative_just_now` |
| < 60 min | `sync_history_relative_minutes` (%1$d) |
| < 24 hr | `sync_history_relative_hours` (%1$d) |
| == 1 day | `sync_history_relative_yesterday` |
| else | `sync_history_relative_days` (%1$d) |

**Unread count semantic:** Bell badge count = `SyncEventDao.observeEventsSince(lastBannerSeenAt, 20).size`. The sheet renders `SyncEventDao.observeRecentEvents(100)` — the unfiltered last-100 log so the operator can scroll back even after acknowledging. Dismissing the sheet (swipe down or tap outside) bumps `DeviceSettings.lastBannerSeenAt` to `now` so the badge clears AND the in-app banner stops re-showing the same events. Sheet and banner share one watermark by design — viewing one acknowledges both.

**Rotation contract:** `showHistorySheet` is `remember { mutableStateOf(false) }` (NOT `rememberSaveable`) — rotating mid-sheet closes it. Acceptable since the sheet is a transient view and the bell remains tappable to re-open.

**Why this exists separate from §15.5.1 banner:** Banner is ephemeral, bounded to 3 lines, dismissable, designed for "what changed since I last looked". Sheet is the persistent receipt log with unbounded scroll-back, designed for "I want to audit who did what across the last week". Two complementary surfaces, one shared watermark.

### 15.5.12 Per-LOT ₹20,000 cap enforcement (v8.2 amendment, 253819c)

Mirrors the portal's per-LOT total limit on the phone so over-limit LOTs are caught BEFORE sync would reject them. Per-LOT, **not** per-session.

**Cap rule:** `Σ (RdAccount.monthlyAmount × monthsPaid)` across every row in a LOT must be `<= 20,000`. **Exactly ₹20,000 is allowed** — boundary check is strict greater-than (`LotTotal.isOver = verifiedRupees > LOT_TOTAL_LIMIT_RUPEES`), not `>=`. The sum is enforced on the **verified** total only — rows whose `RdAccount.monthlyAmount` is null or zero (no profile yet) are excluded from the sum but counted separately.

**Enforcement surfaces:**
1. `LotReviewScreen` in `LotReviewMode.FreshScan` (in-flight LOT, post-scan, pre-finalize). Fires on **Confirm** tap before the regression-confirm flow. Two actions:
   - **Cancel** → return to review screen so operator can reduce month counts.
   - **Rescan this LOT** → tear down + return to scanner at the same LOT number (see *Rescan semantic* below).
2. `LotReviewScreen` in `LotReviewMode.RecordedEdit` (post-finalize edit from `SessionDetailScreen`). Fires on **Save** tap before the existing skip-gap-confirm flow. Single action:
   - **Got it** → dismiss + return to editor so operator can reduce counts. No rescan because the session is already finalized.

Prior to commit 8821852 the post-finalize editor lived in a separate `DefaulterEditDialog`. The two paths were unified into `LotReviewScreen` with a `LotReviewMode` discriminator (FreshScan / RecordedEdit) so the cap logic, regression confirm, and row hydration code stay in one place.

**Live signal on `LotReviewScreen`:** `LotTotalLine` above the Confirm button shows `Total: ₹X · limit ₹20,000` (mint when under) or `Total ₹X exceeds ₹20,000 limit` (coral when over) plus a `N without profile not counted` line when unverified rows exist. Operator self-corrects before tapping Confirm.

**Rescan semantic** (locked Q3 decision, 253819c):
- The deleted LOT keeps its number (operator re-attempts LOT N, doesn't advance to N+1).
- `rdNumberDao.deleteForLot(lotId)` (hard delete on local-only rows — the LOT was never finalized so it has no cloudId), then `scanLotDao.deleteIfEmpty(lotId)` (race-safe variant in case another device's realtime push lands an insert mid-deletion).
- `scanSessionDao.setActiveLotId(session.id, null)` releases the parent session's active-LOT pin.
- `totalLotsInSession--` + `currentLotNumber--` both `coerceAtLeast` their floor (0 and 1 respectively).
- `currentLotNumbers.clear()` AND `allSessionNumbers.removeAll(deletedNumbers)` — both must run, otherwise the next rescan of the same RD number trips the session-level dedup guard and the rescan is dead-locked (QC-H CRITICAL caught this gap).
- **Prior LOTs in the session stay intact.** This is a user-facing contract operators rely on.

**Locked product decisions** (5 from 253819c product Q&A):
- Q1 unverified rows = warn-but-skip (count only profile rows in the cap check).
- Q2 live running total in confirm bar = yes.
- Q3 after Rescan = same LOT number (re-attempt, don't advance).
- Q4 popup breakdown = total + excess only (no per-row breakdown, no top-3).
- Q5 same check on SessionDetail edit = yes.

**Client-side enforcement only in v1.** A modified APK could bypass by writing directly to the cloud DTO upsert path. Acceptable for the single-owner trusted-employee threat model per spec §5; revisit in v2 with a Postgres CHECK constraint or Supabase Edge Function if untrusted devices become a concern (QC-E LOW finding).

### 15.5.13.1 Cache + dedup performance contract

The `lotAmountCache` is resolved at the scan site (single DAO call per append) rather than in a size-keyed `LaunchedEffect` that re-filtered the whole list. The cache is cleared per-LOT in `finishCurrentLot`, `endSession`, `undoLastScan`, and rescan paths so a re-scanned RD number after a mid-session amount edit doesn't reuse a stale value. Rehydration paths (`adoptSession`, `rehydrateAfterConfigChange`) prime the cache via a `sessionId`-keyed effect that fires once per session boot.

Duplicate detection within the current LOT and across the session uses parallel `HashSet<String>` mirrors of the visible `mutableStateListOf<String>` collections. Every add/remove on the visible list MUST also mutate the matching set or the dedup guard silently breaks. Set lookups are O(1) and avoid the cumulative ~250ms scanner jank that List.contains incurred at 500 scans/session.

### 15.5.14 LotReviewScreen architecture (unified editor)

The fresh-scan post-LOT review screen and the post-finalize "edit defaulters" surface are the same Compose screen with a `LotReviewMode` discriminator. The architecture has three pure I/O boundaries that callers stitch together; the screen itself is a thin renderer over the resulting `List<LotReviewRow>`.

```kotlin
sealed interface LotReviewMode {
    data class FreshScan(val lotId: Long, val lotNumber: Int, val lotTimestamp: Long) : LotReviewMode
    data class RecordedEdit(val lotId: Long, val lotNumber: Int, val lotTimestamp: Long) : LotReviewMode
}

object LotReviewBuilder {
    /**
     * Hydrates [LotReviewRow] list from the DB. For each rd_number in the
     * LOT, reads the optional [RdAccount] profile (name + monthlyAmount +
     * lastPaidThrough) via a single batched [findByRdNumbers] call, then
     * computes the auto-anchored month-list selection.
     *
     * originalSelected MUST reflect the DB's actual stored monthsList,
     * NOT the auto-suggested default — so confirming the auto-anchor for
     * a row that had no stored list counts as a real edit and advances
     * lastPaidThrough. Using the auto-suggested value here was the H1
     * regression that silently dropped scan-and-confirm advances.
     */
    suspend fun build(app: QRScannerApp, lotId: Long, lotTimestamp: Long): List<LotReviewRow>
}

sealed interface LotReviewOutcome {
    data class Success(val rowsPersisted: Int) : LotReviewOutcome
    data object SessionTombstoned : LotReviewOutcome  // parent deleted mid-edit
    data class Error(val cause: Throwable) : LotReviewOutcome
}

object LotReviewPersister {
    /**
     * Writes edits atomically across rd_numbers + rd_accounts inside a
     * single Room transaction:
     *   1. Update rd_numbers.monthsList + monthsPaid for each changed row
     *   2. Advance rd_accounts.lastPaidThrough monotonically per row
     *      (uses [updateLastPaidThroughMonotonic] for auto-anchor advance;
     *      uses [setLastPaidThroughExplicit] when the operator explicitly
     *      regresses the value via the confirm dialog — see D23)
     *   3. Mark dirty + enqueue push
     *
     * Returns SessionTombstoned if the parent session was deleted
     * (from another device's pull) between hydration and persist.
     */
    suspend fun persist(
        app: QRScannerApp,
        baseRows: List<LotReviewRow>,
        edits: Map<Long, List<MonthYear>>
    ): LotReviewOutcome
}

data class LotReviewRow(
    val rdNumber: RdNumber,
    val accountName: String?,
    val accountLastPaidThrough: MonthYear?,
    val accountMonthlyAmount: Int?,
    val selected: List<MonthYear>,
    /** DB baseline — used by [hasChanges] to filter no-op edits from the persist batch. */
    val originalSelected: List<MonthYear>
) {
    val hasChanges: Boolean get() = selected != originalSelected
}
```

**Why split into Builder + Persister:** the screen's main coroutine stays I/O-free; both boundaries are pure suspend functions that can be tested without spinning up Compose. The unification (commit 8821852) replaced the previous `DefaulterEditDialog` + `DefaulterAskDialog` pair, removing ~600 lines of duplicated month-bar / regression-confirm code.

**Edit persistence across config change + process death:** `LotReviewEditsSaver` encodes the in-progress `Map<Long, List<MonthYear>>` as a compact `rowId=YYYY-MM,YYYY-MM;...` string (~30 bytes per row; 50-row LOT stays under 2KB so Bundle truncation is impossible). Malformed segments are silently skipped on restore — a Bundle corruption never crashes the editor.

Running rupee total of the current LOT displayed below the existing 4-stat row (`Current LOT / In LOT / Saved LOTs / Total RD`) at the top of the scanner viewport.

**Visual contract:**
- Full-width chip, `RoundedCornerShape(12.dp)`, dark-overlay background (`accent.copy(alpha = 0.22f)`).
- **Mint** (`AccentMint`) when ALL of these hold: every scanned account has a profile with positive `monthlyAmount` AND `verifiedRupees <= LOT_TOTAL_LIMIT_RUPEES`. Chip reads `₹X`.
- **Coral** (`AccentCoral`) when EITHER condition fails: any row is unverified OR verified total is over the cap. Chip reads `₹X` (verified) or `₹X · N unverified` (when unverified > 0).
- 8dp accent dot prefix + locale-aware grouped rupee text (`NumberFormat.getNumberInstance(Locale.getDefault())` — Indian grouping renders `20,000` correctly).
- `AnimatedVisibility(fadeIn() + expandVertically())` / `(fadeOut() + shrinkVertically())` on `LiveLotTotal.hasContent` transitions.
- Hidden when `currentLotNumbers.isEmpty()` — clean pre-scan view.

**Compute** (`liveLotTotal: derivedStateOf`):
```kotlin
val verified = currentLotNumbers.sumOf { rdNumber ->
    val amount = lotAmountCache[rdNumber]
    if (amount != null && amount > 0) amount else 0
}
val unverified = currentLotNumbers.count { rdNumber ->
    lotAmountCache[rdNumber].let { it == null || it <= 0 }
}
```

`monthsPaid` is always 1 at scan time (defaulter counts are picked later on `LotReviewScreen`), so the scan-time formula reduces to `Σ monthlyAmount` for verified rows.

**Cache** (`lotAmountCache: SnapshotStateMap<String, Int?>`):
- Populated by `LaunchedEffect(currentLotNumbers.size)` (NOT `.toList()` — `.toList()` allocates a new instance every recompose and would re-fire the effect on every frame; QC-F CRITICAL caught this).
- Each new scan triggers a `rdAccountDao.findByRdNumber(rdNumber)?.monthlyAmount` lookup; missing entries stored as null and surface as 'unverified'.
- Cache survives across LOTs in the same session (re-scanning a known customer hits the cache immediately, no red-flash).
- **Known limitation:** cache is not invalidated on `Lifecycle.onResume` after the operator navigates to `AddAccountsScreen` to add a profile and returns. The chip continues to show the row as unverified until LOT is finished/rescanned. `LotReviewScreen` re-resolves fresh on entry. Tracked as low-priority follow-up.

**Why same line as the existing 4-stat row + AnimatedVisibility:** Compact during empty LOTs (4 stats only, no chip clutter), but instantly informative the moment the operator scans the first RD. Per locked Q2 ("live running total = yes") + the user spec ("show the total live in a top while scanning of current lot only").

---

## 16. Portal architecture

### Cross-system business-rule mirror (round 5)

The portal's edit surfaces MUST mirror every business rule the phone enforces, because a portal write that bypasses a rule lands a row that the phone then refuses to edit (the phone's UI blocks Confirm whenever the row would violate the rule, regardless of which direction the operator pushes). Three rules are mirrored as of round 5:

1. **Per-LOT ₹20,000 cap** — `EditDefaulterDialog` reads `fetchLotTotalsExcluding(lotId, excludeRdId)` on mount + `fetchAccountForRdNumber(rdNumber)` for this row's `monthly_amount`, then live-computes `pendingVerifiedRupees = restOfLotVerified + ownMonthlyAmount × monthsPaid`. Save disables when `pendingVerifiedRupees > 20000`. Live total chip + unverified-rows hint matches the phone's `LiveLotTotal` semantic so the owner sees the same "real total may be higher" floor when unverified rows exist. Spec §15.5.12 + D24.

2. **`last_paid_through` is phone-derived only** — Portal's `updateAccount` never includes `last_paid_through` in the update payload (D22). The field is monotonic-advanced by the phone via `updateLastPaidThroughMonotonic` and explicitly-set via `setLastPaidThroughExplicit` (D23). Portal touching it would race the phone's monotonic invariant.

3. **`last_editor_device_id = null` on every portal write** — Phone's `mergeRdNumbers` interprets `null` as "Portal" for attribution badges. Portal writes explicitly set this field to NULL, never inheriting the row's prior `last_editor_device_id`.

### Realtime subscription server-side filter (round 5)

Portal's `useRealtimeSync` subscribes to each table with `filter: \`owner_id=eq.${userId}\`` so Supabase filters BEFORE delivery. Without the filter, the server would broadcast to every connected portal and RLS would filter client-side AFTER the row crossed the wire — bandwidth waste + realtime quota burn proportional to concurrent-owner count. Spec §14 mandates server-side filtering on phone-side; portal must mirror.

Reconnect handling: supabase-js auto-reconnects the WebSocket on laptop wake / network back, but `postgres_changes` has no backfill for events that fired during the disconnect window. Portal listens to `window.online` + `document.visibilitychange` and invalidates every query on reconnect; phones that pushed during the gap are caught on the very next pull.

### Tech stack

- **Vite + React 18 + TypeScript**, strict mode.
- **@supabase/supabase-js v2** for data + realtime.
- **@tanstack/react-query** for cache + query lifecycle.
- **Tailwind CSS** + a small set of components (custom; we keep it simple).
- **react-router-dom v6** for routing.
- **xlsx** library for in-browser XLSX export (so we don't need a backend).
- Hosted on **Cloudflare Pages** via `wrangler pages deploy`.

### Pages

| Route | Purpose |
|---|---|
| `/login` | Email + password form, redirects to `/sessions` on success. |
| `/sessions` | List of finalized sessions. Filters: date range, device, operator. Sort: end_time desc by default. Click a row → `/sessions/:id`. |
| `/sessions/:id` | Detail view. LOTs as a list of cards, each expandable to show RD numbers + defaulter chips. "Edit defaulter months" button per row → modal with same picker UX as the app. |
| `/search` | Free-text search by RD number. Returns the session(s) that number appears in. |
| `/accounts` | **v8** — Account profiles list. Sortable table (Name / RD Number / Monthly amount / Paid till / Source / Actions). Edit dialog, Mark Inactive / Delete / Reactivate, and bulk CSV upload (Import CSV button). Full contract in §17. |
| `/stats` | Aggregate dashboard. Total scans per day (line chart), defaulter rate over time, top operators by scan count, etc. |
| `/devices` | Read-only list of registered phones with last_seen_at. |
| `/settings` | Sign-out, profile name. (Minimal in v1.) |

### Realtime in the portal

Same approach: subscribe to `scan_sessions`, `scan_lots`, `rd_numbers`, `devices`, and `rd_accounts` channels (5 total — the 5th joins in v8 for the Accounts list). On any change, invalidate the relevant TanStack Query cache key (`['sessions']`, `['accounts']`, etc.), causing a re-fetch and rerender. Within ~1s of a phone finalizing a session or creating an account, the portal's corresponding list re-renders with the new entry at the top.

### Auth

Email + password. Supabase Auth handles everything. On sign-in we store the session in `localStorage` (Supabase SDK default). Auto-refresh on expiry.

### Edit defaulter months from portal

This is the only **write** operation the portal performs on data. Implementation:

1. User clicks "Edit defaulters" on a LOT.
2. Modal opens with the same conceptual UX as the Android app: month chips per row, year picker for swaps.
3. On save, the portal does `supabase.from('rd_numbers').update({months_paid, months_list, updated_at: now()}).eq('id', rdNumberId)`.
4. The change triggers a Realtime broadcast which the phones receive, pulling the row down via §11.

Conflict scenario (theoretical): operator just edited the same row offline 2 seconds before the portal save. Whichever has the later `updated_at` wins. Owner doesn't see the silent loss; phone sees it on next pull and overwrites local with the now-canonical remote (which may be the portal's edit). Documented in §11.

### Hosting / deploy

```bash
# from portal/ directory
npm run build           # produces dist/
wrangler pages deploy dist --project-name=rd-scanner-portal
```

DNS via Cloudflare (we'll use a subdomain we own).

---

## 17. Account profiles (rd_accounts)

### Why this exists

An RD number is just a string. A scanned RD number on its own does not tell the operator *whose* book this is, *how much* they pay per month, or *which month they last paid through*. The pre-v8 workflow recovered that context by hand (operator reads the printed name, recalls the denomination), which broke at scale: typos, forgotten denominations, "did I already collect from him this month?" become real problems above ~50 accounts.

The `rd_accounts` table makes the account a **first-class entity** with a profile (name + monthly amount + paid-till state) that lives in both the phone and the portal, syncs across devices, and carries the auto-suggest signal into the defaulter dialog so the operator never has to remember "Sharma uncle is paid through August."

### Entity model

A single row per `(owner_id, rd_number)`. The natural composite PK is intentional: there is no need for a synthetic `id uuid` — the RD number string is globally unique within an owner's book (enforced by the phone-side regex `^\d{9,15}$` + the DB composite PK).

| Field | Type | Why |
|---|---|---|
| `rd_number` | text, PK part | The visible identity. Stable across the account's life. |
| `owner_id` | uuid, PK part, FK auth.users | RLS scoping; cascade delete on owner removal. |
| `name` | text, NOT NULL | The account holder's name as the operator entered it (or as CSV upload provided). |
| `monthly_amount` | int, NOT NULL, CHECK > 0 | Rupees per month. Drives caption on bulk-QR PDFs. |
| `last_paid_through` | text, nullable, format `YYYY-MM` | Most recent month for which payment was recorded. **Phone-derived only** — never editable in the portal. NULL = never paid. |
| `source` | text, NOT NULL, CHECK IN (`MANUAL`, `CSV`) | Where the account profile originated. CSV rows lock the edit affordance on the phone (Snackbar: "This account can only be edited from the portal"). |
| `is_active` | boolean, NOT NULL, default true | Soft state. Inactive accounts hide from the default Accounts list, do not block new-account creation by themselves, and **auto-reactivate on scan**. |
| `account_opened_date` | date, nullable | Schema-only in v8 — no UI surface yet. Reserved for the future "account aging" report. |
| `account_closing_date` | date, nullable | Schema-only in v8 — independent of `is_active`. "I marked it inactive" ≠ "the bank closed the account." |
| `last_editor_device_id` | uuid, FK devices, nullable | Attribution. NULL = portal edit ("Portal" badge). Non-NULL = phone edit ("via Counter Phone"). |
| `created_at` | timestamptz, NOT NULL, default now() | |
| `updated_at` | timestamptz, NOT NULL, default now(), trigger-maintained | LWW basis. |
| `deleted_at` | timestamptz, nullable | Tombstone. NULL = live. Soft-delete only; hard-delete is admin-only via Supabase Studio. |

Cloud table DDL is appended to `cloud/schema.sql` as the "Schema patch v3" block (idempotent — safe to re-run after partial application). Indexes: `(owner_id, rd_number)` (the PK), `(owner_id, is_active)` for the Accounts list, and a `pg_trgm` GIN on `name` for the portal search box.

### Three distinct row states

The interplay of `is_active` and `deleted_at` defines three states with different visibility + reuse semantics:

| `is_active` | `deleted_at` | State | Visible? | RD number reusable for new account? | Reactivates on scan? |
|---|---|---|---|---|---|
| true | NULL | **Active** | Yes (default list) | No (DB unique constraint blocks) | n/a |
| false | NULL | **Mark Inactive** | Toggle "Show inactive" | No (composite PK blocks) | **Yes** |
| n/a | non-NULL | **Soft-deleted** | Never | **Yes** (after tombstone, a new MANUAL row may insert) | n/a |

This is the locked contract behind the two-path delete dialog: "Mark Inactive" is the recommended primary path because it preserves payment history and reactivates trivially; "Delete" is the secondary danger path that wipes the profile entirely and lets the operator reuse the RD number from scratch.

### Auto-reactivate-on-scan

In `RDScannerScreen.kt`, immediately after `rdNumberDao.insert(...)` on a successful scan, the phone runs:

```kotlin
val account = rdAccountDao.findByRdNumber(scannedNumber)
if (account != null && !account.isActive) {
    rdAccountDao.upsert(
        account.copy(
            isActive = true,
            syncStatus = SyncStatus.DIRTY,
            updatedAt = System.currentTimeMillis(),
        )
    )
    Toast.makeText(context, "Account reactivated: ${account.name}", LONG).show()
}
```

Soft-deleted rows are explicitly NOT reactivated (they're invisible to `findByRdNumber`; only `findByRdNumberIncludingDeleted` returns them, and that's only used by the CSV resurrect path).

### CSV bulk upload (portal-only)

The portal `/accounts` page exposes an "Import CSV" affordance — three-column strict header `name,rd_number,monthly_amount`. Validation rules:
- Header row is case-insensitive + whitespace-stripped (papaparse `transformHeader`).
- `rd_number` regex `^\d{9,15}$`.
- `monthly_amount` must be a positive integer (no floats, no zero).
- In-file dedupe: if the CSV has the same `rd_number` twice, the second occurrence is flagged as an error.
- Every imported row stamps `source = 'CSV'`, `is_active = true`, `last_editor_device_id = null`, and **`deleted_at = null`** — that last one is what resurrects a tombstoned row on re-import (mirrors phone `RdAccountDao.resurrectTombstone()`).

Per-row upsert (not array form) so each failure is reportable in the result toast (`Imported 47 · skipped 3 invalid · 1 failed`).

### Conflict resolution

LWW by `updated_at`, same as §11. Three project-specific clarifications:

1. **Portal CSV always wins.** The user's locked decision: a CSV upload's `updated_at` (server-stamped at upsert) is by definition newer than any prior phone edit on the same `rd_number`. The phone pulls the new name/amount on next sync.
2. **`last_paid_through` is monotonic-only on auto-scan push.** Enforced client-side at the phone DAO: `RdAccountDao.updateLastPaidThroughMonotonic` has a `WHERE lastPaidThrough IS NULL OR lastPaidThrough < :newMonth` guard, so an out-of-order replay can't push a regression. (Cloud-side `GREATEST(...)` enforcement was considered but rejected per decision D21 — client-side prevents the write entirely rather than silently discarding it, which is the safer pattern for a single-owner deployment.) When the phone finalizes a session and `markSessionForSync` recomputes the holder's latest paid month, only the strictly-greater value reaches the cloud upsert. **Operator-explicit edits via `setLastPaidThroughExplicit` or `clearLastPaidThrough` bypass this guard after a confirm modal — see D23.**
3. **The portal NEVER edits `last_paid_through`.** It's not in the edit dialog form, not in `updateAccount()` query, never sent. The field is a phone-derived signal only.

### Push order

Inside `SyncRepository.runPushLocked()`, accounts push **before** sessions:

```
dirty accounts → upsert /rd_accounts ...........(idempotent, owner+rd PK)
dirty sessions → upsert /scan_sessions ..........(needs accounts? no, but consistent ordering)
dirty lots → upsert /scan_lots
dirty rd_numbers → upsert /rd_numbers
```

Master-data first principle: even though there's no FK from sessions to accounts, the operator's mental model is "accounts exist, then I scan against them," and sync ordering should mirror that.

### Realtime channel

A fifth channel `realtime:public:rd_accounts:owner_id=eq.<me>` joins the existing four (devices, sessions, lots, rd_numbers). On the phone the handler runs a targeted pull → `mergeRdAccounts`. The portal handler invalidates the `['accounts']` TanStack Query key. No `RemoteEditNotice` is emitted on account merges — they're silent background sync; the cross-device "owner edited Session #47" notice pattern (§15.5) is only for session-level edits where the operator might be mid-scan.

### Defaulter dialog auto-suggest

When the operator opens the defaulter month picker for a freshly-scanned RD number, the dialog now:
1. Looks up `rdAccountDao.findByRdNumber(scannedNumber)`.
2. If `lastPaidThrough != null`, the month-picker block builds **backward** from `nextMonth(lastPaidThrough)` and shows a banner "Last paid: through Aug 2025" above the slider.
3. On save, if the operator's chosen `block_start_month > lastPaidThrough + 1`, a "Skip gap?" confirmation modal fires — guards against "I forgot September existed."

If the account doesn't exist (operator skipped the AddAccount flow), behavior falls back to the pre-v8 anchor logic — the dialog still works; the auto-suggest just doesn't fire.

### Open phase-2 deferrals

- **OCR thermal-print mode** for reading the dot-matrix RD book directly into a new account profile — researched but not built; lives in §24.
- **Account-aging report** using `account_opened_date` — schema-ready, UI deferred.

---

## 18. Migration plan (v5 → v6)

This is a schema migration on Android only. No data migration on the cloud side — the cloud database is empty until we first sync.

### Android-side: `MIGRATION_5_6`

Adds sync metadata columns to three tables, and a new `device_settings` table.

```sql
-- scan_sessions
ALTER TABLE scan_sessions ADD COLUMN cloudId TEXT DEFAULT NULL;
ALTER TABLE scan_sessions ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'LOCAL_ONLY';
ALTER TABLE scan_sessions ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0;
ALTER TABLE scan_sessions ADD COLUMN syncedAt INTEGER DEFAULT NULL;
ALTER TABLE scan_sessions ADD COLUMN lastSyncError TEXT DEFAULT NULL;
ALTER TABLE scan_sessions ADD COLUMN deviceCloudId TEXT DEFAULT NULL;
ALTER TABLE scan_sessions ADD COLUMN operatorName TEXT DEFAULT NULL;
ALTER TABLE scan_sessions ADD COLUMN deletedAt INTEGER DEFAULT NULL;

-- Backfill: existing finalized sessions become DIRTY (they need to be pushed)
UPDATE scan_sessions
SET syncStatus = 'DIRTY',
    updatedAt = COALESCE(endTime, startTime, strftime('%s','now')*1000)
WHERE isActive = 0;

-- scan_lots: same 5 columns
ALTER TABLE scan_lots ADD COLUMN cloudId TEXT DEFAULT NULL;
ALTER TABLE scan_lots ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'LOCAL_ONLY';
ALTER TABLE scan_lots ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0;
ALTER TABLE scan_lots ADD COLUMN syncedAt INTEGER DEFAULT NULL;
ALTER TABLE scan_lots ADD COLUMN lastSyncError TEXT DEFAULT NULL;
ALTER TABLE scan_lots ADD COLUMN deletedAt INTEGER DEFAULT NULL;

-- Backfill lots belonging to finalized sessions
UPDATE scan_lots
SET syncStatus = 'DIRTY',
    updatedAt = COALESCE(timestamp, strftime('%s','now')*1000)
WHERE sessionId IN (SELECT id FROM scan_sessions WHERE isActive = 0);

-- rd_numbers: same 5 columns
ALTER TABLE rd_numbers ADD COLUMN cloudId TEXT DEFAULT NULL;
ALTER TABLE rd_numbers ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'LOCAL_ONLY';
ALTER TABLE rd_numbers ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0;
ALTER TABLE rd_numbers ADD COLUMN syncedAt INTEGER DEFAULT NULL;
ALTER TABLE rd_numbers ADD COLUMN lastSyncError TEXT DEFAULT NULL;
ALTER TABLE rd_numbers ADD COLUMN deletedAt INTEGER DEFAULT NULL;

UPDATE rd_numbers
SET syncStatus = 'DIRTY',
    updatedAt = COALESCE(scannedAt, strftime('%s','now')*1000)
WHERE lotId IN (
    SELECT id FROM scan_lots WHERE sessionId IN (
        SELECT id FROM scan_sessions WHERE isActive = 0
    )
);

-- device_settings (single-row table)
CREATE TABLE device_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    deviceCloudId TEXT DEFAULT NULL,
    deviceName TEXT DEFAULT NULL,
    operatorName TEXT DEFAULT NULL,
    ownerId TEXT DEFAULT NULL,
    lastPulledAt INTEGER NOT NULL DEFAULT 0,
    lastPullErrorAt INTEGER DEFAULT NULL,
    lastPullError TEXT DEFAULT NULL
);

INSERT INTO device_settings (id) VALUES (1);
```

Wrap in `legacy_alter_table` guard if any of the new columns conflict with FK reshuffling (they shouldn't, since these are pure ADD COLUMNs, but verify against migration history).

### What the first launch on v6 does

1. User opens app post-upgrade.
2. AppDatabase migration runs (no user-visible disruption).
3. All existing finalized sessions are now `DIRTY`.
4. App goes to home as normal. Status pill shows "N pending sync."
5. User hasn't signed in yet — pill is purple with "Not signed in. Tap to sign in."
6. User taps → SignInScreen.
7. After sign-in + first-run setup → first push pushes all the historical data.
8. First pull retrieves any data from any other phone that's already on v6 and signed in (likely none on day 1).
9. Now everything is `SYNCED`.

### What about active sessions at migration time?

If the user upgrades while a session is `isActive=1` mid-scan, that session keeps its `LOCAL_ONLY` status. It will become `DIRTY` only when finalized — exactly as designed.

---

## 19. Phase-by-phase build plan

We split the work into 5 self-contained phases. Each phase ends with a working app at that level of capability. Phase boundaries are commit boundaries — after each phase you should have a green build, a clean tree, and tests-by-eyeball that the prior phase's behavior still works.

### Phase 1: Schema v6 + auth shell (2–3 days)

**Goal:** the database is sync-aware; the app has a sign-in screen but no sync logic yet.

Work items:
- 1.1 Add `cloudId`, `syncStatus`, `updatedAt`, `syncedAt`, `lastSyncError` to RdNumber, ScanLot, ScanSession entities. Add `deletedAt`, `deviceCloudId`, `operatorName` to ScanSession. Add `deletedAt` to ScanLot, RdNumber.
- 1.2 Create `device_settings` table + DAO.
- 1.3 Write MIGRATION_5_6 with backfill. Commit schema v6 JSON.
- 1.4 Add Supabase Kotlin SDK deps; add `INTERNET` / `ACCESS_NETWORK_STATE` permission; add buildConfig fields for project URL + anon key.
- 1.5 Build `CloudClient` singleton initialized in `QRScannerApp.onCreate()`. No actual calls yet; just wires up the SDK.
- 1.6 Build `SignInScreen` and `FirstRunSetupScreen`. Navigation: if `device_settings.ownerId` is null, show SignInScreen as root instead of HomeScreen.
- 1.7 Sign-in flow saves session to encrypted prefs (Supabase SDK default behavior).
- 1.8 FirstRunSetupScreen captures device name + operator name. Saves to `device_settings`. Generates fresh `deviceCloudId` (UUID). Inserts a `devices` row in cloud (this is the **first** real cloud call).
- 1.9 SettingsScreen (basic): show signed-in email, sign-out button, "switch operator" link.

**Out of scope this phase:** any pushing/pulling of session data, realtime, work scheduling. Just identity + setup.

### Phase 2: Push-only sync (3–4 days)

**Goal:** finalized sessions automatically upload to cloud. No pull yet, no portal.

Work items:
- 2.1 Add `SyncRepository` skeleton with `runPush(): Result`.
- 2.2 Implement DTO mappers (entity ↔ DTO) for Session, Lot, RdNumber.
- 2.3 Implement push logic per §8: gather all DIRTY rows, push in parent-first order, mark SYNCED on success.
- 2.4 Modify the existing "End Session" path (`finalizeSession` in RDScannerScreen) to mark the session + its children DIRTY and enqueue `SyncPushWorker`.
- 2.5 Modify the existing "save defaulter edits" paths (both scanner and detail) to mark the affected RD number rows DIRTY and enqueue `SyncPushWorker`.
- 2.6 Implement `SyncPushWorker` as a CoroutineWorker with NetworkType.CONNECTED constraint and exponential backoff.
- 2.7 Add a status pill to HomeScreen TopBar showing pending sync count (green/amber/red).
- 2.8 Wire `setActiveLotId(null)` cleanup to ALSO clear `deviceCloudId`/`operatorName` carry-over (sanity).
- 2.9 Display number assignment: replace local `getNextDisplayNumber()` with a call to the `next_display_number()` RPC. If we're offline, fall back to local assignment with a flag that says "tentative", and reconcile on first push.
- 2.10 Handle session deletion from history: when user deletes a synced session, set `deletedAt = now()` locally + push the PATCH.

**Acceptance:** after this phase, scanning a session on Phone A, finalizing it, then opening Supabase studio → see the session, its LOTs, all RD numbers, with the correct defaulter months.

### Phase 3: Pull + multi-device read (2–3 days)

**Goal:** Phone B can see what Phone A finalized.

Work items:
- 3.1 Implement `runPull(): Result` in SyncRepository. Per §11.
- 3.2 Build `SyncPullWorker` (CoroutineWorker).
- 3.3 Wire pull triggers: on app launch, on opening SessionHistory, every 5 minutes while foregrounded (a coroutine in `MainActivity.onStart` + cancellation in `onStop`).
- 3.4 Realtime channel subscription in `MainActivity` lifecycle. On any payload, enqueue a one-shot pull.
- 3.5 Merge logic per §11, including the silent-overwrite WARN log.
- 3.6 Resolve foreign-key dependencies on pull: if we receive an rd_number whose lot doesn't exist locally yet, the parent-first pull order ensures the lot was pulled first. Add a sanity check that gracefully retries on FK violations.
- 3.7 Tombstone handling on pull: if `deleted_at IS NOT NULL`, soft-delete locally + cascade.

**Acceptance:** Phone B logs in. Within ~5s, it sees Phone A's previously-finalized sessions in History. Open one — see all the LOTs and RD numbers. Edit a defaulter on Phone A; within ~5s the edit appears on Phone B.

### Phase 4: Portal v1 (3–4 days)

**Goal:** the owner can read everything (and edit defaulter months) from a browser.

Work items:
- 4.1 Bootstrap Vite + React + TS + Tailwind project under `/portal`.
- 4.2 Configure Supabase client; wire auth.
- 4.3 Build `/login`, `/sessions`, `/sessions/:id`, `/devices`, `/settings`.
- 4.4 Add filtering UI on `/sessions`: date range, device dropdown, operator dropdown.
- 4.5 Implement XLSX export on `/sessions/:id` using the `xlsx` library, matching the phone's export format byte-for-byte where possible.
- 4.6 Build `/search`: full-text on `rd_numbers.number` using Postgres `ilike` or pg_trgm.
- 4.7 Build `/stats`: simple dashboard with recharts or similar. Total scans per day (last 30), defaulter rate, scans per device.
- 4.8 Realtime invalidation: every Postgres change broadcasts → TanStack Query cache invalidation → automatic re-fetch and re-render.
- 4.9 Edit-defaulter modal on `/sessions/:id`. Match the Android UX as closely as web allows (chip strip, month picker).
- 4.10 Deploy to Cloudflare Pages via Wrangler.

**Acceptance:** owner opens `https://rd-scanner-portal.pages.dev` in any browser, logs in, sees all phones' data, drills into a session, exports XLSX, searches by RD number, edits defaulter months. Edit propagates back to phones.

### Phase 5: Hardening + observability (1–2 days)

**Goal:** the system is robust against real-world failures.

Work items:
- 5.1 Comprehensive error states in the status pill ("Network down", "Sign-in expired", etc.).
- 5.2 SettingsScreen → "Sync diagnostics" sub-screen: pending count by table, last successful push/pull timestamps, last error per table, "Force push" / "Force pull" buttons.
- 5.3 Crash + sync logging: route Android Log.WARN + Log.ERROR to a local rolling file (last 7 days). Hidden "View logs" button in Sync diagnostics.
- 5.4 Handle Supabase project paused state (free tier pauses after 1 week of inactivity). Auto-unpause is triggered by the first request, but it can take 30–60s. Status pill shows "Reconnecting..." during that.
- 5.5 Test the upgrade path on a real device with v5 data: scan 3 sessions on v5, install v6, sign in, verify all 3 sessions push correctly with right defaulter months.
- 5.6 Test "two phones, network flaky" scenarios manually.
- 5.7 README polish + this spec gets updated with anything we learned during build.

### Total: 12–18 focused days

Calendar: spread realistically over 3–4 weeks.

---

## 19.5 Continuous QC Cadence (mandatory at short intervals)

The defaulter feature shipped clean only because we ran 5 oracle review rounds (29 oracle invocations + 5 main-chat sweeps) with main-chat static verification between each. The same cadence applies here, formalized so an unattended overnight build can't skip it.

### The cycle

After **every meaningful unit of work** — defined below — the following sequence runs before moving to the next unit:

1. **Main-chat static sweep** (≤2 min, blocking):
   - Forward-reference scan for local fns inside `@Composable`.
   - Unused-import scan (with `getValue`/`setValue` false-positive guard for `by` delegates).
   - Same-package redundant-import scan.
   - Cross-file signature audit for any changed function (`grep -rn 'funName\b'` and verify every call site matches).
   - LSP diagnostics on every touched file (`lsp_diagnostics` tool).

2. **Oracle review fan-out** (parallel, 3–5 oracles, varying angles):
   - Oracle A: correctness of the unit itself (data model, state machine, idempotency).
   - Oracle B: regression check (does anything before this unit still work?).
   - Oracle C: adversarial / edge cases (race conditions, partial failures, weird input).
   - Oracle D (when UI changed): Compose hygiene, recomposition keys, motion tokens, color tokens.
   - Oracle E (when schema changed): migration safety, FK integrity, RLS policy correctness.

3. **Synthesize findings into a table** (severity-tagged), apply blockers + warnings, document accepted notes, push the fix commit.

4. **Visual QA** (when UI changed): load `/visual-qa` skill, capture screenshot evidence, get dual read-only verdict (design + functional integrity, visual fidelity + i18n precision). Required for any banner/notification/portal page.

### What counts as a "meaningful unit"

| Unit | QC required? |
|---|---|
| New DAO method | yes |
| New Room migration | yes |
| New Composable surface (screen, dialog, banner) | yes (+ visual QA) |
| New Worker (sync push, sync pull) | yes |
| Conflict resolution merge logic edit | yes |
| New cloud schema column / RLS policy | yes |
| New API call site / endpoint | yes |
| Bug fix touching > 1 file | yes |
| Typo / comment / KDoc only | no |
| Trivial rename via Edit replaceAll | no |
| Dependency version bump with no behavior change | no |

### Phase-boundary review (heavier)

At the end of each Phase (1 through 5), in addition to per-unit QC:

1. **Full oracle round** (5–6 oracles in parallel, same shape as our rounds 1–5):
   - Schema / data layer correctness
   - State machine / concurrency
   - UI / Compose hygiene
   - Exports / serialization
   - Regression vs prior phase
   - Edge cases / adversarial

2. **Main-chat sweep** synthesizing oracle findings against the spec contract.

3. **Acceptance criteria checklist** (§20) must be checked off with evidence (test output, screenshot, Supabase Studio screenshot, etc.) before declaring phase complete.

4. **Update spec** to reflect anything reality forced different from plan. Commit as `docs(spec): update §X for phase N divergence`.

### Why this is non-negotiable for overnight work

The cadence is the only mechanism that detects:
- Forward-reference bugs (Kotlin doesn't hoist local fns inside `@Composable`).
- FK cascade misconfigurations that compile but corrupt at runtime.
- Race conditions in the sync state machine that only fire under flaky-network conditions I can't easily reproduce.
- Visual regressions on the banner / notifications under both en and hi locales.

Without it, I can produce 12 days of code that looks done and isn't. With it, every commit pushed has been reviewed from 4–6 orthogonal angles and the next morning I can defend every line.

### Cadence guard rails

- **No unit may move to "complete"** without the per-unit QC table showing zero unaddressed blockers + warnings.
- **No phase may close** without the heavier review running cleanly.
- **If 2+ consecutive units produce blockers** in oracle review, pause the loop. Commit a `docs/STOPPED_AT.md` describing the pattern and switch to lower-priority work until owner reviews.
- **Notepad discipline**: every QC round logs `## Findings` with file:line refs and `## Learnings` with patterns to apply forward. Survives context loss across multi-day execution.

---

## 20. Acceptance criteria per phase

Concrete, testable. "Done" means each box is checked.

### Phase 1
- [ ] Fresh install: app opens to SignInScreen, scanner is unreachable until signed in.
- [ ] Upgrade install (from v5): existing finalized sessions are visible in History, but SignInScreen is shown before any sync attempt.
- [ ] Sign-in with correct credentials → FirstRunSetupScreen → HomeScreen. Repeat sign-in returns directly to HomeScreen.
- [ ] FirstRunSetupScreen creates a `devices` row in Supabase (verifiable in Supabase Studio).
- [ ] Sign-out clears `ownerId` in `device_settings` but leaves all other local data intact.
- [ ] Status pill on HomeScreen shows "Not signed in" → "All synced" after first run, even though no real syncing happens this phase.

### Phase 2
- [ ] Scan a fresh session, finish it → Supabase Studio shows the session, its LOTs, all RD numbers within ~10s.
- [ ] Status pill shows pending count during sync, returns to green after.
- [ ] Edit a defaulter month → cloud row updates within ~10s.
- [ ] Delete a session from History → cloud row gets `deleted_at` set.
- [ ] Turn off WiFi, scan a session, finish → status pill shows "3 pending." Turn WiFi back on → within 30s, pill returns to green and cloud has the data.
- [ ] `display_number` from cloud matches what the user sees in the app. After multiple sessions, numbers are monotonically increasing globally.

### Phase 3
- [ ] Phone A finalizes a session. Within 5s (Realtime), Phone B (foregrounded) shows it in History without manual refresh.
- [ ] Phone A backgrounded; Phone B foregrounded. Phone A finalizes a session via an automated script. Phone B's 5-min poll picks it up within 5 min.
- [ ] Edit a defaulter on Phone A. Phone B shows the new months within 5s.
- [ ] Phone A deletes a session. Phone B sees it gone within 5s.
- [ ] No FK violations during pull, even when LOT + RD numbers + Session arrive out of order.
- [ ] Silent-overwrite case (deliberate test): edit same row offline on both phones, reconnect both, verify the later-timestamp edit wins and the earlier one is logged but not surfaced.

### Phase 4
- [ ] Owner browser → login → `/sessions` shows all sessions, filters work.
- [ ] Drill into a session → see LOTs and RD numbers correctly. Defaulter chips show months.
- [ ] Export XLSX → file format byte-identical to phone export (ignoring timestamps).
- [ ] Search "1234567890" → returns the session(s) containing that number.
- [ ] Stats dashboard renders with sensible numbers.
- [ ] Edit defaulter from portal → within 5s, phones see the change.

### Phase 5
- [ ] All status pill states ("Not signed in", "All synced", "Pending N", "Syncing", "Error: <msg>", "Reconnecting…") visually verified.
- [ ] Diagnostics screen shows accurate counts.
- [ ] Force push from diagnostics works.
- [ ] Force pull from diagnostics works.
- [ ] Logs viewer shows recent sync activity.
- [ ] Two-week-pause case: stop pushing for >7 days, Supabase pauses, app handles the cold start gracefully (no crash, status shows "Reconnecting", succeeds eventually).

---

## 21. Runbook (setup from scratch)

A new engineer should be able to follow this and have a working system end-to-end.

### 20.1 Create Supabase project

1. Sign up at https://supabase.com (free).
2. Create new project. Region: closest to your operators (probably `ap-south-1` or `ap-southeast-1`).
3. Save the **Project URL** and **anon (public) key** somewhere. We'll need them.
4. Save the **service_role key** in a password manager. Never commit it. Used only for ad-hoc admin tasks.

### 20.2 Apply schema

In Supabase Studio → SQL Editor → run the contents of `cloud/schema.sql` (we'll commit this in Phase 2). The schema includes:
- Tables (§5).
- Triggers (§5).
- RPC functions (§5).
- RLS policies (§13).

### 20.3 Enable Realtime on tables

Supabase Studio → Database → Replication → toggle ON for `scan_sessions`, `scan_lots`, `rd_numbers`, `devices`.

### 20.4 Create the owner account

Option A (UI): Supabase Studio → Authentication → Add user → enter email + password.
Option B (SQL): `INSERT INTO auth.users (...)` (less convenient; use the UI).

### 20.5 Configure Android app

In the project root, create `local.properties` (gitignored) with:

```properties
SUPABASE_URL=https://YOUR_PROJECT_ID.supabase.co
SUPABASE_ANON_KEY=eyJ...
```

Build the APK as normal: `./gradlew assembleDebug`.

### 20.6 Configure Portal

Under `portal/`:

```bash
cp .env.example .env.local
# edit .env.local with VITE_SUPABASE_URL and VITE_SUPABASE_ANON_KEY
npm install
npm run dev      # local dev
npm run build    # production build into dist/
```

### 20.7 Deploy Portal

```bash
npx wrangler login
npx wrangler pages deploy dist --project-name=rd-scanner-portal
# or set up a connected Git deployment via the Cloudflare dashboard
```

DNS: optionally point a subdomain at the Pages project.

### 20.8 Verify end-to-end

1. Install the APK on a phone.
2. Sign in with the owner email/password.
3. First-run setup, name the phone, name yourself.
4. Scan a quick session.
5. Open the portal URL in a browser, sign in with the same creds.
6. See the session at the top of `/sessions`.

If any step fails, see §22.

---

## 22. Failure modes and recovery

A non-exhaustive but representative list. Add to this as we learn.

### F1. Phone is offline indefinitely

**Symptoms:** Status pill stuck on "N pending."
**Behavior:** WorkManager keeps retrying on network availability. No data loss. Data backs up locally.
**Recovery:** none needed; auto-resumes when network returns.

### F2. Supabase project paused (free tier inactivity)

**Symptoms:** First request after >7 days idle returns 503 or similar.
**Behavior:** Worker treats it as transient, retries. The act of any request unpauses the project; takes 30–60s.
**Recovery:** Status pill shows "Reconnecting"; succeeds on retry.

### F3. Auth token expired and refresh failed

**Symptoms:** All requests return 401.
**Behavior:** Worker exits with failure. Status pill shows "Sign-in expired. Tap to re-sign-in."
**Recovery:** User signs in again. Session data is not lost.

### F4. Server-side row was deleted but local has unsynced edits

**Symptoms:** Push of a DIRTY row to an id that has `deleted_at` set on cloud.
**Behavior:** PostgREST upsert with `Prefer: resolution=merge-duplicates` would resurrect the row, which is wrong. We need to check on push: if the cloud has `deleted_at != null` and our local doesn't, we treat as "remote deletion wins"; we drop our local edit and apply the tombstone.
**Recovery:** Logged as silent loss, same WARN as §11.

### F5. Schema migration fails on upgrade

**Symptoms:** App crashes on launch after install. (Should never happen if we test, but…)
**Behavior:** Crash. User has to reinstall.
**Recovery:** We must thoroughly test MIGRATION_5_6 on a v5 device before release. If it does fail in the wild, the recovery is uninstall+reinstall, which loses all v5 data. **This is why testing is non-negotiable.**

### F6. Two phones offline, both scan + finalize, both come back online

**Symptoms:** None — both push successfully in turn.
**Behavior:** `display_number` collision is prevented by server-side RPC under advisory lock. Each phone gets a different number.
**Recovery:** none needed.

### F7. Defaulter edit on phone races with portal edit

**Symptoms:** None user-visible.
**Behavior:** Per §11, the later `updated_at` wins. Logged.
**Recovery:** none. Owner reviews logs if curious.

### F8. Realtime channel disconnects

**Symptoms:** None visible.
**Behavior:** Channel auto-reconnects on next foreground or network availability. 5-min poll catches any missed updates.
**Recovery:** none needed.

### F9. Tampered or corrupted local data

**Symptoms:** Push fails with FK violation or CHECK constraint.
**Behavior:** Row marked `SYNC_ERROR`. Doesn't block other rows. Logged.
**Recovery:** Diagnostics → "Force push" doesn't help. Manual fix via Supabase Studio if owner needs to clean up. Local row can be deleted from history UI.

### F10. Portal user changes password

**Symptoms:** Phones' refresh tokens get invalidated next time they refresh.
**Behavior:** Auth refresh fails → re-sign-in required (F3 flow).

---

## 23. Cost and free-tier limits

A snapshot reality check. Numbers as of doc creation; subject to provider changes.

### Supabase free tier

| Resource | Limit | Our expected usage |
|---|---|---|
| Database size | 500 MB | ~5 MB per 100k RD scans. Lifetime headroom: years. |
| Egress | 5 GB / month | Each pull is ~10 KB; 3 phones × 288 pulls/day = ~26 MB/mo. Portal browsing ~negligible. **Comfortable.** |
| Storage | 1 GB | Unused in v1. |
| MAU | 50,000 | We have 1 (owner). |
| Realtime concurrent connections | 200 | We have 3-4 (phones + portal). |
| Realtime messages | 2M / month | Each row change broadcasts to ~3 subscribers. 1000 finalized sessions/mo × 3 broadcasts = 3000 messages. **Massive headroom.** |
| Edge function invocations | 500,000 / mo | Unused in v1 (we only have one RPC, called per session ~1000/mo). **Massive headroom.** |
| Inactivity pause | 7 days | Will hit only if you literally don't use the system for a week. Auto-resumes. |

### Cloudflare Pages free tier

| Resource | Limit | Our usage |
|---|---|---|
| Bandwidth | Unlimited | yes really |
| Builds | 500 / month | Negligible — we deploy maybe weekly. |
| Custom domains | Unlimited | We'll use one. |
| Concurrent requests | 100k req/min | We have 1 user. |

### What would push us over

- 10x scaling (20 phones, 20k sessions/mo): still inside free tier, comfortably.
- 100x scaling (200 phones): we'd start to brush against egress and Realtime message limits. At that point Supabase Pro ($25/mo) gives us 8 GB DB, 100 GB egress. Still cheap.

**Conclusion:** for the realistic operating range, free forever holds.

---

## 24. Open questions / deferred items

Items we've consciously left for later. If any of these become "I need this now" during the build, surface to the spec — don't unilaterally add.

| Q | Item | When to revisit |
|---|---|---|
| Q1 | Per-operator auth (each operator has their own login). | If you ever have >3 operators or need per-operator deletion. |
| Q2 | Edit RD numbers post-scan (correct typos in the scanned number itself). | If "delete + rescan" workflow becomes painful. |
| Q3 | Bulk export of multiple sessions at once on the portal. | When you find yourself selecting 5+ sessions and exporting one by one. |
| Q4 | Portal "force re-sync this session" button. | If we ever see desync bugs in practice. |
| Q5 | iOS app. | If you ever need to support iPhone operators. |
| Q6 | End-to-end encryption (client-side encryption). | If regulatory needs change. |
| Q7 | Self-host migration path. | If Supabase ever raises prices unexpectedly. |
| Q8 | Conflict resolution UI ("merge or pick winner"). | Only if real-world conflicts become non-zero. |
| Q9 | Server-side validation logic (Edge Functions). | If we ever find clients pushing bad data. |
| Q10 | Schedule push notifications to operators on portal-side edits. | If owner-edits-defaulter-then-operator-misses-it becomes a real issue. |
| Q11 | **OCR thermal-print mode** — read the dot-matrix RD book directly into a new account profile via the phone camera. Researched in v8, not built. See §17. | When the manual AddAccounts spreadsheet flow + CSV bulk upload become the operator pain point at >200 accounts. |
| Q12 | **Account-aging report** using `account_opened_date`. The field is schema-only in v8 (no UI). | When the owner needs a "show me everyone whose account is >5 years old" report — typically triggered by tax/regulatory closeout cycles. |

---

## 25. Glossary

- **Owner**: the human who creates the Supabase auth account. There is one per system.
- **Operator**: a human who uses the phone. Not an auth principal; identified by free-text name.
- **Device**: a physical phone. Has a stable `deviceCloudId` UUID and a user-friendly `deviceName`.
- **Session** ("scan session"): one continuous scanning period on one phone, ended by tapping "End Session." Contains 0..N LOTs.
- **LOT**: a batch of RD numbers scanned in one go within a session, ended by tapping "Finish LOT."
- **RD number**: a single scanned account number, with optional defaulter metadata (`monthsPaid`, `monthsList`).
- **RD account** (v8): a first-class profile for an RD number — `name`, `monthlyAmount`, `lastPaidThrough`, source (MANUAL/CSV), state (active/inactive/tombstoned). See §17.
- **Account source** (v8): `MANUAL` (added on the phone via AddAccounts spreadsheet) or `CSV` (added via the portal bulk upload). CSV rows lock the phone edit affordance.
- **Mark Inactive** (v8): the recommended close-out path — preserves payment history, hides from default list, auto-reactivates on scan. Distinct from soft-delete.
- **Tombstone resurrect** (v8): the CSV-reimport path that clears `deleted_at` on a soft-deleted row, making it visible again. Mirrors phone-side `RdAccountDao.resurrectTombstone()`.
- **`lastPaidThrough`** (v8): the most recent `YYYY-MM` for which the operator recorded payment on an account. Phone-derived only; monotonic-only on auto-scan push; **operator-explicit edits can regress after confirmation (D23)**; never editable in the portal.
- **Defaulter**: an RD number where `monthsPaid > 1`.
- **Active session**: a session locally where `is_active = true` and `end_time` is null. Device-private. Never in cloud.
- **Finalized session**: a session where `is_active = false`. Eligible to sync.
- **Tombstone**: a row with `deleted_at` set. Represents a deletion; preserved instead of hard-delete to propagate the deletion across devices.
- **DIRTY / SYNCING / SYNCED / SYNC_ERROR / SYNC_ABANDONED / LOCAL_ONLY**: see §6. The six states of a local row's sync lifecycle. SYNC_ABANDONED is the circuit-breaker terminal state (oracle R3/I6) — a row that's failed PUSH_ABANDON_THRESHOLD pushes is structurally unpushable (schema drift, FK we can't satisfy, CHECK violation) and gets parked here to break the retry loop; manual diagnostics or a code-version bump is the recovery path.
- **Push**: phone → cloud.
- **Pull**: cloud → phone.
- **Cloud ID**: the UUID that identifies a row across devices. Same row across all devices has the same cloudId. For `rd_accounts` the cloudId *is* the `rd_number` string (no separate UUID).
- **`updated_at`**: server-trigger-maintained timestamp used for conflict resolution. The newer one wins.
- **Silent overwrite / silent loser**: when a sync merge discards local changes because remote is newer. We log it but don't surface to user.
- **Status pill**: the small visual indicator on HomeScreen showing sync state.
- **LOT total** (v8.2): the sum `Σ (RdAccount.monthlyAmount × monthsPaid)` across every row in a single LOT. Subject to the ₹20,000 per-LOT cap (D24). The chip on the scanner (§15.5.13) and the confirm bar on LotReviewScreen (§15.5.12) display the *verified* component — rows without a profile are excluded from the sum and surfaced separately so the operator knows the figure is a floor, not the absolute total.
- **Actor label** (v8.1): the operator-prefer display name used by notifications, the in-app banner, and the sync history sheet: `operatorName ?? deviceName ?? "Another phone" / "Portal"`. Computed identically across `RecentChangesBanner.originLabel`, `SyncRepository.RemoteEditNotice.originLabel`, `SyncHistorySheet.resolveActorLabel`, and `SyncRepository.notifySessionSynced.actorLabel`. Single source of truth: the operator's name, with device name as fallback for un-named operators and "Portal" for portal-originated events.
- **Free tier**: the no-cost service tier of Supabase / Cloudflare Pages. We commit to operating within it.

---

## Sign-off

Before any code is written, this document must be approved by the owner (Sugam). Approval = explicit "go" message on the chat thread where this lives, OR a `chore(docs): approve CLOUD_SYNC_SPEC v1` commit by you to this repo.

The current state of approval: **APPROVED v1** by Sugam Agrawal in chat on the day this line was committed. Subsequent amendments must be explicit commits to this file with rationale in the message.

Once approved, this document is frozen except for in-flight ammendments during the build, which must be explicit commits to this file with rationale in the message. If a phase finishes and reality diverged from the spec, update the spec to reflect what shipped, then continue.
