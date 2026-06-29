import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { supabase } from './supabase';
import { useAuth } from './useAuth';

/**
 * Subscribes to Supabase Realtime for the four data tables and
 * invalidates the relevant TanStack Query cache keys when changes
 * arrive. Resolves three Phase 4 boundary review findings at once:
 *
 *  - bg_d6be2052 W1: spec §16 violation — portal must subscribe to
 *    realtime for sub-second cross-device latency.
 *  - bg_369b0f00 #3: cross-tab sync gap — two tabs visible side-by-
 *    side never refreshed each other.
 *  - bg_369b0f00 #4: open-tab-during-delete stale view — Sessions
 *    list invalidates on rd_numbers / scan_lots / scan_sessions
 *    changes so a tombstone-from-another-phone collapses the row.
 *
 * One channel for everything — Supabase Realtime quotas (2 channels
 * free tier per project) make multiplexing the right move. Per-table
 * .on() filters give us fine-grained invalidation without burning
 * channel slots.
 *
 * Cleanup is symmetric: every subscribe is paired with an unsubscribe
 * on unmount or session change so multiple sign-in/sign-out cycles
 * don't leak channels.
 */
export function useRealtimeSync(): void {
  const { session } = useAuth();
  const qc = useQueryClient();
  const userId = session?.user?.id;

  useEffect(() => {
    if (!userId) return;

    // Each subscription is filtered by `owner_id=eq.${userId}` so the
    // Supabase realtime server filters BEFORE delivery. Without the
    // filter, the server broadcasts the row to every connected portal
    // and RLS filters client-side AFTER the row already crossed the
    // wire — bandwidth waste and realtime quota burn proportional to
    // the number of concurrent owners. Spec §14 explicitly mandates
    // the filter on phone-side; portal must mirror.
    const ownerFilter = `owner_id=eq.${userId}`;

    const channel = supabase
      .channel(`portal-${userId}`)
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'scan_sessions', filter: ownerFilter },
        () => {
          qc.invalidateQueries({ queryKey: ['sessions'] });
          qc.invalidateQueries({ queryKey: ['session'] });
          // Activity feed is materialised from scan_sessions; invalidate
          // it on every session change so finalize + soft-delete events
          // show up live without the owner refreshing /activity.
          qc.invalidateQueries({ queryKey: ['activity'] });
          qc.invalidateQueries({ queryKey: ['dashboard'] });
        }
      )
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'scan_lots', filter: ownerFilter },
        () => {
          qc.invalidateQueries({ queryKey: ['lots'] });
          qc.invalidateQueries({ queryKey: ['session'] });
          qc.invalidateQueries({ queryKey: ['dashboard'] });
        }
      )
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'rd_numbers', filter: ownerFilter },
        () => {
          qc.invalidateQueries({ queryKey: ['rd'] });
          qc.invalidateQueries({ queryKey: ['rd-search'] });
          qc.invalidateQueries({ queryKey: ['lot-totals-excluding'] });
          // Defaulter edits (months_paid > 1) are an Activity feed
          // category; invalidate so a phone-side correction reflows
          // the portal feed live.
          qc.invalidateQueries({ queryKey: ['activity'] });
          qc.invalidateQueries({ queryKey: ['dashboard'] });
        }
      )
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'devices', filter: ownerFilter },
        () => {
          qc.invalidateQueries({ queryKey: ['devices'] });
          qc.invalidateQueries({ queryKey: ['dashboard'] });
        }
      )
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'rd_accounts', filter: ownerFilter },
        () => {
          qc.invalidateQueries({ queryKey: ['accounts'] });
          qc.invalidateQueries({ queryKey: ['account-for-rd'] });
          qc.invalidateQueries({ queryKey: ['lot-totals-excluding'] });
          qc.invalidateQueries({ queryKey: ['activity'] });
          qc.invalidateQueries({ queryKey: ['dashboard'] });
        }
      )
      .subscribe();

    // Network reconnect handler: supabase-js auto-reconnects the
    // WebSocket but postgres_changes has no backfill — events that
    // fired during the disconnect window are lost. Invalidating every
    // query on `online` (network back) + `visibilitychange` (tab back
    // from background) catches phones that pushed during a laptop
    // sleep or network blip.
    const refetchAll = () => qc.invalidateQueries();
    const onVisible = () => {
      if (document.visibilityState === 'visible') refetchAll();
    };
    window.addEventListener('online', refetchAll);
    document.addEventListener('visibilitychange', onVisible);

    return () => {
      void supabase.removeChannel(channel);
      window.removeEventListener('online', refetchAll);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [userId, qc]);
}
