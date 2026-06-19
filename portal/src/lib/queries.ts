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
