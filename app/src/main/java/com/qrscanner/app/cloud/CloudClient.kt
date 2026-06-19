package com.qrscanner.app.cloud

import com.qrscanner.app.cloud.dto.DeviceDto
import com.qrscanner.app.cloud.dto.RdNumberDto
import com.qrscanner.app.cloud.dto.ScanLotDto
import com.qrscanner.app.cloud.dto.ScanSessionDto
import kotlinx.coroutines.flow.Flow

/**
 * Boundary between Android sync code and the Supabase backend.
 *
 * Every method is suspending and throws [CloudException] on failure.
 * The interface deliberately exposes DTOs (not Room entities) so the
 * sync repository owns entity↔DTO conversion and the cloud layer never
 * has to know about Room. This also makes the boundary mockable — the
 * default implementation [com.qrscanner.app.cloud.SupabaseCloudClient]
 * hits real Supabase; a test or overnight-mock implementation can
 * satisfy the same contract.
 *
 * Spec reference: §3, §12, §14.
 */
interface CloudClient {

    /**
     * Reactive view of the current auth state. UI observes this to swap
     * between SignInScreen / FirstRunSetupScreen / HomeScreen. Workers
     * read [currentSession] for the access token they need to attach to
     * outbound requests.
     */
    val sessionStatus: Flow<CloudSessionStatus>

    /** Snapshot read of the current session, or null when signed out. */
    fun currentSession(): CloudSession?

    /** Signs in with email + password. On success [sessionStatus] flips to [CloudSessionStatus.Authenticated]. */
    suspend fun signIn(email: String, password: String)

    /** Signs out locally only — preserves the cached session token on the server. */
    suspend fun signOut()

    /**
     * Inserts or updates the device row representing this phone. Idempotent
     * via `id` (the local-generated UUID). Called on first-run setup and
     * subsequently on every successful push (to update `last_seen_at`).
     */
    suspend fun upsertDevice(device: DeviceDto): DeviceDto

    /**
     * Server-assigned display number for a brand-new session. Wrapped in a
     * Postgres advisory lock so two concurrent phones can never collide.
     * Falls back to a local tentative number when offline — the push
     * worker reconciles the canonical number when it eventually pushes.
     */
    suspend fun nextDisplayNumber(ownerId: String): Int

    /** Upserts a finalized session row. PK conflict on `id` → merge. */
    suspend fun upsertSession(session: ScanSessionDto): ScanSessionDto
    suspend fun upsertLot(lot: ScanLotDto): ScanLotDto
    suspend fun upsertRdNumber(rdNumber: RdNumberDto): RdNumberDto

    /**
     * Soft-deletes a session by stamping `deleted_at`. Returns the
     * updated row including the server-side updated_at trigger value
     * for conflict-resolution bookkeeping.
     */
    suspend fun tombstoneSession(sessionCloudId: String, deletedAt: Long): ScanSessionDto

    /**
     * Delta pull — returns rows from the four tables whose updated_at or
     * deleted_at exceed [since]. Paginated server-side at 500 rows per
     * table. The repository merges these into local Room per spec §11.
     */
    suspend fun pullChangesSince(ownerId: String, since: Long): CloudDelta

    /**
     * Opens a realtime subscription for the owner's data. The Flow emits
     * one [CloudRealtimePayload] per Postgres change; the repository
     * uses each as a "go look" trigger to run a targeted pull. The flow
     * completes when the caller cancels the collecting coroutine, which
     * unsubscribes the underlying channel.
     */
    fun observeRealtimeChanges(ownerId: String): Flow<CloudRealtimePayload>
}

/** Auth state surfaced to UI and workers. */
sealed interface CloudSessionStatus {
    data object Initializing : CloudSessionStatus
    data class Authenticated(val session: CloudSession) : CloudSessionStatus
    data object NotAuthenticated : CloudSessionStatus
    data class RefreshFailure(val cause: String) : CloudSessionStatus
}

data class CloudSession(
    val ownerId: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSec: Long
)

/** Batched pull response — see [CloudClient.pullChangesSince]. */
data class CloudDelta(
    val devices: List<DeviceDto>,
    val sessions: List<ScanSessionDto>,
    val lots: List<ScanLotDto>,
    val rdNumbers: List<RdNumberDto>,
    val highWaterMark: Long
)

/** Realtime payload identifying what changed (the repo follows up with a targeted pull). */
data class CloudRealtimePayload(
    val table: CloudTable,
    val cloudId: String,
    val event: CloudRealtimeEvent
)

enum class CloudTable { DEVICES, SCAN_SESSIONS, SCAN_LOTS, RD_NUMBERS }
enum class CloudRealtimeEvent { INSERT, UPDATE, DELETE }

/** All cloud errors funnel through this. The repository decides retry semantics. */
sealed class CloudException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    class AuthExpired(cause: Throwable? = null) : CloudException("auth expired", cause)
    class InvalidCredentials(cause: Throwable? = null) : CloudException("invalid credentials", cause)
    class NotConfigured : CloudException("cloud not configured")
    class Network(cause: Throwable) : CloudException("network error: ${cause.message}", cause)
    class Server(val status: Int, body: String?) : CloudException("server $status: $body")
    class Conflict(message: String) : CloudException(message)
    class Unknown(cause: Throwable) : CloudException("unknown: ${cause.message}", cause)
}
