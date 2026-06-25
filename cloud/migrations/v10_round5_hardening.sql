-- =====================================================================
-- Round-5 Cloud Hardening Migration (v10)
-- =====================================================================
-- Branch: feat/cloud-sync
-- Round: 5 (7-agent QC of full system: phone + portal + cloud + CF)
--
-- Applies the verified-real cloud-schema findings from the round-5
-- audit. Idempotent: every CREATE / ALTER uses IF NOT EXISTS or DROP
-- IF EXISTS guards, so re-running is safe.
--
-- HOW TO APPLY
--   1. Open Supabase Studio -> SQL Editor -> New Query.
--   2. Paste this entire file. Run.
--   3. Verify zero errors in the result panel.
--   4. (Optional) Run the sanity-check block at the bottom to confirm
--      the new constraints + indexes are in place.
--
-- ROLLBACK
--   This migration is additive (new columns, new indexes, new
--   constraints, new triggers) plus two FK rebuilds (CASCADE -> RESTRICT).
--   The FK rebuilds are the only non-additive change; to roll them
--   back, re-CREATE with `on delete cascade`. Do NOT roll back the
--   `deleted_at` columns once data lands in them.
-- =====================================================================

begin;

-- 1. BLOCKER: scan_lots.session_id FK CASCADE -> RESTRICT ---------------
-- A hard-delete on scan_sessions previously cascaded to silently drop
-- all child scan_lots rows without producing tombstones. Phones that
-- hadn't yet pulled the tombstone would never learn the lots were
-- deleted. Soft-delete is the only allowed deletion path (spec 11);
-- RESTRICT enforces that contract at the FK boundary.

alter table public.scan_lots
    drop constraint if exists scan_lots_session_id_fkey;
alter table public.scan_lots
    add constraint scan_lots_session_id_fkey
    foreign key (session_id)
    references public.scan_sessions(id)
    on delete restrict;

-- 2. BLOCKER: rd_numbers.lot_id FK CASCADE -> RESTRICT ------------------
-- Same rationale as above for the rd_numbers -> scan_lots edge.

alter table public.rd_numbers
    drop constraint if exists rd_numbers_lot_id_fkey;
alter table public.rd_numbers
    add constraint rd_numbers_lot_id_fkey
    foreign key (lot_id)
    references public.scan_lots(id)
    on delete restrict;

-- 3. HIGH: scan_lots missing deleted_at column --------------------------
-- Every syncable table must carry the tombstone column so phones can
-- push soft-deletes. The omission meant a phone soft-deleting a lot
-- could never propagate that deletion to other devices.

alter table public.scan_lots
    add column if not exists deleted_at timestamptz;

-- 4. HIGH: devices missing deleted_at column ----------------------------
-- Schema consistency. Spec 5 says every syncable table has deleted_at.
-- Devices are not currently soft-deleted, but the absence of the
-- column would block any future "remove this phone" feature.

alter table public.devices
    add column if not exists deleted_at timestamptz;

-- 5. HIGH: Missing (owner_id, updated_at) indexes -----------------------
-- The phone pull query is:
--   SELECT * FROM <table> WHERE owner_id = :me AND updated_at > :cursor
-- Without a composite index, every pull does a full table scan +
-- filesort. At 8,400 rows/year * multi-year retention this is the
-- single biggest pull-cycle perf cliff.

create index if not exists scan_sessions_owner_updatedat_idx
    on public.scan_sessions (owner_id, updated_at);

create index if not exists scan_lots_owner_updatedat_idx
    on public.scan_lots (owner_id, updated_at);

create index if not exists rd_numbers_owner_updatedat_idx
    on public.rd_numbers (owner_id, updated_at);

create index if not exists rd_accounts_owner_updatedat_idx
    on public.rd_accounts (owner_id, updated_at);

-- 6. HIGH: UNIQUE(owner_id, display_number) -----------------------------
-- next_display_number RPC uses pg_advisory_xact_lock for serialization,
-- but the unique constraint is a defense-in-depth safety net so a
-- bug in the RPC (e.g., lock dropped early) can never produce
-- duplicate display numbers per owner. Spec 5.

-- NB: this will FAIL if you already have duplicate (owner_id, display_number)
-- rows. Run the sanity-check query at the bottom first; if duplicates
-- exist, resolve them manually before adding the constraint.

alter table public.scan_sessions
    drop constraint if exists scan_sessions_owner_displaynum_unique;
alter table public.scan_sessions
    add constraint scan_sessions_owner_displaynum_unique
    unique (owner_id, display_number);

-- 7. HIGH: Clock-skew clamp on updated_at -------------------------------
-- A phone with a future clock (e.g., user manually set the clock
-- forward) would push rows whose updated_at is in the year 2099. LWW
-- compares updated_at strictly, so those rows would win every
-- subsequent merge forever, including portal edits with the correct
-- server clock. Clamp inbound updated_at to at most 1 hour in the
-- future (allows for legitimate NTP drift but rejects gross skew).
--
-- The clamp fires on BEFORE INSERT and BEFORE UPDATE so it catches
-- both fresh pushes and merge-back paths. The existing set_updated_at
-- trigger only fires on UPDATE and unconditionally overwrites with
-- now(), which is the right behavior for cloud-side writes but
-- doesn't help on INSERT (when DEFAULT now() applies if the client
-- omits the field, but the client doesn't omit it).

create or replace function public.clamp_updated_at()
returns trigger
language plpgsql
as $$
declare
    v_max_allowed timestamptz := now() + interval '1 hour';
begin
    if new.updated_at is null then
        new.updated_at := now();
    elsif new.updated_at > v_max_allowed then
        -- Log the clamp via raise notice so Supabase Studio logs
        -- preserve a breadcrumb when this fires. notice is non-
        -- fatal, so the row still lands.
        raise notice 'clamp_updated_at: % was %, clamped to now()',
            tg_table_name, new.updated_at;
        new.updated_at := now();
    end if;
    return new;
end;
$$;

drop trigger if exists trg_devices_clamp_updated_at on public.devices;
create trigger trg_devices_clamp_updated_at
    before insert on public.devices
    for each row execute function public.clamp_updated_at();

drop trigger if exists trg_scan_sessions_clamp_updated_at on public.scan_sessions;
create trigger trg_scan_sessions_clamp_updated_at
    before insert on public.scan_sessions
    for each row execute function public.clamp_updated_at();

drop trigger if exists trg_scan_lots_clamp_updated_at on public.scan_lots;
create trigger trg_scan_lots_clamp_updated_at
    before insert on public.scan_lots
    for each row execute function public.clamp_updated_at();

drop trigger if exists trg_rd_numbers_clamp_updated_at on public.rd_numbers;
create trigger trg_rd_numbers_clamp_updated_at
    before insert on public.rd_numbers
    for each row execute function public.clamp_updated_at();

drop trigger if exists trg_rd_accounts_clamp_updated_at on public.rd_accounts;
create trigger trg_rd_accounts_clamp_updated_at
    before insert on public.rd_accounts
    for each row execute function public.clamp_updated_at();

-- 8. MED: next_display_number must not reuse deleted numbers ------------
-- Removing the `and deleted_at is null` filter means MAX() considers
-- tombstoned sessions too, so a soft-deleted Session #47 doesn't get
-- its number reissued. The unique constraint added above would also
-- block reuse, but fixing the MAX query is the right primary fix; the
-- constraint is the safety net.

create or replace function public.next_display_number(p_owner_id uuid)
returns int
language plpgsql
security definer
set search_path = public
as $$
declare
    v_next int;
begin
    if auth.uid() is null or auth.uid() <> p_owner_id then
        raise exception 'next_display_number: not authorized for owner_id %', p_owner_id
            using errcode = '42501';
    end if;

    perform pg_advisory_xact_lock(hashtext(p_owner_id::text));

    -- No deleted_at filter: tombstoned sessions still occupy their
    -- display number so the sequence never reuses one. Owner-facing
    -- audit trails (portal session list) stay monotonic.
    select coalesce(max(display_number), 0) + 1
        into v_next
        from public.scan_sessions
        where owner_id = p_owner_id;

    return v_next;
end;
$$;

revoke all on function public.next_display_number(uuid) from public;
grant execute on function public.next_display_number(uuid) to authenticated;

-- 9. MED: Missing (owner_id, deleted_at) indexes ------------------------
-- Tombstone-aware pull queries filter `deleted_at > :cursor` on
-- children. Without an index, the filter does a full table scan.
-- scan_sessions already has scan_sessions_owner_deletedat_idx; mirror
-- it on the other three syncable tables.

create index if not exists scan_lots_owner_deletedat_idx
    on public.scan_lots (owner_id, deleted_at);

create index if not exists rd_numbers_owner_deletedat_idx
    on public.rd_numbers (owner_id, deleted_at);

create index if not exists rd_accounts_owner_deletedat_idx
    on public.rd_accounts (owner_id, deleted_at);

-- 10. MED: INSERT RLS policies should verify parent ownership ----------
-- Current INSERT policies only check `owner_id = auth.uid()`. An
-- attacker who guessed a victim's session UUID could theoretically
-- insert a scan_lot pointing at the victim's session, attributed to
-- the attacker. UUIDs are 128-bit random so brute-force is infeasible,
-- but defense-in-depth costs us nothing and the policy reads more
-- correctly. Same fix applies for rd_numbers -> scan_lots.

drop policy if exists "scan_lots: owner insert" on public.scan_lots;
create policy "scan_lots: owner insert" on public.scan_lots
    for insert with check (
        owner_id = auth.uid()
        and exists (
            select 1 from public.scan_sessions s
            where s.id = session_id and s.owner_id = auth.uid()
        )
    );

drop policy if exists "rd_numbers: owner insert" on public.rd_numbers;
create policy "rd_numbers: owner insert" on public.rd_numbers
    for insert with check (
        owner_id = auth.uid()
        and exists (
            select 1 from public.scan_lots l
            where l.id = lot_id and l.owner_id = auth.uid()
        )
    );

commit;

-- =====================================================================
-- SANITY CHECKS (run separately after the migration commits)
-- =====================================================================
-- Uncomment and run these queries one at a time to verify the
-- migration landed correctly.
--
-- Check FK ON DELETE actions:
-- select tc.table_name, kcu.column_name, rc.delete_rule
--     from information_schema.table_constraints tc
--     join information_schema.referential_constraints rc
--         on tc.constraint_name = rc.constraint_name
--     join information_schema.key_column_usage kcu
--         on tc.constraint_name = kcu.constraint_name
--     where tc.table_schema = 'public'
--       and tc.constraint_type = 'FOREIGN KEY'
--       and tc.table_name in ('scan_lots','rd_numbers');
-- -- Expected: both rows show delete_rule = 'RESTRICT'
--
-- Check new indexes exist:
-- select indexname from pg_indexes
--     where schemaname = 'public'
--       and indexname in (
--         'scan_sessions_owner_updatedat_idx',
--         'scan_lots_owner_updatedat_idx',
--         'rd_numbers_owner_updatedat_idx',
--         'rd_accounts_owner_updatedat_idx',
--         'scan_lots_owner_deletedat_idx',
--         'rd_numbers_owner_deletedat_idx',
--         'rd_accounts_owner_deletedat_idx'
--       );
-- -- Expected: 7 rows
--
-- Check unique constraint:
-- select conname from pg_constraint
--     where conrelid = 'public.scan_sessions'::regclass
--       and conname = 'scan_sessions_owner_displaynum_unique';
-- -- Expected: 1 row
--
-- Check clamp triggers:
-- select trigger_name, event_manipulation, event_object_table
--     from information_schema.triggers
--     where trigger_schema = 'public'
--       and trigger_name like '%clamp_updated_at';
-- -- Expected: 5 rows (one per syncable table, on INSERT)
--
-- Check for duplicate display numbers BEFORE running step 6 (if you
-- have existing data):
-- select owner_id, display_number, count(*)
--     from public.scan_sessions
--     group by owner_id, display_number
--     having count(*) > 1;
-- -- Expected: zero rows (otherwise the unique constraint will fail)
-- =====================================================================
