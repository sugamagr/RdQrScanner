import { supabase } from './supabase';
import type {
  AccountSource,
  DeviceRow,
  RdAccountRow,
  RdNumberRow,
  ScanLotRow,
  ScanSessionRow,
} from '../types/db';

/**
 * Resolves the current owner_id for defense-in-depth filtering on
 * mutations. RLS at cloud/schema.sql §318-326 already blocks cross-
 * owner writes, but adding the explicit `.eq('owner_id', x)` filter
 * here means the wire payload itself encodes the constraint and any
 * future RLS misconfiguration gets caught client-side before the
 * round trip. Throws if there's no live session so a caller never
 * silently runs an unfiltered mutation.
 */
async function requireOwnerId(): Promise<string> {
  const { data, error } = await supabase.auth.getUser();
  if (error) throw error;
  const ownerId = data.user?.id;
  if (!ownerId) {
    throw new Error('No active session — refusing to mutate without owner_id scope.');
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
  let query = supabase
    .from('scan_sessions')
    .select('*', { count: 'exact' })
    .is('deleted_at', null)
    .order('end_time', { ascending: false })
    .range(offset, to);

  const trimmed = search?.trim();
  if (trimmed) {
    const asNumber = Number(trimmed);
    if (!Number.isNaN(asNumber) && Number.isInteger(asNumber)) {
      query = query.eq('display_number', asNumber);
    }
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
  const { data, error } = await supabase
    .from('scan_sessions')
    .select('*')
    .eq('id', sessionId)
    .is('deleted_at', null)
    .maybeSingle();
  if (error) throw error;
  return (data as ScanSessionRow | null) ?? null;
}

export async function fetchLotsForSession(sessionId: string): Promise<ScanLotRow[]> {
  const { data, error } = await supabase
    .from('scan_lots')
    .select('*')
    .eq('session_id', sessionId)
    .is('deleted_at', null)
    .order('lot_number', { ascending: true });
  if (error) throw error;
  return (data ?? []) as ScanLotRow[];
}

export async function fetchRdNumbersForLots(lotIds: string[]): Promise<RdNumberRow[]> {
  if (lotIds.length === 0) return [];
  const { data, error } = await supabase
    .from('rd_numbers')
    .select('*')
    .in('lot_id', lotIds)
    .is('deleted_at', null)
    .order('position', { ascending: true });
  if (error) throw error;
  return (data ?? []) as RdNumberRow[];
}

export async function fetchDevices(): Promise<DeviceRow[]> {
  const { data, error } = await supabase
    .from('devices')
    .select('*')
    .order('last_seen_at', { ascending: false });
  if (error) throw error;
  return (data ?? []) as DeviceRow[];
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
 * Search rd_numbers by partial number. Limited to 100 hits — at scale
 * the owner should narrow the query rather than scroll thousands of
 * matches. Uses `ilike` for case-insensitive partial match. Postgres
 * trigram index lands in Phase 5 hardening.
 */
export async function searchRdNumbers(query: string): Promise<RdSearchHit[]> {
  const trimmed = query.trim();
  if (trimmed.length < 2) return [];

  const escaped = trimmed.replace(/[%_\\]/g, (c) => `\\${c}`);
  const { data, error } = await supabase
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
    .ilike('number', `%${escaped}%`)
    .is('deleted_at', null)
    .order('scanned_at', { ascending: false })
    .limit(100);

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
  const { data, error } = await supabase
    .from('rd_accounts')
    .select('*')
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

export interface BulkAccountInput {
  rdNumber: string;
  name: string;
  monthlyAmount: number;
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
  ownerId: string
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
  for (const row of rows) {
    const payload = {
      rd_number: row.rdNumber,
      owner_id: ownerId,
      name: row.name,
      monthly_amount: row.monthlyAmount,
      source: 'CSV' as AccountSource,
      is_active: true,
      last_editor_device_id: null,
      // Resurrect tombstoned rows on CSV re-import. Mirrors phone-side
      // RdAccountDao.resurrectTombstone(). Without this the upsert
      // updates the row but leaves deleted_at set, making it invisible
      // to fetchAccounts() (which filters .is('deleted_at', null)).
      deleted_at: null,
    };
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
