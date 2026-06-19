-- =====================================================================
-- RD Book QR Scanner — Supabase Schema
-- =====================================================================
-- Spec: docs/CLOUD_SYNC_SPEC.md §5 (data model), §13 (RLS), §10
-- (multi-device), §11 (conflict resolution).
--
-- Apply by pasting the entire file into Supabase Studio → SQL Editor →
-- New Query → Run. Idempotent: every CREATE uses IF NOT EXISTS and
-- every CREATE POLICY drops the prior version first, so re-running is
-- safe.
--
-- After applying:
--   1. Studio → Database → Replication → enable Realtime for all four
--      data tables (devices, scan_sessions, scan_lots, rd_numbers).
--   2. Studio → Authentication → Users → Add user → owner@yourdomain.com
--      with a strong password. This is the owner account the phones and
--      portal sign in with.
-- =====================================================================

-- 0. Required extensions ----------------------------------------------
create extension if not exists "pgcrypto";   -- gen_random_uuid()

-- 1. Tables -----------------------------------------------------------

create table if not exists public.devices (
    id            uuid primary key default gen_random_uuid(),
    owner_id      uuid not null references auth.users(id) on delete cascade,
    device_name   text not null,
    device_model  text,
    first_seen_at timestamptz not null default now(),
    last_seen_at  timestamptz not null default now(),
    app_version   text,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);
create index if not exists devices_owner_idx
    on public.devices (owner_id, last_seen_at desc);

create table if not exists public.scan_sessions (
    id                uuid primary key default gen_random_uuid(),
    owner_id          uuid not null references auth.users(id) on delete cascade,
    device_id         uuid not null references public.devices(id) on delete restrict,
    operator_name     text,
    display_number    int not null,
    start_time        timestamptz not null,
    end_time          timestamptz not null,
    total_lots        int not null default 0,
    total_rd_numbers  int not null default 0,
    default_count     int not null default 0,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    deleted_at        timestamptz
);
create index if not exists scan_sessions_owner_endtime_idx
    on public.scan_sessions (owner_id, end_time desc);
create index if not exists scan_sessions_owner_deletedat_idx
    on public.scan_sessions (owner_id, deleted_at);
create index if not exists scan_sessions_owner_displaynum_idx
    on public.scan_sessions (owner_id, display_number);

create table if not exists public.scan_lots (
    id          uuid primary key default gen_random_uuid(),
    owner_id    uuid not null references auth.users(id) on delete cascade,
    session_id  uuid not null references public.scan_sessions(id) on delete cascade,
    lot_number  int not null,
    timestamp   timestamptz not null,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    deleted_at  timestamptz,
    unique (session_id, lot_number)
);
create index if not exists scan_lots_session_lotnum_idx
    on public.scan_lots (session_id, lot_number);
create index if not exists scan_lots_owner_idx
    on public.scan_lots (owner_id);

create table if not exists public.rd_numbers (
    id           uuid primary key default gen_random_uuid(),
    owner_id     uuid not null references auth.users(id) on delete cascade,
    lot_id       uuid not null references public.scan_lots(id) on delete cascade,
    number       text not null,
    position     int not null,
    scanned_at   timestamptz not null,
    months_paid  int not null default 1 check (months_paid between 1 and 36),
    months_list  text,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    deleted_at   timestamptz
);
create index if not exists rd_numbers_lot_position_idx
    on public.rd_numbers (lot_id, position);
create index if not exists rd_numbers_owner_idx
    on public.rd_numbers (owner_id);
create index if not exists rd_numbers_owner_number_idx
    on public.rd_numbers (owner_id, number);

-- 2. updated_at trigger -----------------------------------------------
-- Stamps updated_at on every UPDATE so the client can't accidentally
-- omit it. Last-writer-wins conflict resolution (§11) compares this
-- field so server-side stamping is essential.

create or replace function public.set_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

drop trigger if exists trg_devices_updated_at on public.devices;
create trigger trg_devices_updated_at
    before update on public.devices
    for each row execute function public.set_updated_at();

drop trigger if exists trg_scan_sessions_updated_at on public.scan_sessions;
create trigger trg_scan_sessions_updated_at
    before update on public.scan_sessions
    for each row execute function public.set_updated_at();

drop trigger if exists trg_scan_lots_updated_at on public.scan_lots;
create trigger trg_scan_lots_updated_at
    before update on public.scan_lots
    for each row execute function public.set_updated_at();

drop trigger if exists trg_rd_numbers_updated_at on public.rd_numbers;
create trigger trg_rd_numbers_updated_at
    before update on public.rd_numbers
    for each row execute function public.set_updated_at();

-- 3. next_display_number RPC ------------------------------------------
-- Server-assigned display number under a Postgres advisory lock so two
-- concurrent phones can never collide on the same number. Per spec §5.
-- SECURITY DEFINER so it runs with elevated privileges, but the
-- p_owner_id parameter is verified against auth.uid() to prevent
-- cross-account leakage.

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

    select coalesce(max(display_number), 0) + 1
        into v_next
        from public.scan_sessions
        where owner_id = p_owner_id
          and deleted_at is null;

    return v_next;
end;
$$;

revoke all on function public.next_display_number(uuid) from public;
grant execute on function public.next_display_number(uuid) to authenticated;

-- 4. Row-Level Security policies --------------------------------------
-- Spec §13. Every row is filtered by owner_id = auth.uid().
-- DELETE policies are intentionally absent — clients never hard-delete;
-- they set deleted_at and let the soft-tombstone propagate per §11.

alter table public.devices        enable row level security;
alter table public.scan_sessions  enable row level security;
alter table public.scan_lots      enable row level security;
alter table public.rd_numbers     enable row level security;

-- devices ----------------------------------------------------------
drop policy if exists "devices: owner select" on public.devices;
create policy "devices: owner select" on public.devices
    for select using (owner_id = auth.uid());
drop policy if exists "devices: owner insert" on public.devices;
create policy "devices: owner insert" on public.devices
    for insert with check (owner_id = auth.uid());
drop policy if exists "devices: owner update" on public.devices;
create policy "devices: owner update" on public.devices
    for update using (owner_id = auth.uid()) with check (owner_id = auth.uid());

-- scan_sessions ----------------------------------------------------
drop policy if exists "scan_sessions: owner select" on public.scan_sessions;
create policy "scan_sessions: owner select" on public.scan_sessions
    for select using (owner_id = auth.uid());
drop policy if exists "scan_sessions: owner insert" on public.scan_sessions;
create policy "scan_sessions: owner insert" on public.scan_sessions
    for insert with check (owner_id = auth.uid());
drop policy if exists "scan_sessions: owner update" on public.scan_sessions;
create policy "scan_sessions: owner update" on public.scan_sessions
    for update using (owner_id = auth.uid()) with check (owner_id = auth.uid());

-- scan_lots --------------------------------------------------------
drop policy if exists "scan_lots: owner select" on public.scan_lots;
create policy "scan_lots: owner select" on public.scan_lots
    for select using (owner_id = auth.uid());
drop policy if exists "scan_lots: owner insert" on public.scan_lots;
create policy "scan_lots: owner insert" on public.scan_lots
    for insert with check (owner_id = auth.uid());
drop policy if exists "scan_lots: owner update" on public.scan_lots;
create policy "scan_lots: owner update" on public.scan_lots
    for update using (owner_id = auth.uid()) with check (owner_id = auth.uid());

-- rd_numbers -------------------------------------------------------
drop policy if exists "rd_numbers: owner select" on public.rd_numbers;
create policy "rd_numbers: owner select" on public.rd_numbers
    for select using (owner_id = auth.uid());
drop policy if exists "rd_numbers: owner insert" on public.rd_numbers;
create policy "rd_numbers: owner insert" on public.rd_numbers
    for insert with check (owner_id = auth.uid());
drop policy if exists "rd_numbers: owner update" on public.rd_numbers;
create policy "rd_numbers: owner update" on public.rd_numbers
    for update using (owner_id = auth.uid()) with check (owner_id = auth.uid());

-- 5. Realtime publication --------------------------------------------
-- Supabase Realtime listens to changes published on a specific
-- publication. Adding the data tables here lets phones subscribe to
-- postgresChangeFlow per §14.
--
-- Idempotent: if the table is already in the publication this is a
-- no-op via the EXCEPTION block.

do $$
begin
    perform 1 from pg_publication where pubname = 'supabase_realtime';
    if found then
        begin
            execute 'alter publication supabase_realtime add table public.devices';
        exception when duplicate_object then null;
        end;
        begin
            execute 'alter publication supabase_realtime add table public.scan_sessions';
        exception when duplicate_object then null;
        end;
        begin
            execute 'alter publication supabase_realtime add table public.scan_lots';
        exception when duplicate_object then null;
        end;
        begin
            execute 'alter publication supabase_realtime add table public.rd_numbers';
        exception when duplicate_object then null;
        end;
    end if;
end$$;

-- =====================================================================
-- End of schema.sql.
-- =====================================================================
