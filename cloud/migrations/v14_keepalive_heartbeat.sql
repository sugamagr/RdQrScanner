-- v14: keepalive_heartbeat table + keepalive_beat() function
--
-- WHY THIS EXISTS
-- Supabase pauses a Free plan project that "does not receive sufficient
-- user database activity over the past week"; its docs say "typically a
-- few user requests to the database each day" is enough. The first
-- keep-alive Worker sent ONE anonymous read every 3 days, which is far
-- below that, so the project kept getting paused while the Worker
-- reported success.
--
-- The Worker (supabase-keepalive/) now calls keepalive_beat() every
-- hour. Each call is a real write transaction: it bumps today's counter
-- row and returns the recent counts, which the Worker's weekly email
-- uses to prove the hourly schedule actually ran.
--
-- SAFETY
--   * The table holds one small row per day and is trimmed to 60 days
--     on every call, so it cannot grow even if someone calls the
--     function in a loop with the public anon key.
--   * Row level security is ON with no policies and all table grants
--     are revoked from anon/authenticated: the table is reachable only
--     through the function.
--   * Nothing here touches the app's tables. Phones and the portal are
--     unaffected and need no update.
--
-- APPLY: paste into Supabase Studio -> SQL editor and run. Safe to run
-- again. Wrapped in begin;commit; so a failure changes nothing.
begin;

create table if not exists public.keepalive_heartbeat (
    day       date primary key,
    beats     integer not null default 0,
    first_at  timestamptz not null default now(),
    last_at   timestamptz not null default now()
);

comment on table public.keepalive_heartbeat is
    'One row per UTC day, bumped hourly by the ops Worker via '
    'keepalive_beat(). Exists only to generate real database activity '
    'so the Free plan project is not paused. Not app data.';

alter table public.keepalive_heartbeat enable row level security;
revoke all on table public.keepalive_heartbeat from anon, authenticated;

create or replace function public.keepalive_beat()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_today date := (now() at time zone 'utc')::date;
begin
    insert into public.keepalive_heartbeat as h (day, beats, first_at, last_at)
    values (v_today, 1, now(), now())
    on conflict (day) do update
        set beats = h.beats + 1,
            last_at = now();

    delete from public.keepalive_heartbeat where day < v_today - 60;

    return jsonb_build_object(
        'server_time', now(),
        'beats_today',
            (select k.beats from public.keepalive_heartbeat k where k.day = v_today),
        'beats_yesterday',
            coalesce((select k.beats from public.keepalive_heartbeat k where k.day = v_today - 1), 0),
        'beats_7d',
            coalesce((select sum(k.beats) from public.keepalive_heartbeat k where k.day > v_today - 7), 0),
        -- true once tracking began before yesterday, i.e. yesterday was a
        -- full day and its count can be judged against the expected 24
        'yesterday_full',
            coalesce((select min(k.day) < v_today - 1 from public.keepalive_heartbeat k), false)
    );
end;
$$;

comment on function public.keepalive_beat() is
    'Called hourly by the ops Worker with the anon key. Records one '
    'heartbeat and returns recent counts.';

revoke all on function public.keepalive_beat() from public;
grant execute on function public.keepalive_beat() to anon, authenticated, service_role;

-- Make the new function visible to the REST API straight away.
notify pgrst, 'reload schema';

-- Sanity check: one call works and reports a count of at least 1.
do $$
declare
    v jsonb;
begin
    v := public.keepalive_beat();
    if coalesce((v ->> 'beats_today')::int, 0) < 1 then
        raise exception 'v14 migration failed: keepalive_beat() returned %', v;
    end if;
end $$;

commit;

-- After running, this should show one row for today:
--   select * from public.keepalive_heartbeat order by day desc;
