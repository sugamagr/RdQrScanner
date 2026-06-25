package com.qrscanner.app.cloud.mappers

import java.time.Instant

/**
 * Locale-stable conversion between Postgres `timestamptz` (ISO-8601 UTC
 * strings) and epoch millis used by Room.
 *
 * Centralised here so every mapper round-trips through the same code
 * path. Parsing is fail-soft: a malformed string maps to 0L rather than
 * throwing, on the assumption that a corrupted server payload should
 * never bring down the sync worker.
 */
internal object IsoTime {

    fun toEpochMillis(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        return try {
            Instant.parse(iso).toEpochMilli()
        } catch (e: Exception) {
            // Diagnostic trail: a malformed timestamptz from cloud is a
            // server-side schema or trigger bug, not a phone bug. Log
            // it so the next sync diagnostics dump captures the
            // offending string — without this trail, the 0L fallback
            // silently lies about row freshness and corrupts LWW merge
            // ordering with no breadcrumb a developer can follow.
            android.util.Log.w("IsoTime", "malformed timestamp '$iso' → 0L", e)
            0L
        }
    }

    fun toEpochMillisOrNull(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            Instant.parse(iso).toEpochMilli()
        } catch (e: Exception) {
            android.util.Log.w("IsoTime", "malformed timestamp '$iso' → null", e)
            null
        }
    }

    fun fromEpochMillis(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).toString()

    fun fromEpochMillisOrNull(epochMs: Long?): String? =
        epochMs?.let { fromEpochMillis(it) }
}
