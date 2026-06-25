# Cloud Schema Migration Playbook

> **Audience:** future-you, or any engineer who needs to change the cloud
> schema (`cloud/schema.sql`) without breaking the running phones and portal.
> **Status:** Lockstep policy; deviate at your peril.

This document answers ONE question:

> "I need to change the cloud schema. What's the safe order of operations
> so I don't break the phones that are already in the field?"

It is small on purpose. Read it end-to-end before every schema change.

---

## 1. The core risk

The phones and portal are **versioned independently** from the cloud:

- Old phones (still on a pre-update APK) keep pushing rows in their old DTO shape.
- The portal updates whenever you redeploy (within seconds of `npm run deploy`).
- The cloud updates whenever you paste a migration into Supabase Studio.

So at any given moment after a schema change, there are at least TWO concurrent client versions writing to the same cloud, and possibly three (old phones, new phones, new portal).

**Any deploy order that requires all three to be in sync at once is wrong.**

The playbook below avoids that by always making each side OPTIONAL until the
other catches up.

---

## 2. The three rules

### Rule 1 — Add before remove

Add new columns, indexes, constraints, tables BEFORE removing the old ones.
Run the system in the overlap state for at least one release cycle, then
remove the old artifact only after every phone is provably on the new APK.

### Rule 2 — Nullable / DEFAULT before NOT NULL

Every new column ships as `NULL`-allowed or with a server-side `DEFAULT`.
Tighten to `NOT NULL` only after the population code has fully rolled out.

### Rule 3 — Backwards-compatible reads

Phone pull-merge code MUST tolerate cloud rows that lack any column it
expects, treating absence as a safe default. Portal queries that fail
on `null` for a new column have the same blast radius as breaking the cloud.

If you can't honor these three rules for some change, the change needs
a hard "everyone updates first" rollout — see §6 below.

---

## 3. The seven-step rollout pattern

For ANY schema change (add column, add index, add table, add constraint,
add trigger, drop column, etc.), follow these seven steps in this order:

### Step 1 — Update `cloud/schema.sql` canonically

Edit the canonical schema file to reflect the FINAL state (after the
migration completes). This is what a fresh Supabase project would
bootstrap from. Every CREATE uses `IF NOT EXISTS`, every column uses
`alter table ... add column if not exists` for idempotence.

### Step 2 — Write a standalone migration file

Create `cloud/migrations/vN_<description>.sql`, where N is incremented
from the last migration. The migration:

- Wraps everything in a single `begin; ... commit;`.
- Uses `IF NOT EXISTS` / `DROP IF EXISTS` so re-running is safe.
- Has commented-out sanity-check queries at the bottom that confirm the
  change landed (`SELECT FROM pg_indexes ...`, etc.).
- Documents in a header comment WHY this migration exists (which oracle
  finding, which spec section, which user request).

### Step 3 — Update the phone push DTOs + mappers

Modify the Kotlin code that serializes Room rows to cloud DTOs:

- New column on phone-pushed table → add to `cloud/mappers/<Entity>Mapper.kt`
  and the corresponding `cloud/dto/<Entity>Dto.kt`.
- The serializer must produce the new field as `null` if the local Room
  column hasn't been populated yet (pre-room-migration data).

### Step 4 — Update the phone pull-merge code

Modify `SyncRepository.merge<Entity>s()` to read the new column from
the inbound cloud DTO. Tolerate `null` cleanly. If the column drives
business logic, gate that logic on `!= null`.

### Step 5 — Update the Room schema (phone-local)

Bump `AppDatabase.version` and add the corresponding `MIGRATION_X_Y`
that adds the matching local column. Update the Room entity. Register
the migration in `.addMigrations(...)`.

The local Room column is what stores the new field once the phone
starts receiving it from cloud. Without this step the merge code would
have nowhere to write to.

### Step 6 — Update the portal

If the portal renders or writes the new field:

- Add to `portal/src/types/db.ts` (TypeScript shape).
- Add the SELECT in the relevant `portal/src/lib/queries.ts` function.
- Add the render or input UI.

If the portal doesn't touch the new field, skip this step. The portal
tolerates cloud rows with extra columns it doesn't know about (PostgREST
returns them, React just ignores them).

### Step 7 — Deploy in this order

This is the LOAD-BEARING order:

1. **Cloud first.** Paste the migration into Supabase Studio. New
   columns exist as NULL on all existing rows; queries that don't
   reference them are unaffected; old phones pushing the old DTO
   shape continue to work because the new columns just stay NULL.

2. **Verify with sanity checks.** Run the commented-out queries at
   the bottom of the migration file. Confirm every new index,
   constraint, trigger, column exists.

3. **Portal second.** `cd portal && npm run build && npm run deploy`.
   The portal now renders/writes the new field. Old phones still
   pushing old shape continue to work (they just don't see the new
   field in their pulls until they update too).

4. **Phone APK last.** Build, sign, distribute. As phones update they
   start writing the new field. Phones still on the old APK keep
   writing the old shape; cloud accepts both because new columns are
   nullable.

5. **(Optional) Tighten constraints once everyone's updated.** After
   you've confirmed every phone in the field is on the new APK
   (Devices page `app_version` column shows this), you can run a
   follow-up migration that makes nullable columns `NOT NULL`, drops
   defunct old columns, etc. This is a separate migration file, not
   bundled with the original.

---

## 4. Worked example — Adding `whatsapp_number` to rd_accounts

Walking through a realistic future scenario to make the pattern concrete.

**The goal:** add `whatsapp_number` (optional TEXT) to `rd_accounts` so
the portal can render a "WhatsApp" link next to each customer.

### Step 1 — Edit `cloud/schema.sql`

Add the column to the `create table public.rd_accounts (...)` block and
an idempotent `alter table public.rd_accounts add column if not exists`
right after so re-bootstraps and existing-project applies both work.

### Step 2 — Write `cloud/migrations/v11_rd_accounts_whatsapp.sql`

```sql
begin;
alter table public.rd_accounts
    add column if not exists whatsapp_number text;
-- No CHECK constraint yet. Validate format in app code; tighten later
-- if needed (would require step-5 follow-up migration).
commit;
```

### Step 3 — Update phone DTO + mapper

`RdAccountDto.kt`: add `val whatsappNumber: String? = null` to the
`@Serializable` data class.
`RdAccountMapper.kt`: `toDto()` reads `entity.whatsappNumber`,
`toEntity()` writes `dto.whatsappNumber`. Both tolerate null.

### Step 4 — Update phone pull merge

`SyncRepository.mergeRdAccounts()`: pass `whatsappNumber = dto.whatsappNumber`
to `rdAccountDao.mergeFromCloud(...)`. Update the DAO query to write the
new column.

### Step 5 — Update phone Room schema (v10 → v11)

`AppDatabase.version = 11`. Add `MIGRATION_10_11`:

```kotlin
private val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rd_accounts ADD COLUMN whatsappNumber TEXT")
    }
}
```

Register it in `.addMigrations(...)`. Update `RdAccount` entity:
`val whatsappNumber: String? = null`.

### Step 6 — Update portal

- `portal/src/types/db.ts`: `whatsapp_number: string | null` on `RdAccountRow`.
- `portal/src/lib/queries.ts`: include `whatsapp_number` in the SELECT of
  `fetchAccounts()` (or use `.select('*')` which already does).
- `portal/src/pages/Accounts.tsx`: render a `<a href="https://wa.me/...">`
  link when `account.whatsapp_number != null`.
- `portal/src/components/AccountEditDialog.tsx`: optional `<input>` field
  + add to `updateAccount` payload.

### Step 7 — Deploy in order

1. Run `v11_rd_accounts_whatsapp.sql` in Supabase Studio. Phones still
   on the old APK keep writing without the field. Portal still on old
   bundle doesn't reference it. Nothing breaks.
2. `cd portal && npm run build && npm run deploy`. Portal now renders
   the WhatsApp link when present, but old data is all NULL so the
   link doesn't render yet. Account edit dialog now has the field;
   any edits start populating it. Old phones still tolerate cloud
   rows with the new field (their pull-merge ignores it).
3. Build + distribute the new phone APK whenever ready. As phones
   update, they start writing `whatsappNumber` too.

**Total breakage window across all three deploys: zero.** Each side is
optional until the previous catches up.

---

## 5. The forbidden patterns

Some patterns LOOK convenient but bring the system down. Never do these:

### Forbidden 1 — Adding `NOT NULL` without `DEFAULT`

```sql
-- DON'T:
alter table public.rd_accounts add column whatsapp_number text not null;
```

This will fail outright if any row already exists (they all have NULL).
Even if the table is empty, the moment old phones push without the
field, the INSERT will fail with a 400 and sync breaks for that phone.

**Correct:** ship nullable first, populate, then tighten later if needed.

### Forbidden 2 — Renaming a column

```sql
-- DON'T:
alter table public.rd_accounts rename column whatsapp to whatsapp_number;
```

Old phones still writing `whatsapp` will start getting 400 errors.

**Correct:** add new column, dual-write from new APK code, deprecate old
column after everyone updates, drop old column in a later migration.

### Forbidden 3 — Dropping a column the portal still uses

If the portal's TypeScript types still reference a column you DROP,
every portal query that does `SELECT *` will work but every render
that accesses `row.<old_column>` will be `undefined`. Possibly
silently. Possibly crash.

**Correct:** remove from portal first, deploy portal, THEN drop column.

### Forbidden 4 — Adding a constraint to existing data

```sql
-- DON'T:
alter table public.rd_accounts add constraint chk_monthly_amount
    check (monthly_amount between 100 and 50000);
```

If ANY existing row violates the constraint, this fails outright. Even
if all current rows are compliant, an old phone pushing a non-compliant
value will start getting 400 errors.

**Correct:** survey existing data first (`SELECT ... WHERE NOT (...)` to
find offenders), correct them, deploy new app code that enforces the
constraint client-side, wait for rollout, THEN add the server-side
constraint as belt-and-suspenders.

### Forbidden 5 — Dropping or rebuilding an FK while data is live

The seven-step pattern assumes additive changes. If you need to change
an FK's `ON DELETE` action (like the round-5 CASCADE → RESTRICT
change), do it during a known-quiet window (no active phone syncs):

1. Verify no rows currently have `deleted_at` in flight (`SELECT ...
   WHERE deleted_at IS NOT NULL AND deleted_at > now() - interval '1
   hour'`).
2. Run the FK rebuild in a single transaction.
3. Confirm with sanity-check query.

The round-5 migration did this safely because the project was new
enough that no production traffic existed. For a live system,
schedule a brief sync pause first (cancel all WorkManager workers
via a portal "maintenance" toggle would be ideal — out of scope
for v1).

---

## 6. Emergency hard-rollout pattern

Sometimes the seven-step pattern can't honor backwards compatibility
because the change is structural (e.g., splitting a column into two,
or changing the type of an existing column from TEXT to JSONB).

In that case the rollout has to be:

1. **Communicate first.** Tell every operator "the app will need an
   update by date X; sync will pause for everyone older than version Y."
2. **Phone APK first, NOT cloud.** Build the new APK that knows how to
   read/write both old AND new formats. Distribute widely. Wait at
   least one week + verify via portal Devices `app_version` column
   that 100% of active phones are on the new APK.
3. **Cloud migration second.** Now run the structural change. Old APKs
   in the field will break — that's why step 1 existed.
4. **Phone APK third (optional cleanup).** Once the cloud is on the new
   shape, ship a follow-up APK that drops the dual-mode read/write and
   only handles the new shape.

This is much slower and riskier than the seven-step pattern. Avoid it.

---

## 7. Pre-flight checklist

Before pasting ANY migration into Supabase Studio:

- [ ] `cloud/schema.sql` updated to reflect the final state.
- [ ] `cloud/migrations/vN_*.sql` written, idempotent, with sanity
      checks at the bottom.
- [ ] Phone DTO + mapper updated (Step 3).
- [ ] Phone pull-merge updated (Step 4).
- [ ] Phone Room migration written + registered (Step 5).
- [ ] Phone code compiles + tests pass + lint clean.
- [ ] Portal types + queries updated if applicable (Step 6).
- [ ] Portal builds + lints clean.
- [ ] Nothing in this migration falls under §5 Forbidden Patterns.
- [ ] If it does, you're using §6 Emergency Hard-Rollout consciously.
- [ ] You have a rollback plan if the sanity checks fail.

If any box isn't ticked, stop and address it before deploying.

---

## 8. Rollback strategy

Most migrations in this playbook are additive (new column, new index,
new trigger) and therefore have a trivial rollback: `DROP <thing>`. The
column or index didn't exist before; dropping it returns the schema to
the prior state.

Non-additive rollbacks (FK type changes, table renames, constraint adds)
are harder. For those:

- **If caught before any client wrote to the new shape:** drop the new
  thing, redeploy the previous portal bundle, ship the previous phone
  APK if you'd already pushed an incompatible one. Then re-plan.
- **If caught after clients started writing:** you may have data that
  ONLY makes sense in the new shape. Rollback may mean accepting
  data loss for that window. This is why pre-flight matters.

In practice: keep migrations additive (Rule 1) and rollback is trivial.

---

## 9. Cross-references

- `cloud/schema.sql` — canonical schema (final state after every
  migration).
- `cloud/migrations/` — every applied migration as a standalone
  paste-ready SQL file. Numbered in apply order.
- `docs/CLOUD_SYNC_SPEC.md` §5 — cloud data model spec.
- `docs/CLOUD_SYNC_SPEC.md` §11 — conflict resolution + clock-skew
  clamp invariant a migration must preserve.
- `docs/CLOUD_SYNC_SPEC.md` §18 — historical phone-side v5→v6 migration
  example (older than this playbook; serves as reference for the
  Room-side pattern).

---

## 10. Decision log for future schema changes

When you do a future migration, append a one-line entry here so the
next maintainer (or future-you) can see the historical pattern:

| Date | Migration | What changed | Notes |
|------|-----------|--------------|-------|
| 2025-06 | `v10_round5_hardening.sql` | FK CASCADE → RESTRICT, +`deleted_at` columns, indexes, clamp triggers, UNIQUE constraint, INSERT RLS tightening | Round-5 7-agent QC sweep findings |
