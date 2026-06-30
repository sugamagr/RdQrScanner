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
  // Sync diagnostics (cloud schema v11) — pushed by the phone on every
  // runPush() exit so the portal Devices page sees the same truth the
  // in-app sync pill shows. `null` last_sync_error / `0` pending_count
  // means caught up on the most recent cycle. `last_push_at` is null
  // for devices that registered but haven't completed a push yet.
  last_sync_error: string | null;
  pending_count: number;
  last_push_at: string | null;
  created_at: string;
  updated_at: string;
  // Soft-delete tombstone. Mirrors the cloud `deleted_at timestamptz`
  // column added by the v9 soft-delete migration. The portal Devices
  // page filters `is null` server-side, but any future code path that
  // reads a raw DeviceRow (e.g. an audit log, or a join surfaced by a
  // future view) must see the field on the type to stay sound.
  deleted_at: string | null;
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

/**
 * Derived view for the Activity page. The cloud has no `activity` table —
 * the feed is materialized in the portal by reading recent rows from
 * `scan_sessions`, `rd_numbers`, and `rd_accounts` and projecting them
 * into a uniform shape so the UI can render a single list.
 *
 * `actorLabel` follows the same attribution logic as the phone bell:
 *   - `null` last_editor_device_id (or null device row) → "Portal"
 *   - resolved device row → device_name (e.g. "Counter Phone")
 *
 * The portal user (you) is always the actor for portal-originated events;
 * the labels are useful when there are multiple phones in play.
 */
export type ActivityKind =
  | 'session_finalized'
  | 'session_deleted'
  | 'defaulter_edited'
  | 'account_added'
  | 'account_edited';

export interface ActivityRow {
  kind: ActivityKind;
  occurredAt: string;
  actorLabel: string;
  primary: string;
  secondary: string | null;
  linkTo: string | null;
}
