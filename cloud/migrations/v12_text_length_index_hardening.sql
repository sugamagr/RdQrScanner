-- v12: text-length CHECK constraints + redundant index cleanup
--
-- TWO INDEPENDENT HARDENING ITEMS, BOTH OPTIONAL AT CURRENT SCALE
-- (2-5 phones / 1 portal owner / ~30 sessions per year / ~200 accounts
-- per month) but cheap, idempotent, and worth applying before any
-- public-facing release. Both surfaced by skeptical-veteran QC rounds:
--
--   1. TEXT-LENGTH CHECK CONSTRAINTS (R4 oracle bg_f9b79008 #15)
--      Five user-controlled text columns currently accept arbitrarily
--      long strings. At scale this is self-inflicted (1 owner, no
--      adversary), but a corrupt phone push or pasted UTF-8 garbage
--      could land a multi-MB row that bloats the realtime payload and
--      breaks the dashboard's PostgREST embed (Cloudflare Workers'
--      6 MB response cap, etc.). CHECK constraints catch this at the
--      DB boundary instead of letting it poison every downstream read.
--
--      Caps chosen to be 10-20x the realistic operator input so this
--      should never fire on a well-behaved phone:
--        devices.device_name            <=  100  (Samsung SM-A356E)
--        devices.last_sync_error        <= 4000  (truncated phone-side
--                                                 to 240, server cap is
--                                                 16x that headroom)
--        scan_sessions.operator_name    <=  100  (operator first name)
--        rd_accounts.name               <=  200  (Hindi names with
--                                                 honorifics + village)
--        rd_accounts.last_paid_through  <=    7  (YYYY-MM exactly)
--
--   2. REDUNDANT SINGLE-COLUMN owner_id INDEX CLEANUP (R6 oracle
--      bg_c7a90c24 #15)
--      Three single-column (owner_id) indexes are functionally subsumed
--      by the composite (owner_id, updated_at) indexes that the v10
--      hardening migration added (pull-cycle hot path). PostgreSQL's
--      planner uses the composite for both equality-only owner_id
--      lookups AND owner_id + updated_at range scans, so the singles
--      are pure dead weight on every INSERT/UPDATE.
--
--      Indexes to drop:
--        scan_lots_owner_idx
--        rd_numbers_owner_idx
--        rd_accounts_owner_idx
--
--      Subsumed-by composite indexes (already exist, verified):
--        scan_lots_owner_updatedat_idx     covers WHERE owner_id = ?
--        rd_numbers_owner_updatedat_idx    covers WHERE owner_id = ?
--        rd_accounts_owner_updatedat_idx   covers WHERE owner_id = ?
--
--      Micro-optimization at 8,400 writes/year; matters if/when the
--      app sees 10x growth. Keeps the schema lean. Free to drop now.
--
-- IDEMPOTENT: safe to re-run. ALTER TABLE ... ADD CONSTRAINT IF NOT
-- EXISTS guards the CHECK adds; DROP INDEX IF EXISTS guards the drops.
-- Paste verbatim into Supabase Studio SQL editor; the four sanity
-- checks at the bottom should all return the expected counts.
--
-- TRANSACTION WRAPPED: begin/commit brackets the DDL so a crash or
-- disconnect mid-migration rolls back partial state. Same rationale
-- as v11.

begin;

-- ---------------------------------------------------------------------
-- Part 1: text-length CHECK constraints
-- ---------------------------------------------------------------------
-- Each constraint is added IF NOT EXISTS via a do block so re-paste is
-- a no-op. NOT VALID is intentionally NOT used because the canonical
-- schema is small (5 phones max, ~5000 RD rows lifetime) so the
-- one-time validation scan is sub-second; if a future row violates the
-- cap we want to know immediately, not silently skip it.

do $$ begin
    if not exists (
        select 1 from pg_constraint
         where conname = 'devices_device_name_max_len'
    ) then
        alter table public.devices
            add constraint devices_device_name_max_len
            check (char_length(device_name) <= 100);
    end if;

    if not exists (
        select 1 from pg_constraint
         where conname = 'devices_last_sync_error_max_len'
    ) then
        alter table public.devices
            add constraint devices_last_sync_error_max_len
            check (last_sync_error is null or char_length(last_sync_error) <= 4000);
    end if;

    if not exists (
        select 1 from pg_constraint
         where conname = 'scan_sessions_operator_name_max_len'
    ) then
        alter table public.scan_sessions
            add constraint scan_sessions_operator_name_max_len
            check (operator_name is null or char_length(operator_name) <= 100);
    end if;

    if not exists (
        select 1 from pg_constraint
         where conname = 'rd_accounts_name_max_len'
    ) then
        alter table public.rd_accounts
            add constraint rd_accounts_name_max_len
            check (char_length(name) <= 200);
    end if;

    if not exists (
        select 1 from pg_constraint
         where conname = 'rd_accounts_last_paid_through_format'
    ) then
        -- Length-AND-format CHECK in one. Phone enforces YYYY-MM
        -- (MonthYear), portal CSV parser enforces YYYY-MM (regex), but
        -- a malformed cloud write (manual SQL, future RPC bug) could
        -- still land garbage. Lexical comparison is only equivalent to
        -- chronological comparison for strict YYYY-MM, so 'last_paid_through < currentMonth'
        -- silently wrong for '2025-3' or '25-03'.
        alter table public.rd_accounts
            add constraint rd_accounts_last_paid_through_format
            check (
                last_paid_through is null
                or last_paid_through ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'
            );
    end if;
end $$;

-- ---------------------------------------------------------------------
-- Part 2: redundant single-column owner_id index cleanup
-- ---------------------------------------------------------------------
-- DROP INDEX IF EXISTS is idempotent and cheap (metadata-only on PG15
-- when no blocking queries; CONCURRENTLY not required at <10k rows).
-- Verified the composite indexes that subsume each one exist BEFORE
-- this migration ships:
--   scan_lots_owner_updatedat_idx        (owner_id, updated_at)
--   rd_numbers_owner_updatedat_idx       (owner_id, updated_at)
--   rd_accounts_owner_updatedat_idx      (owner_id, updated_at)

drop index if exists public.scan_lots_owner_idx;
drop index if exists public.rd_numbers_owner_idx;
drop index if exists public.rd_accounts_owner_idx;

-- Note on devices_owner_idx: we KEEP that one. It's
-- (owner_id, last_seen_at desc), not just (owner_id), and the portal's
-- Devices page sorts by last_seen_at, not updated_at. The v11 add of
-- devices_owner_last_push_idx is a third index for the new sort, not
-- a replacement.

commit;

-- ---------------------------------------------------------------------
-- SANITY CHECKS (run after applying; each must return what the comment
-- says before you trust the migration).
-- ---------------------------------------------------------------------
--
--   -- 1. All five CHECK constraints exist with the right table.
--   select conrelid::regclass as table_name, conname, pg_get_constraintdef(oid) as def
--     from pg_constraint
--    where conname in (
--        'devices_device_name_max_len',
--        'devices_last_sync_error_max_len',
--        'scan_sessions_operator_name_max_len',
--        'rd_accounts_name_max_len',
--        'rd_accounts_last_paid_through_format'
--    )
--    order by table_name, conname;
--   -- expect: exactly 5 rows; defs match the CHECK expressions above.
--
--   -- 2. No existing row violates any new CHECK (the migration would
--   --    have aborted, but double-check after the fact for paper trail).
--   select 'devices.device_name'      as col, count(*) as violations
--     from public.devices       where char_length(device_name) > 100
--   union all
--   select 'devices.last_sync_error', count(*)
--     from public.devices       where last_sync_error is not null and char_length(last_sync_error) > 4000
--   union all
--   select 'scan_sessions.operator_name', count(*)
--     from public.scan_sessions where operator_name is not null and char_length(operator_name) > 100
--   union all
--   select 'rd_accounts.name', count(*)
--     from public.rd_accounts   where char_length(name) > 200
--   union all
--   select 'rd_accounts.last_paid_through', count(*)
--     from public.rd_accounts
--    where last_paid_through is not null
--      and last_paid_through !~ '^[0-9]{4}-(0[1-9]|1[0-2])$';
--   -- expect: every row's violations = 0.
--
--   -- 3. The three redundant single-column indexes are gone.
--   select indexname
--     from pg_indexes
--    where schemaname = 'public'
--      and indexname in (
--        'scan_lots_owner_idx',
--        'rd_numbers_owner_idx',
--        'rd_accounts_owner_idx'
--      );
--   -- expect: zero rows.
--
--   -- 4. The subsuming composite indexes still exist (the planner
--   --    needs these — confirm none were accidentally dropped).
--   select indexname
--     from pg_indexes
--    where schemaname = 'public'
--      and indexname in (
--        'scan_lots_owner_updatedat_idx',
--        'rd_numbers_owner_updatedat_idx',
--        'rd_accounts_owner_updatedat_idx',
--        'devices_owner_idx',
--        'devices_owner_last_push_idx'
--      )
--    order by indexname;
--   -- expect: exactly 5 rows.
