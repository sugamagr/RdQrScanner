import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { supabase } from './supabase';
import { useAuth } from './auth';

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

    const channel = supabase
      .channel(`portal-${userId}`)
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'scan_sessions' },
        () => {
          qc.invalidateQueries({ queryKey: ['sessions'] });
          qc.invalidateQueries({ queryKey: ['session'] });
        }
      )
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'scan_lots' },
        () => {
          qc.invalidateQueries({ queryKey: ['lots'] });
          qc.invalidateQueries({ queryKey: ['session'] });
        }
      )
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'rd_numbers' },
        () => {
          qc.invalidateQueries({ queryKey: ['rd'] });
          qc.invalidateQueries({ queryKey: ['rd-search'] });
        }
      )
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'devices' },
        () => {
          qc.invalidateQueries({ queryKey: ['devices'] });
        }
      )
      .subscribe();

    return () => {
      void supabase.removeChannel(channel);
    };
  }, [userId, qc]);
}
