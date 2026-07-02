import { supabase } from './supabase';
import type {
  ActivityKind,
  ActivityRow,
  DeviceRow,
  RdAccountRow,
  RdNumberRow,
  ScanLotRow,
  ScanSessionRow,
} from '../types/db';

/**
 * Marker error class for an expired or missing auth session, thrown
 * by [requireOwnerId] so callers can distinguish "user is signed out"
 * from other mutation failures and route to the sign-in page instead
 * of showing a generic error toast. The catch-side check is `if (err
 * instanceof SessionExpiredError)`.
 */
export class SessionExpiredError extends Error {
  constructor() {
    super('Your session expired. Please sign in again.');
    this.name = 'SessionExpiredError';
  }
}

/**
 * Resolves the current owner_id for defense-in-depth filtering on
 * mutations. RLS at cloud/schema.sql §318-326 already blocks cross-
 * owner writes, but adding the explicit `.eq('owner_id', x)` filter
 * here means the wire payload itself encodes the constraint and any
 * future RLS misconfiguration gets caught client-side before the
 * round trip. Throws [SessionExpiredError] if there's no live session
 * so a caller never silently runs an unfiltered mutation AND the UI
 * can route to /signin instead of surfacing developer-speak.
 */
export async function requireOwnerId(): Promise<string> {
  const { data, error } = await supabase.auth.getUser();
  if (error) throw error;
  const ownerId = data.user?.id;
  if (!ownerId) {
    throw new SessionExpiredError();
  }
  return ownerId;
}

export const SESSIONS_PAGE_SIZE = 30;

export interface SessionsPage {
  rows: ScanSessionRow[];
  nextOffset: number | null;
}

/**
 * Cursor-based pagination would be more correct under realtime churn
 * but offset is fine for the portal's scale (<<10k sessions) and keeps
 * the URL/query state simple.
 */
export async function fetchSessionsPage(params: {
  offset: number;
  search?: string;
}): Promise<SessionsPage> {
  const { offset, search } = params;
  const to = offset + SESSIONS_PAGE_SIZE - 1;
  const ownerId = await requireOwnerId();

  const trimmed = search?.trim() ?? '';
  const asNumber = trimmed ? Number(trimmed) : NaN;
  const isPureInt = trimmed !== '' && !Number.isNaN(asNumber) && Number.isInteger(asNumber);

  // Text-mode search (any non-numeric query) resolves session IDs
  // through: rd_accounts.name ILIKE %q% → rd_number list →
  // rd_numbers → scan_lots → scan_sessions.id. Chained client-side
  // fetch instead of a Postgres RPC because owner-scale is ~30
  // sessions/year × ~200 accounts; the four round-trips stay under
  // ~500ms and the rewrite avoids a cloud migration (which would
  // force a manual Supabase Studio paste for every operator on
  // first upgrade). If scale ever grows past ~10k sessions, replace
  // this with an RPC that runs the join server-side.
  let sessionIdFilter: string[] | null = null;
  if (trimmed !== '' && !isPureInt) {
    // rd_accounts is indexed on lower(name) gin_trgm_ops — cheap
    // ILIKE. Match ANY substring; users search "shikhil" or "gupta"
    // not the whole name.
    const escaped = trimmed.replace(/[%_\\]/g, (ch) => `\\${ch}`);
    const accountsRes = await supabase
      .from('rd_accounts')
      .select('rd_number')
      .eq('owner_id', ownerId)
      .is('deleted_at', null)
      .ilike('name', `%${escaped}%`);
    if (accountsRes.error) throw accountsRes.error;
    const rdNumbers = (accountsRes.data ?? []).map((r) => (r as { rd_number: string }).rd_number);
    if (rdNumbers.length === 0) {
      return { rows: [], nextOffset: null };
    }
    const lotIdsRes = await supabase
      .from('rd_numbers')
      .select('lot_id')
      .eq('owner_id', ownerId)
      .is('deleted_at', null)
      .in('number', rdNumbers);
    if (lotIdsRes.error) throw lotIdsRes.error;
    const lotIds = Array.from(
      new Set((lotIdsRes.data ?? []).map((r) => (r as { lot_id: string }).lot_id))
    );
    if (lotIds.length === 0) {
      return { rows: [], nextOffset: null };
    }
    const sessionsRes = await supabase
      .from('scan_lots')
      .select('session_id')
      .eq('owner_id', ownerId)
      .is('deleted_at', null)
      .in('id', lotIds);
    if (sessionsRes.error) throw sessionsRes.error;
    sessionIdFilter = Array.from(
      new Set((sessionsRes.data ?? []).map((r) => (r as { session_id: string }).session_id))
    );
    if (sessionIdFilter.length === 0) {
      return { rows: [], nextOffset: null };
    }
  }

  let query = supabase
    .from('scan_sessions')
    .select('*', { count: 'exact' })
    .eq('owner_id', ownerId)
    .is('deleted_at', null)
    .order('end_time', { ascending: false })
    .range(offset, to);

  if (isPureInt) {
    query = query.eq('display_number', asNumber);
  } else if (sessionIdFilter !== null) {
    query = query.in('id', sessionIdFilter);
  }

  const { data, error, count } = await query;
  if (error) throw error;
  const rows = (data ?? []) as ScanSessionRow[];
  const fetched = offset + rows.length;
  const total = count ?? fetched;
  return {
    rows,
    nextOffset: fetched < total ? fetched : null,
  };
}

export async function fetchSession(sessionId: string): Promise<ScanSessionRow | null> {
  const ownerId = await requireOwnerId();
  const { data, error } = await supabase
    .from('scan_sessions')
    .select('*')
    .eq('id', sessionId)
    .eq('owner_id', ownerId)
    .is('deleted_at', null)
    .maybeSingle();
  if (error) throw error;
  return (data as ScanSessionRow | null) ?? null;
}

export async function fetchLotsForSession(sessionId: string): Promise<ScanLotRow[]> {
  const ownerId = await requireOwnerId();
  const { data, error } = await supabase
    .from('scan_lots')
    .select('*')
    .eq('session_id', sessionId)
    .eq('owner_id', ownerId)
    .is('deleted_at', null)
    .order('lot_number', { ascending: true });
  if (error) throw error;
  return (data ?? []) as ScanLotRow[];
}

export async function fetchRdNumbersForLots(lotIds: string[]): Promise<RdNumberRow[]> {
  if (lotIds.length === 0) return [];
  const ownerId = await requireOwnerId();
  const { data, error } = await supabase
    .from('rd_numbers')
    .select('*')
    .in('lot_id', lotIds)
    .eq('owner_id', ownerId)
    .is('deleted_at', null)
    .order('position', { ascending: true });
  if (error) throw error;
  return (data ?? []) as RdNumberRow[];
}

export async function fetchDevices(): Promise<DeviceRow[]> {
  const ownerId = await requireOwnerId();
  const { data, error } = await supabase
    .from('devices')
    .select('*')
    .eq('owner_id', ownerId)
    .is('deleted_at', null)
    .order('last_seen_at', { ascending: false });
  if (error) throw error;
  return (data ?? []) as DeviceRow[];
}

/**
 * Materializes the Activity feed by reading recent rows from the three
 * tables that carry events (scan_sessions, rd_numbers with defaulter
 * data, rd_accounts) and projecting into a uniform [ActivityRow] shape.
 *
 * The cloud has no dedicated `activity` table — the phone's bell uses a
 * Room-local `sync_events` table that doesn't exist server-side (spec
 * §15.5.5), so the portal can't read from it. The four queries here
 * fetch each event-emitting source independently, sorted by their
 * `updated_at` or `created_at`. The merge interleaves them client-side
 * and trims to [limit] so the UI gets a single feed.
 *
 * Attribution mirrors `mergeRdNumbers` on the phone:
 *  - `null` last_editor_device_id  → "Portal"
 *  - resolved device row           → device.device_name
 *  - unresolved (deleted device)   → "(removed phone)"
 *
 * Categories defined in [ActivityKind]:
 *  - `session_finalized` — scan_sessions row with isActive=0 (no
 *    column; we treat any non-deleted session as finalized because
 *    portal-visible sessions are always finalized — active sessions
 *    don't push to cloud per §10)
 *  - `session_deleted` — scan_sessions row with deleted_at != null
 *  - `defaulter_edited` — rd_numbers row with months_paid > 1
 *  - `account_added` — rd_accounts row with created_at == updated_at
 *  - `account_edited` — rd_accounts row with updated_at > created_at
 */
export async function fetchActivityFeed(params: {
  limit?: number;
  kinds?: ReadonlyArray<ActivityKind>;
}): Promise<ActivityRow[]> {
  const limit = params.limit ?? 100;
  const requested = new Set(params.kinds ?? [
    'session_finalized',
    'session_deleted',
    'defaulter_edited',
    'account_added',
    'account_edited',
  ]);
  const ownerId = await requireOwnerId();

  // Fetch the device map once so attribution is O(1) per event after.
  const { data: deviceRows, error: devicesErr } = await supabase
    .from('devices')
    .select('id, device_name')
    .eq('owner_id', ownerId);
  if (devicesErr) throw devicesErr;
  const deviceById = new Map<string, string>();
  for (const row of (deviceRows ?? []) as Array<{ id: string; device_name: string }>) {
    deviceById.set(row.id, row.device_name);
  }
  const labelFor = (deviceId: string | null): string => {
    if (deviceId == null) return 'Portal';
    return deviceById.get(deviceId) ?? '(removed phone)';
  };

  // Each source fetches up to `limit` rows. The merge below trims to
  // the global `limit` so we don't over-fetch.
  const events: ActivityRow[] = [];

  if (requested.has('session_finalized') || requested.has('session_deleted')) {
    const { data, error } = await supabase
      .from('scan_sessions')
      .select('id, display_number, operator_name, total_lots, total_rd_numbers, device_id, updated_at, deleted_at')
      .eq('owner_id', ownerId)
      .order('updated_at', { ascending: false })
      .limit(limit);
    if (error) throw error;
    for (const row of (data ?? []) as Array<{
      id: string;
      display_number: number;
      operator_name: string | null;
      total_lots: number;
      total_rd_numbers: number;
      device_id: string;
      updated_at: string;
      deleted_at: string | null;
    }>) {
      if (row.deleted_at != null) {
        if (!requested.has('session_deleted')) continue;
        events.push({
          kind: 'session_deleted',
          occurredAt: row.deleted_at,
          actorLabel: labelFor(row.device_id),
          primary: `Session #${row.display_number} deleted`,
          secondary: row.operator_name ?? null,
          linkTo: null,
        });
      } else {
        if (!requested.has('session_finalized')) continue;
        events.push({
          kind: 'session_finalized',
          occurredAt: row.updated_at,
          actorLabel: labelFor(row.device_id),
          primary: `Session #${row.display_number} finalized`,
          secondary: `${row.total_lots} LOT${row.total_lots === 1 ? '' : 's'} · ${row.total_rd_numbers} RD numbers`,
          linkTo: `/sessions/${row.id}`,
        });
      }
    }
  }

  if (requested.has('defaulter_edited')) {
    // Defaulter edits are rd_numbers rows with months_paid > 1. We
    // can't reliably distinguish "initial defaulter at scan time"
    // from "edited later" without comparing to a baseline — the
    // cloud doesn't track that. Treat any defaulter row whose
    // updated_at differs from scanned_at as edited (the trigger
    // bumps updated_at on every write so the gap is reliable).
    const { data, error } = await supabase
      .from('rd_numbers')
      .select('id, number, months_paid, last_editor_device_id, updated_at, scanned_at, lot:scan_lots!inner(session_id, session:scan_sessions!inner(id, display_number))')
      .eq('owner_id', ownerId)
      .gt('months_paid', 1)
      .is('deleted_at', null)
      .order('updated_at', { ascending: false })
      .limit(limit);
    if (error) throw error;
    for (const row of (data ?? []) as unknown as Array<{
      id: string;
      number: string;
      months_paid: number;
      last_editor_device_id: string | null;
      updated_at: string;
      scanned_at: string;
      lot: { session_id: string; session: { id: string; display_number: number } } | { session_id: string; session: { id: string; display_number: number } }[] | null;
    }>) {
      // PostgREST returns embedded relationships as object OR
      // 1-element array depending on cardinality detection.
      const lot = Array.isArray(row.lot) ? row.lot[0] : row.lot;
      if (!lot?.session) continue;
      const session = Array.isArray(lot.session) ? lot.session[0] : lot.session;
      if (!session) continue;
      events.push({
        kind: 'defaulter_edited',
        occurredAt: row.updated_at,
        actorLabel: labelFor(row.last_editor_device_id),
        primary: `Defaulter edit on RD ${maskRdNumber(row.number)}`,
        secondary: `${row.months_paid} months · Session #${session.display_number}`,
        linkTo: `/sessions/${session.id}`,
      });
    }
  }

  if (requested.has('account_added') || requested.has('account_edited')) {
    const { data, error } = await supabase
      .from('rd_accounts')
      .select('rd_number, name, last_editor_device_id, created_at, updated_at')
      .eq('owner_id', ownerId)
      .is('deleted_at', null)
      .order('updated_at', { ascending: false })
      .limit(limit);
    if (error) throw error;
    for (const row of (data ?? []) as Array<{
      rd_number: string;
      name: string;
      last_editor_device_id: string | null;
      created_at: string;
      updated_at: string;
    }>) {
      // Equal created_at / updated_at means insert with no follow-up
      // edit; anything else means at least one edit landed after
      // creation. The server trigger guarantees updated_at >= created_at.
      const isFreshInsert = row.created_at === row.updated_at;
      if (isFreshInsert) {
        if (!requested.has('account_added')) continue;
        events.push({
          kind: 'account_added',
          occurredAt: row.created_at,
          actorLabel: labelFor(row.last_editor_device_id),
          primary: `Account added — ${row.name}`,
          secondary: maskRdNumber(row.rd_number),
          linkTo: '/accounts',
        });
      } else {
        if (!requested.has('account_edited')) continue;
        events.push({
          kind: 'account_edited',
          occurredAt: row.updated_at,
          actorLabel: labelFor(row.last_editor_device_id),
          primary: `Account edited — ${row.name}`,
          secondary: maskRdNumber(row.rd_number),
          linkTo: '/accounts',
        });
      }
    }
  }

  const collapsed = collapseBulkAccountEvents(events);
  collapsed.sort((a, b) => (b.occurredAt < a.occurredAt ? -1 : b.occurredAt > a.occurredAt ? 1 : 0));
  return collapsed.slice(0, limit);
}

/**
 * Collapses per-account activity rows that came from the same bulk
 * upload into a single aggregated row. Without this a 145-row CSV
 * import fills the Activity feed with 145 identical "Account added"
 * entries, drowning every other event (session finalize, defaulter
 * edit) that landed the same day.
 *
 * Bucket key: (kind, actorLabel, minute-of-occurredAt). Bulk upserts
 * always land within one minute (145 rows takes ~2-5s over the
 * PostgREST loop); manual add-one flows are separated by human typing
 * time so they never collide. Two operators doing single adds within
 * the same minute is not observed at 2-5 phone scale, and even if it
 * happened both events would carry distinct actorLabels so wouldn't
 * collapse.
 *
 * The phone-side bell already emits ONE aggregated `LOCAL_ACCOUNTS_
 * ADDED` event with summary "added N accounts"; this brings the
 * portal to visual parity. Single-account entries pass through
 * untouched because the group size stays 1 and this function short-
 * circuits to the original row.
 */
function collapseBulkAccountEvents(events: ReadonlyArray<ActivityRow>): ActivityRow[] {
  const groups = new Map<string, ActivityRow[]>();
  const passthrough: ActivityRow[] = [];
  for (const ev of events) {
    if (ev.kind !== 'account_added' && ev.kind !== 'account_edited') {
      passthrough.push(ev);
      continue;
    }
    const minuteBucket = ev.occurredAt.slice(0, 16);
    const key = `${ev.kind}|${ev.actorLabel}|${minuteBucket}`;
    const bucket = groups.get(key);
    if (bucket == null) {
      groups.set(key, [ev]);
    } else {
      bucket.push(ev);
    }
  }
  const out: ActivityRow[] = [...passthrough];
  for (const bucket of groups.values()) {
    if (bucket.length === 1) {
      out.push(bucket[0]);
    } else {
      const verb = bucket[0].kind === 'account_added' ? 'added' : 'edited';
      const noun = bucket.length === 1 ? 'account' : 'accounts';
      const newest = bucket.reduce((a, b) => (a.occurredAt > b.occurredAt ? a : b));
      out.push({
        kind: bucket[0].kind,
        occurredAt: newest.occurredAt,
        actorLabel: bucket[0].actorLabel,
        primary: `${bucket.length} ${noun} ${verb}`,
        secondary: 'Bulk update',
        linkTo: '/accounts',
      });
    }
  }
  return out;
}

/**
 * Masks all but the last 4 digits of an RD number for display in the
 * activity feed. Mirrors the phone's PII redaction policy: the bell
 * sheet showed full numbers historically (operator was the actor); on
 * the portal the owner sees a broader feed so showing only the tail is
 * the right default. The Account/Session pages still show full numbers
 * via direct navigation, gated by RLS.
 */
export function maskRdNumber(rdNumber: string): string {
  if (rdNumber.length <= 4) return rdNumber;
  return `***${rdNumber.slice(-4)} (len=${rdNumber.length})`;
}

/**
 * Soft-delete a session by stamping `deleted_at`. Mirrors the phone's
 * `SyncRepository.softDeleteSession` so the tombstone propagates via
 * the normal LWW merge path. Hard-delete is INTENTIONALLY not exposed
 * here — spec §11 line 188 says clients never hard-delete, and the
 * FK constraints (round-5 RESTRICT change) would refuse it anyway.
 *
 * Defensive helper exists EVEN BEFORE any UI uses it: oracle bg_a5d8fee6
 * flagged the missing function as a latent landmine. A future
 * maintainer adding a "Delete Session" button would naturally reach
 * for `supabase.from('scan_sessions').delete()`, which would either
 * fail the FK (after round-5) or break sync (before round-5). Having
 * the correct helper present makes the obviously-wrong path harder to
 * stumble into.
 */
export async function softDeleteSession(sessionId: string): Promise<void> {
  const ownerId = await requireOwnerId();
  const { error } = await supabase
    .from('scan_sessions')
    .update({ deleted_at: new Date().toISOString() })
    .eq('id', sessionId)
    .eq('owner_id', ownerId)
    .is('deleted_at', null);
  if (error) throw error;
}

/**
 * Per-LOT 20K cap enforcement helper. Spec §15.5.12 + D24: every LOT's
 * `Σ(monthly_amount × months_paid) <= 20_000`. Phone enforces this in
 * `LotReviewScreen.kt`. The portal MUST mirror the rule on defaulter
 * edits — without it, a portal save can push a LOT over cap and the
 * phone gets stuck unable to edit it (since LotReviewScreen.isOver
 * blocks Confirm regardless of what direction the operator pushes).
 *
 * Computes the cap by reading ALL alive rd_numbers in the LOT, joined
 * with rd_accounts for monthly_amount. The `excludeRdId` lets the
 * caller substitute its in-flight edit's new months_paid value into
 * the sum: pass the row's id to exclude its stored months_paid, then
 * add `pendingMonthsPaid × pendingMonthlyAmount` on top.
 *
 * Rows without a profile (rd_accounts row missing) contribute zero to
 * `verifiedRupees` and increment `unverifiedCount` — same semantic as
 * `LiveLotTotal` on the phone scanner so the portal warning copy can
 * match.
 */
export interface LotTotalsSnapshot {
  verifiedRupees: number;
  unverifiedCount: number;
}

export async function fetchLotTotalsExcluding(params: {
  lotId: string;
  excludeRdId: string;
}): Promise<LotTotalsSnapshot> {
  const { lotId, excludeRdId } = params;
  const ownerId = await requireOwnerId();
  const { data, error } = await supabase
    .from('rd_numbers')
    .select(
      `
      id, number, months_paid,
      account:rd_accounts!left(monthly_amount)
    `
    )
    .eq('lot_id', lotId)
    .eq('owner_id', ownerId)
    .neq('id', excludeRdId)
    .is('deleted_at', null);
  if (error) throw error;
  let verifiedRupees = 0;
  let unverifiedCount = 0;
  for (const row of (data ?? []) as Array<{
    months_paid: number;
    account: { monthly_amount: number } | { monthly_amount: number }[] | null;
  }>) {
    // PostgREST may return the joined row as an object or a 1-element
    // array depending on relationship cardinality detection. Normalize.
    const profile = Array.isArray(row.account) ? row.account[0] : row.account;
    const amount = profile?.monthly_amount;
    if (typeof amount === 'number' && amount > 0) {
      verifiedRupees += amount * row.months_paid;
    } else {
      unverifiedCount += 1;
    }
  }
  return { verifiedRupees, unverifiedCount };
}

export async function fetchAccountForRdNumber(
  rdNumber: string
): Promise<{ monthly_amount: number; name: string } | null> {
  const ownerId = await requireOwnerId();
  const { data, error } = await supabase
    .from('rd_accounts')
    .select('monthly_amount, name')
    .eq('rd_number', rdNumber)
    .eq('owner_id', ownerId)
    .is('deleted_at', null)
    .maybeSingle();
  if (error) throw error;
  return data ?? null;
}

export async function updateRdNumberMonths(params: {
  id: string;
  monthsPaid: number;
  monthsList: string | null;
}): Promise<void> {
  const { id, monthsPaid, monthsList } = params;
  const ownerId = await requireOwnerId();
  const { error } = await supabase
    .from('rd_numbers')
    .update({
      months_paid: monthsPaid,
      months_list: monthsList,
      // Phase 5 T5.6 (F9): explicitly nullify the editor stamp so the
      // phone merge attributes this edit to 'Portal' instead of inheriting
      // the prior phone's deviceId. Without this, a phone-stamped row
      // edited via portal would still surface as 'another phone' in
      // the banner + Channel C notification.
      last_editor_device_id: null,
    })
    .eq('id', id)
    .eq('owner_id', ownerId)
    .is('deleted_at', null);
  if (error) throw error;
}

export interface RdSearchHit {
  rd: RdNumberRow;
  lot: Pick<ScanLotRow, 'id' | 'lot_number' | 'session_id'>;
  session: Pick<ScanSessionRow, 'id' | 'display_number' | 'operator_name' | 'end_time'>;
}

/**
 * Search rd_numbers by partial number OR by customer name. Limited to
 * 100 hits — at scale the owner should narrow the query rather than
 * scroll thousands of matches.
 *
 * Query routing (single-input, two modes):
 *   - Pure digits (e.g. "1234"): matches rd_numbers.number ILIKE — the
 *     original RD-number-lookup behavior.
 *   - Anything else (e.g. "ajeet"): matches rd_accounts.name ILIKE %q%,
 *     resolves to a set of rd_numbers.number, then joins to sessions.
 *     Same 4-round-trip pattern Sessions.tsx name-search uses; kept
 *     client-side rather than a Postgres RPC because at the current
 *     scale (~30 sessions/year × ~200 accounts) latency is comfortably
 *     under 500ms and adding an RPC would require every operator's
 *     first upgrade to also paste a cloud migration.
 *
 * Both modes filter out tombstoned rd_numbers, lots, and sessions.
 * The trigram index rd_accounts_name_trgm_idx on lower(name) powers
 * the name path; the trigram index on rd_numbers.number powers the
 * digit path.
 */
export async function searchRdNumbers(query: string): Promise<RdSearchHit[]> {
  const trimmed = query.trim();
  if (trimmed.length < 2) return [];

  const ownerId = await requireOwnerId();
  const escaped = trimmed.replace(/[%_\\]/g, (c) => `\\${c}`);
  const isDigitQuery = /^\d+$/.test(trimmed);

  // Name mode: resolve customer name -> rd_number list, then feed the
  // list into the same rd_numbers-with-joined-lot-session query so the
  // render pipeline stays identical. If the name matches nothing, or
  // matches an rd_number that has never been scanned, return empty
  // without hitting the second query.
  let rdNumberFilter: string[] | null = null;
  if (!isDigitQuery) {
    const accountsRes = await supabase
      .from('rd_accounts')
      .select('rd_number')
      .eq('owner_id', ownerId)
      .is('deleted_at', null)
      .ilike('name', `%${escaped}%`)
      .limit(500);
    if (accountsRes.error) throw accountsRes.error;
    const matched = (accountsRes.data ?? []) as Array<{ rd_number: string }>;
    if (matched.length === 0) return [];
    rdNumberFilter = matched.map((r) => r.rd_number);
  }

  let q = supabase
    .from('rd_numbers')
    .select(
      `
      id, owner_id, lot_id, number, position, scanned_at, months_paid, months_list,
      last_editor_device_id, created_at, updated_at, deleted_at,
      lot:scan_lots!inner(id, lot_number, session_id,
        session:scan_sessions!inner(id, display_number, operator_name, end_time, deleted_at)
      )
    `
    )
    .eq('owner_id', ownerId)
    .is('deleted_at', null)
    .order('scanned_at', { ascending: false })
    .limit(100);
  q = isDigitQuery
    ? q.ilike('number', `%${escaped}%`)
    : q.in('number', rdNumberFilter!);
  const { data, error } = await q;

  if (error) throw error;
  if (!data) return [];

  const hits: RdSearchHit[] = [];
  for (const row of data as unknown as Array<
    RdNumberRow & {
      lot: ScanLotRow & {
        session: ScanSessionRow & { deleted_at: string | null };
      };
    }
  >) {
    if (row.lot?.session?.deleted_at) continue;
    if (!row.lot || !row.lot.session) continue;
    hits.push({
      rd: {
        id: row.id,
        owner_id: row.owner_id,
        lot_id: row.lot_id,
        number: row.number,
        position: row.position,
        scanned_at: row.scanned_at,
        months_paid: row.months_paid,
        months_list: row.months_list,
        last_editor_device_id: row.last_editor_device_id,
        created_at: row.created_at,
        updated_at: row.updated_at,
        deleted_at: row.deleted_at,
      },
      lot: {
        id: row.lot.id,
        lot_number: row.lot.lot_number,
        session_id: row.lot.session_id,
      },
      session: {
        id: row.lot.session.id,
        display_number: row.lot.session.display_number,
        operator_name: row.lot.session.operator_name,
        end_time: row.lot.session.end_time,
      },
    });
  }
  return hits;
}

/**
 * Fetch all rd_accounts visible to the signed-in owner. RLS does the
 * scoping; we filter `deleted_at IS NULL` client-side because Postgres
 * tombstone rows can still arrive via realtime echo before the client
 * cache reconciles. Sorted by lower(name) to match phone-side ordering.
 */
export async function fetchAccounts(): Promise<RdAccountRow[]> {
  const ownerId = await requireOwnerId();
  const { data, error } = await supabase
    .from('rd_accounts')
    .select('*')
    .eq('owner_id', ownerId)
    .is('deleted_at', null)
    .order('name', { ascending: true });
  if (error) throw error;
  return (data ?? []) as RdAccountRow[];
}

export async function updateAccount(params: {
  rdNumber: string;
  name: string;
  monthlyAmount: number;
  isActive: boolean;
}): Promise<void> {
  const { rdNumber, name, monthlyAmount, isActive } = params;
  const ownerId = await requireOwnerId();
  const { error } = await supabase
    .from('rd_accounts')
    .update({
      name,
      monthly_amount: monthlyAmount,
      is_active: isActive,
      last_editor_device_id: null,
    })
    .eq('rd_number', rdNumber)
    .eq('owner_id', ownerId)
    .is('deleted_at', null);
  if (error) throw error;
}

export async function markAccountInactive(rdNumber: string): Promise<void> {
  const ownerId = await requireOwnerId();
  const { error } = await supabase
    .from('rd_accounts')
    .update({ is_active: false, last_editor_device_id: null })
    .eq('rd_number', rdNumber)
    .eq('owner_id', ownerId)
    .is('deleted_at', null);
  if (error) throw error;
}

export async function reactivateAccount(rdNumber: string): Promise<void> {
  const ownerId = await requireOwnerId();
  const { error } = await supabase
    .from('rd_accounts')
    .update({ is_active: true, last_editor_device_id: null })
    .eq('rd_number', rdNumber)
    .eq('owner_id', ownerId)
    .is('deleted_at', null);
  if (error) throw error;
}

export async function softDeleteAccount(rdNumber: string): Promise<void> {
  const ownerId = await requireOwnerId();
  const { error } = await supabase
    .from('rd_accounts')
    .update({ deleted_at: new Date().toISOString(), last_editor_device_id: null })
    .eq('rd_number', rdNumber)
    .eq('owner_id', ownerId)
    .is('deleted_at', null);
  if (error) throw error;
}

/**
 * Bulk soft-delete for the portal's Accounts page bulk-select flow.
 * Same semantics as softDeleteAccount but batched: one PostgREST
 * round-trip per chunk. Chunked to stay under PostgREST's IN-list
 * URL-length limit (see fetchExistingLastPaidThroughMap for the same
 * 500 chunk convention). Returns the number of rows actually
 * tombstoned (may be less than input if some were already deleted
 * concurrently by another device — LWW via `is('deleted_at', null)`
 * gate).
 */
export async function bulkSoftDeleteAccounts(
  rdNumbers: ReadonlyArray<string>
): Promise<number> {
  if (rdNumbers.length === 0) return 0;
  const ownerId = await requireOwnerId();
  const now = new Date().toISOString();
  const CHUNK = 500;
  let deleted = 0;
  for (let i = 0; i < rdNumbers.length; i += CHUNK) {
    const chunk = rdNumbers.slice(i, i + CHUNK);
    const { data, error } = await supabase
      .from('rd_accounts')
      .update({ deleted_at: now, last_editor_device_id: null })
      .in('rd_number', chunk as string[])
      .eq('owner_id', ownerId)
      .is('deleted_at', null)
      .select('rd_number');
    if (error) throw error;
    deleted += data?.length ?? 0;
  }
  return deleted;
}

export interface BulkAccountInput {
  rdNumber: string;
  name: string;
  monthlyAmount: number;
  /**
   * Optional explicit override of `rd_accounts.last_paid_through`.
   * `null` = leave existing cloud value untouched (writer omits the
   * field from the upsert payload). Non-null = operator-authoritative
   * write, NO monotonic guard — the caller is responsible for any
   * regression-confirm UX before passing a value that lowers the
   * existing one. Mirrors phone-side `setLastPaidThroughExplicit`.
   */
  lastPaidThrough: string | null;
}

/**
 * Pre-import lookup: given a set of rd_numbers from a CSV, return the
 * currently-stored last_paid_through for any of them that already
 * exist (and aren't tombstoned). Used by ImportCsvDialog to compute
 * the regression set BEFORE the actual upsert lands, so the operator
 * can confirm or back out. Returns an empty map when the input is
 * empty.
 */
export async function fetchExistingLastPaidThroughMap(
  rdNumbers: ReadonlyArray<string>
): Promise<Map<string, string | null>> {
  if (rdNumbers.length === 0) return new Map();
  const ownerId = await requireOwnerId();
  const result = new Map<string, string | null>();
  // Chunked to stay under PostgREST's IN-list URL-length limit. 500
  // is well under Supabase's default 16 KB header cap (max 7 KB of
  // 12-digit rd_numbers in one URL) and round-trip cost at small N is
  // dwarfed by the upload itself.
  const CHUNK = 500;
  for (let i = 0; i < rdNumbers.length; i += CHUNK) {
    const chunk = rdNumbers.slice(i, i + CHUNK);
    const { data, error } = await supabase
      .from('rd_accounts')
      .select('rd_number, last_paid_through')
      .eq('owner_id', ownerId)
      .in('rd_number', chunk)
      .is('deleted_at', null);
    if (error) throw error;
    for (const row of (data ?? []) as Array<{ rd_number: string; last_paid_through: string | null }>) {
      result.set(row.rd_number, row.last_paid_through);
    }
  }
  return result;
}

export interface BulkUpsertResult {
  inserted: number;
  updated: number;
  failed: number;
  errors: Array<{ rdNumber: string; message: string }>;
}

/**
 * CSV bulk upload write path. Sends each row as a separate upsert
 * because PostgREST's array upsert returns the entire merged set and
 * we want per-row error reporting. For ~few-hundred-row uploads (user
 * spec) this is acceptable; if the volume grows, batch via .upsert
 * with a single array call and trade granular errors for throughput.
 *
 * All rows are stamped source = 'CSV' + is_active = true +
 * last_editor_device_id = null so the phone-side attribution renders
 * 'Portal' for the next pull.
 */
export async function bulkUpsertAccounts(
  rows: BulkAccountInput[],
  ownerId: string,
  signal?: AbortSignal
): Promise<BulkUpsertResult> {
  // Defence-in-depth: even though the caller passes ownerId, validate
  // against the live session before writing. Defends against a stale
  // useAuth() snapshot or a caller bug from spraying writes under the
  // wrong owner_id and tripping RLS only on the second hop.
  const liveOwnerId = await requireOwnerId();
  if (liveOwnerId !== ownerId) {
    throw new Error(
      'bulkUpsertAccounts: caller ownerId does not match active session — refusing to write.'
    );
  }
  const result: BulkUpsertResult = { inserted: 0, updated: 0, failed: 0, errors: [] };
  // R3 CSV-authority anchor. ONE snapshot per upload — every row in
  // this batch carries the same csv_imported_at so the phone-side
  // session-delete guard (session.endTime > account.csvImportedAt
  // means "session is newer, revert its contribution") treats all
  // rows in one CSV as landing at a single logical moment. Snapshot
  // OUTSIDE the loop: per-row timestamps would drift by the loop
  // duration and let a fast session-scan sneak between two rows'
  // effective CSV times, breaking the guard's ordering.
  //
  // DELIBERATELY distinct from updated_at (a maintained-by-trigger
  // column reflecting any write to the row, including cloud pulls
  // and portal name-edits). csv_imported_at must specifically track
  // "when did authoritative CSV data last stamp this row" so the
  // cascade can compare session date against CSV import date.
  const csvImportedAt = new Date().toISOString();
  for (const row of rows) {
    // P6γ MEDIUM cancellation: check the signal before each per-row
    // upsert so closing the import dialog mid-upload aborts the
    // remaining rows. The already-uploaded rows stay committed (CSV
    // re-import is idempotent via the owner_id+rd_number PK + the
    // deleted_at:null resurrect stamp, so the user can retry safely).
    if (signal?.aborted) {
      throw new DOMException('bulkUpsertAccounts cancelled', 'AbortError');
    }
    // last_paid_through is conditionally included: omitting the key
    // entirely tells PostgREST to leave the existing column value
    // alone on UPDATE. Including it with a value (or even null) would
    // overwrite. The null/undefined distinction matters here — we
    // build the object once and only attach the key when the CSV row
    // carried an explicit value.
    const payload: Record<string, unknown> = {
      rd_number: row.rdNumber,
      owner_id: ownerId,
      name: row.name,
      monthly_amount: row.monthlyAmount,
      source: 'CSV',
      is_active: true,
      last_editor_device_id: null,
      // Resurrect tombstoned rows on CSV re-import. Mirrors phone-side
      // RdAccountDao.resurrectTombstone(). Without this the upsert
      // updates the row but leaves deleted_at set, making it invisible
      // to fetchAccounts() (which filters .is('deleted_at', null)).
      deleted_at: null,
      // R3 cascade anchor — see loop-preamble comment for why this
      // is a single snapshot, and why we don't reuse updated_at.
      csv_imported_at: csvImportedAt,
    };
    if (row.lastPaidThrough != null) {
      payload.last_paid_through = row.lastPaidThrough;
    }
    const { error } = await supabase
      .from('rd_accounts')
      .upsert(payload, { onConflict: 'owner_id,rd_number' });
    if (error) {
      result.failed++;
      result.errors.push({ rdNumber: row.rdNumber, message: error.message });
    } else {
      // PostgREST upsert doesn't expose insert-vs-update distinction
      // without an extra SELECT round-trip; treat all successes as
      // "inserted" for the post-import toast. The portal owner cares
      // about "did it land in cloud?", not the SQL verb.
      result.inserted++;
    }
  }
  return result;
}
