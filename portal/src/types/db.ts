// Mirror of cloud/schema.sql tables (verified against the live schema
// file, not invented). Hand-written rather than generated because the
// schema is small + stable; revisit with `supabase gen types
// typescript` if drift becomes a concern.

export interface DeviceRow {
  id: string;
  owner_id: string;
  device_name: string;
  device_model: string | null;
  first_seen_at: string;
  last_seen_at: string;
  app_version: string | null;
  created_at: string;
  updated_at: string;
}

export interface ScanSessionRow {
  id: string;
  owner_id: string;
  device_id: string;
  operator_name: string | null;
  display_number: number;
  start_time: string;
  end_time: string;
  total_lots: number;
  total_rd_numbers: number;
  default_count: number;
  created_at: string;
  updated_at: string;
  deleted_at: string | null;
}

export interface ScanLotRow {
  id: string;
  owner_id: string;
  session_id: string;
  lot_number: number;
  timestamp: string;
  created_at: string;
  updated_at: string;
  deleted_at: string | null;
}

export interface RdNumberRow {
  id: string;
  owner_id: string;
  lot_id: string;
  number: string;
  position: number;
  scanned_at: string;
  months_paid: number;
  months_list: string | null;
  created_at: string;
  updated_at: string;
  deleted_at: string | null;
}

export interface SessionWithDevice extends ScanSessionRow {
  device: Pick<DeviceRow, 'id' | 'device_name'> | null;
}
