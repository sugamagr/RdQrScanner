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
  last_editor_device_id: string | null;
  created_at: string;
  updated_at: string;
  deleted_at: string | null;
}

/**
 * Customer-account profile (rd_accounts table). Composite PK is
 * (owner_id, rd_number) cloud-side; the portal never sees other
 * owners' rows due to RLS so we treat rd_number as the visible key.
 */
export type AccountSource = 'MANUAL' | 'CSV';

export interface RdAccountRow {
  rd_number: string;
  owner_id: string;
  name: string;
  monthly_amount: number;
  last_paid_through: string | null;
  source: AccountSource;
  is_active: boolean;
  account_opened_date: string | null;
  account_closing_date: string | null;
  last_editor_device_id: string | null;
  created_at: string;
  updated_at: string;
  deleted_at: string | null;
}
