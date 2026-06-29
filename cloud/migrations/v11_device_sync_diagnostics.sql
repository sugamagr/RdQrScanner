-- v11: device sync diagnostics columns
--
-- Adds three observability fields to public.devices so the portal can
-- show "is this phone actually syncing?" without the owner having to
-- pick up the phone and check the in-app sync pill.
--
--  - last_sync_error : text       — last error message from runPush()
--                                   for this phone; null on clean cycle
--  - pending_count   : int        — rows still DIRTY/SYNC_ERROR after
--                                   the most recent push cycle on this
--                                   phone; 0 means caught up
--  - last_push_at    : timestamptz — when this phone's runPush() last
--                                   completed (success OR failure),
--                                   distinct from last_seen_at which
--                                   bumps on any cloud touch
--
-- All three are populated by the phone on every runPush() exit so the
-- portal sees the same picture the phone's sync pill sees. The columns
-- are nullable / defaulted so existing rows backfill without manual
-- intervention; new device registrations will populate them on first
-- push.
--
-- IDEMPOTENT: safe to re-run. Mirrors v10_round5_hardening.sql style.
-- Paste verbatim into Supabase Studio SQL editor; the four sanity
-- checks at the bottom should all return the expected counts.

alter table public.devices
    add column if not exists last_sync_error text,
    add column if not exists pending_count   int  not null default 0,
    add column if not exists last_push_at    timestamptz;

-- Index supports the portal's Devices page sort by "most recently
-- pushed first" once that ordering ships; cheap even without consumers
-- because devices table size is small (<= ~5 rows per owner in the
-- canonical 2-5 phone scale).
create index if not exists devices_owner_last_push_idx
    on public.devices (owner_id, last_push_at desc nulls last);

-- ---------------------------------------------------------------------
-- SANITY CHECKS (run after applying; each must return what the comment
-- says before you trust the migration).
-- ---------------------------------------------------------------------
--
--   -- 1. The three columns exist with the right types and nullability.
--   select column_name, data_type, is_nullable, column_default
--     from information_schema.columns
--    where table_schema = 'public'
--      and table_name = 'devices'
--      and column_name in ('last_sync_error', 'pending_count', 'last_push_at')
--    order by column_name;
--   -- expect:
--   --   last_push_at     | timestamp with time zone | YES | NULL
--   --   last_sync_error  | text                     | YES | NULL
--   --   pending_count    | integer                  | NO  | 0
--
--   -- 2. Existing rows backfilled cleanly (no nulls in NOT NULL column).
--   select count(*) as zero_pending,
--          count(*) filter (where last_sync_error is null) as null_errors,
--          count(*) filter (where last_push_at     is null) as null_push_at
--     from public.devices;
--   -- expect: zero_pending = (total rows), null_errors = (total rows),
--   -- null_push_at = (total rows). All three indicate fresh backfill.
--
--   -- 3. The new index exists.
--   select indexname from pg_indexes
--    where schemaname = 'public'
--      and tablename = 'devices'
--      and indexname = 'devices_owner_last_push_idx';
--   -- expect: one row.
--
--   -- 4. RLS still locks down the table (no policy regression from
--   --    the alter table). The canonical schema defines exactly THREE
--   --    policies on devices: owner-select, owner-insert, owner-update.
--   --    There is intentionally NO delete policy — phones soft-delete
--   --    via the deleted_at column (an UPDATE), they never hard-delete.
--   --    Same pattern as every other syncable table in the schema.
--   select policyname, cmd
--     from pg_policies
--    where schemaname = 'public' and tablename = 'devices'
--    order by policyname;
--   -- expect: exactly 3 rows —
--   --   devices: owner insert | INSERT
--   --   devices: owner select | SELECT
--   --   devices: owner update | UPDATE
