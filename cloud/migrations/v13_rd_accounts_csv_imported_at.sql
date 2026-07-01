-- v13: rd_accounts.csv_imported_at column for R3 session-delete cascade
--
-- BACKGROUND (R3 spec, user-locked):
-- On session-delete cascade the phone must revert every rd_number's
-- last_paid_through contribution from the deleted session — but ONLY
-- if the session's scan date is AFTER the account's most-recent CSV
-- import. If the CSV is newer than the session, the DOP portal export
-- is authoritative (user's rationale: "csv has data from server live
-- dop portal, so if i got the paid month from there who knows if they
-- scanned the session twice") and the session's contribution is
-- silently dropped without touching last_paid_through.
--
-- WHY A COLUMN (option a) over an audit log (option c) or
-- source=CSV+updated_at heuristic (option b):
--   (b) is broken: rd_accounts.updated_at is "last write of any kind"
--       so a portal name-edit AFTER a CSV import masks the CSV
--       timestamp — the R3 guard would compare session dates to the
--       name-edit time instead of the real CSV time.
--   (c) is over-engineering for the current scale (~200 accounts/month
--       × 12 = ~2400 log rows/year; useful only if a full CSV-import
--       audit trail becomes a product feature — which is not asked).
--   (a) stores exactly one timestamp per account = "when last CSV
--       import touched this row." O(1) read on the R3 guard hot path;
--       trivial data footprint; can be denormalized into a full log
--       later without blocking this change.
--
-- APPLY: paste into Supabase Studio → SQL editor and run. Idempotent
-- via `add column if not exists`. Wrapped in begin;commit; so a mid-
-- migration failure leaves the schema untouched.
begin;

alter table public.rd_accounts
    add column if not exists csv_imported_at timestamptz;

comment on column public.rd_accounts.csv_imported_at is
    'Timestamp of most recent CSV/PDF import via bulkUpsertAccounts. '
    'NULL for accounts never touched by CSV (MANUAL-only). Read by '
    'phone R3 session-delete cascade: session end_time > csv_imported_at '
    'means the scan is newer than the import → revert last_paid_through '
    'contribution. Otherwise the CSV is authoritative and the session '
    'is dropped silently.';

-- Sanity check: column exists after migration.
do $$
begin
    if not exists (
        select 1
        from information_schema.columns
        where table_schema = 'public'
          and table_name = 'rd_accounts'
          and column_name = 'csv_imported_at'
    ) then
        raise exception 'v13 migration failed: csv_imported_at column not created';
    end if;
end $$;

commit;
