import { supabase } from './supabase';
import type {
  DeviceRow,
  RdNumberRow,
  ScanLotRow,
  ScanSessionRow,
} from '../types/db';

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
      created_at, updated_at, deleted_at,
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
