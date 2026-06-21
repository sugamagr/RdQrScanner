package com.qrscanner.app.cloud

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.qrscanner.app.BuildConfig
import com.qrscanner.app.cloud.dto.DeviceDto
import com.qrscanner.app.cloud.dto.RdAccountDto
import com.qrscanner.app.cloud.dto.RdNumberDto
import com.qrscanner.app.cloud.dto.ScanLotDto
import com.qrscanner.app.cloud.dto.ScanSessionDto
import com.russhwolf.settings.SharedPreferencesSettings
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.SettingsSessionManager
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * Production [CloudClient] implementation backed by supabase-kt 3.1.4.
 *
 * Spec §3 (architecture), §9 (auth), §12 (API contract), §14 (realtime).
 *
 * The SDK is wired with:
 * - Auth via [Email] sign-in; encrypted session storage via
 *   [EncryptedSessionManager] with API 26-28 fallback to plain
 *   SharedPreferences (spec §15 amendment, commit 119f796).
 * - Postgrest for REST CRUD against the four data tables.
 * - Realtime via the okhttp Ktor engine (required for WebSockets).
 *
 * Errors are translated into the sealed [CloudException] hierarchy so
 * the repository can pattern-match on retry semantics.
 */
class SupabaseCloudClient(
    context: Context,
    private val supabaseUrl: String = BuildConfig.SUPABASE_URL,
    private val supabaseAnonKey: String = BuildConfig.SUPABASE_ANON_KEY
) : CloudClient {

    init {
        // Fail fast with a structured exception so QRScannerApp can render
        // a 'Cloud sync not configured' screen instead of crashing on the
        // SDK's malformed-URL error (oracle round 6 BLOCKER #3). Without
        // this guard a missing local.properties produces a stack trace on
        // first composition with no actionable feedback.
        require(supabaseUrl.isNotBlank() && supabaseAnonKey.isNotBlank()) {
            "SUPABASE_URL / SUPABASE_ANON_KEY missing from local.properties — see CLOUD_SYNC_SPEC §20"
        }
    }

    // Dedicated long-lived scope for fire-and-forget cleanup of Realtime
    // resources. observeRealtimeChanges' awaitClose hook needs to call
    // suspending unsubscribe() + removeChannel() after the flow's own
    // ProducerScope has already cancelled, so it can't use that scope —
    // we'd race the cancellation and leave dangling channels (oracle round 4
    // WARNING #10). SupervisorJob isolates cleanup failures so one bad
    // unsubscribe doesn't poison the next.
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val supabase: SupabaseClient = createSupabaseClient(
        supabaseUrl = supabaseUrl,
        supabaseKey = supabaseAnonKey
    ) {
        install(Auth) {
            sessionManager = EncryptedSessionManager(context.applicationContext)
            autoLoadFromStorage = true
            autoSaveToStorage = true
            alwaysAutoRefresh = true
        }
        install(Postgrest)
        install(Realtime) {
            reconnectDelay = REALTIME_RECONNECT_DELAY
        }
    }

    override val sessionStatus: Flow<CloudSessionStatus> =
        supabase.auth.sessionStatus.map { status ->
            when (status) {
                is SessionStatus.Initializing -> CloudSessionStatus.Initializing
                is SessionStatus.Authenticated -> CloudSessionStatus.Authenticated(status.session.toCloud())
                is SessionStatus.NotAuthenticated -> CloudSessionStatus.NotAuthenticated
                is SessionStatus.RefreshFailure -> CloudSessionStatus.RefreshFailure(
                    // P2β NITPICK: full RefreshFailureCause.toString() includes
                    // the nested Throwable/RestException stack + body, which
                    // can carry token fragments and KeyStore internals into
                    // the visible auth banner. Sanitize to the underlying
                    // exception's message only, truncated to a safe length.
                    when (val cause = status.cause) {
                        is io.github.jan.supabase.auth.status.RefreshFailureCause.NetworkError ->
                            cause.exception.message?.take(120) ?: "refresh failed"
                        is io.github.jan.supabase.auth.status.RefreshFailureCause.InternalServerError ->
                            cause.exception.message?.take(120) ?: "refresh failed"
                    }
                )
            }
        }

    override fun currentSession(): CloudSession? =
        supabase.auth.currentSessionOrNull()?.toCloud()

    override suspend fun signIn(email: String, password: String) = runCloud {
        supabase.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    override suspend fun signOut() = runCloud {
        supabase.auth.signOut(SignOutScope.LOCAL)
    }

    override suspend fun upsertDevice(device: DeviceDto): DeviceDto = runCloud {
        supabase.postgrest.from(TABLE_DEVICES)
            .upsert(device) {
                onConflict = "id"
                select()
            }
            .decodeSingle()
    }

    override suspend fun nextDisplayNumber(ownerId: String): Int = runCloud {
        // PostgREST returns the scalar RPC result as the raw JSON body.
        // A Postgres `int` returns as `42` (no quotes); a `text` returns
        // as `"42"` (with quotes). Trim quotes + whitespace before
        // parsing so a future migration that changes the function's
        // return type doesn't silently break us (oracle round 4 W9).
        val raw = supabase.postgrest.rpc(
            function = "next_display_number",
            parameters = buildJsonObject { put("p_owner_id", ownerId) }
        ).data.trim().trim('"')
        raw.toIntOrNull() ?: error("next_display_number returned non-int: '$raw'")
    }

    override suspend fun upsertSession(session: ScanSessionDto): ScanSessionDto = runCloud {
        supabase.postgrest.from(TABLE_SCAN_SESSIONS)
            .upsert(session) {
                onConflict = "id"
                select()
            }
            .decodeSingle()
    }

    override suspend fun upsertLot(lot: ScanLotDto): ScanLotDto = runCloud {
        supabase.postgrest.from(TABLE_SCAN_LOTS)
            .upsert(lot) {
                onConflict = "id"
                select()
            }
            .decodeSingle()
    }

    override suspend fun upsertRdNumber(rdNumber: RdNumberDto): RdNumberDto = runCloud {
        supabase.postgrest.from(TABLE_RD_NUMBERS)
            .upsert(rdNumber) {
                onConflict = "id"
                select()
            }
            .decodeSingle()
    }

    override suspend fun upsertRdAccount(account: RdAccountDto): RdAccountDto = runCloud {
        // Composite PK is (owner_id, rd_number). Specifying both on
        // onConflict so PostgREST routes the duplicate-key into the
        // correct DO UPDATE branch matching the table's PRIMARY KEY.
        supabase.postgrest.from(TABLE_RD_ACCOUNTS)
            .upsert(account) {
                onConflict = "owner_id,rd_number"
                select()
            }
            .decodeSingle()
    }

    override suspend fun tombstoneSession(sessionCloudId: String, deletedAt: Long): ScanSessionDto = runCloud {
        val iso = com.qrscanner.app.cloud.mappers.IsoTime.fromEpochMillis(deletedAt)
        supabase.postgrest.from(TABLE_SCAN_SESSIONS)
            .update({ set("deleted_at", iso) }) {
                filter { eq("id", sessionCloudId) }
                select()
            }
            .decodeSingle()
    }

    override suspend fun pullChangesSince(ownerId: String, since: Long): CloudDelta = runCloud {
        val sinceIso = com.qrscanner.app.cloud.mappers.IsoTime.fromEpochMillis(since)
        // Phase 5 T5.12 (boundary adversarial #3 blocker fix): cap ordering
        // on (updated_at, id) so a page full of rows sharing the exact same
        // millisecond timestamp doesn't lose the tail. Without the id tie-
        // breaker, repeated batches of 500 rows with identical updated_at
        // would have the drain loop request `updated_at > X` and miss
        // rows 501-600 that all share `updated_at = X`. With (updated_at,
        // id) ordering + an explicit secondary order, the page boundary
        // becomes deterministic and the next iteration's `>=` on
        // updated_at + the merge's findByCloudId dedupe correctly resume
        // from the tail. We keep the millisecond cursor on the client for
        // backward compatibility with device_settings; the dedupe via
        // findByCloudId during merge ensures no double-apply.

        val devices = supabase.postgrest.from(TABLE_DEVICES)
            .select(columns = Columns.ALL) {
                filter {
                    eq("owner_id", ownerId)
                    gte("updated_at", sinceIso)
                }
                order("updated_at", Order.ASCENDING)
                order("id", Order.ASCENDING)
                limit(PULL_PAGE_SIZE.toLong())
            }
            .decodeList<DeviceDto>()

        val sessions = supabase.postgrest.from(TABLE_SCAN_SESSIONS)
            .select(columns = Columns.ALL) {
                filter {
                    eq("owner_id", ownerId)
                    or {
                        gte("updated_at", sinceIso)
                        gt("deleted_at", sinceIso)
                    }
                }
                order("updated_at", Order.ASCENDING)
                order("id", Order.ASCENDING)
                limit(PULL_PAGE_SIZE.toLong())
            }
            .decodeList<ScanSessionDto>()

        val lots = supabase.postgrest.from(TABLE_SCAN_LOTS)
            .select(columns = Columns.ALL) {
                filter {
                    eq("owner_id", ownerId)
                    or {
                        gte("updated_at", sinceIso)
                        gt("deleted_at", sinceIso)
                    }
                }
                order("updated_at", Order.ASCENDING)
                order("id", Order.ASCENDING)
                limit(PULL_PAGE_SIZE.toLong())
            }
            .decodeList<ScanLotDto>()

        val rdNumbers = supabase.postgrest.from(TABLE_RD_NUMBERS)
            .select(columns = Columns.ALL) {
                filter {
                    eq("owner_id", ownerId)
                    or {
                        gte("updated_at", sinceIso)
                        gt("deleted_at", sinceIso)
                    }
                }
                order("updated_at", Order.ASCENDING)
                order("id", Order.ASCENDING)
                limit(PULL_PAGE_SIZE.toLong())
            }
            .decodeList<RdNumberDto>()

        val rdAccounts = supabase.postgrest.from(TABLE_RD_ACCOUNTS)
            .select(columns = Columns.ALL) {
                filter {
                    eq("owner_id", ownerId)
                    or {
                        gte("updated_at", sinceIso)
                        gt("deleted_at", sinceIso)
                    }
                }
                order("updated_at", Order.ASCENDING)
                // rd_accounts has NO synthetic `id` column. Its PK is the
                // composite (owner_id, rd_number) per cloud/schema.sql line
                // 301. Use rd_number as the tie-breaker — PostgREST returns
                // 400/42703 ("column id does not exist") otherwise and the
                // entire pull cycle fails for this table.
                order("rd_number", Order.ASCENDING)
                limit(PULL_PAGE_SIZE.toLong())
            }
            .decodeList<RdAccountDto>()

        val highWater = maxOf(
            since,
            devices.maxOfOrNull { com.qrscanner.app.cloud.mappers.IsoTime.toEpochMillis(it.updatedAt) } ?: since,
            sessions.maxOfOrNull { com.qrscanner.app.cloud.mappers.IsoTime.toEpochMillis(it.updatedAt) } ?: since,
            lots.maxOfOrNull { com.qrscanner.app.cloud.mappers.IsoTime.toEpochMillis(it.updatedAt) } ?: since,
            rdNumbers.maxOfOrNull { com.qrscanner.app.cloud.mappers.IsoTime.toEpochMillis(it.updatedAt) } ?: since,
            rdAccounts.maxOfOrNull { com.qrscanner.app.cloud.mappers.IsoTime.toEpochMillis(it.updatedAt) } ?: since
        )

        val anyPageFull = devices.size >= PULL_PAGE_SIZE ||
            sessions.size >= PULL_PAGE_SIZE ||
            lots.size >= PULL_PAGE_SIZE ||
            rdNumbers.size >= PULL_PAGE_SIZE ||
            rdAccounts.size >= PULL_PAGE_SIZE

        CloudDelta(
            devices = devices,
            sessions = sessions,
            lots = lots,
            rdNumbers = rdNumbers,
            rdAccounts = rdAccounts,
            highWaterMark = highWater,
            pageWasFull = anyPageFull
        )
    }

    override fun observeRealtimeChanges(ownerId: String): Flow<CloudRealtimePayload> = callbackFlow {
        supabase.realtime.connect()
        // P2β NITPICK: channel id was "rt:owner:$ownerId" — embedding the
        // auth uuid in the channel name leaks a stable identifier into
        // any server-side logs that capture channel names. The JWT already
        // scopes the subscription to this owner, so a per-owner namespace
        // adds nothing the realtime server can't already see. Hash the
        // id so the channel name carries no correlatable PII.
        val channel = supabase.realtime.channel("rt:owner:${ownerId.hashedNamespace()}")

        // supabase-kt 3.1.4: PostgresChangeFilter.filter is a private var
        // exposed via filter(column, operator, value). Direct assignment
        // (the 2.x API) breaks at compile. The four channels all scope
        // by owner_id so RLS-equivalent filtering happens at the realtime
        // server layer too, avoiding cross-tenant payload delivery.
        val sessionFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = TABLE_SCAN_SESSIONS
            filter("owner_id", FilterOperator.EQ, ownerId)
        }
        val lotFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = TABLE_SCAN_LOTS
            filter("owner_id", FilterOperator.EQ, ownerId)
        }
        val rdFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = TABLE_RD_NUMBERS
            filter("owner_id", FilterOperator.EQ, ownerId)
        }
        val deviceFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = TABLE_DEVICES
            filter("owner_id", FilterOperator.EQ, ownerId)
        }
        val accountFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = TABLE_RD_ACCOUNTS
            filter("owner_id", FilterOperator.EQ, ownerId)
        }

        listOf(
            sessionFlow to CloudTable.SCAN_SESSIONS,
            lotFlow to CloudTable.SCAN_LOTS,
            rdFlow to CloudTable.RD_NUMBERS,
            deviceFlow to CloudTable.DEVICES,
            accountFlow to CloudTable.RD_ACCOUNTS
        ).forEach { (flow, table) ->
            launch {
                flow.collect { action ->
                    val payload = action.toPayload(table) ?: return@collect
                    // P2γ NITPICK: callbackFlow's default buffer is 64. On a
                    // burst the producer can outrun the slow consumer
                    // (handleRealtimeChange -> runPull, which is mutex-
                    // serialized). trySend silently drops on full — the
                    // 5-min poll backstop recovers, but the dropped events
                    // are invisible. Log so the diagnostic trail exists.
                    trySend(payload).onFailure {
                        Log.w(TAG, "realtime buffer full, dropping ${payload.event} on ${payload.table}; poll backstop will catch up")
                    }
                }
            }
        }

        channel.subscribe()
        awaitClose {
            cleanupScope.launch {
                runCatching { channel.unsubscribe() }
                runCatching { supabase.realtime.removeChannel(channel) }
            }
        }
    }

    private suspend inline fun <T> runCloud(block: () -> T): T {
        try {
            return block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // P2γ NITPICK: structured concurrency requires cancellation
            // to propagate unwrapped. The Throwable catch below would
            // wrap it in CloudException.Unknown, breaking the cancel
            // contract for any caller that's racing this suspend fn.
            throw e
        } catch (e: RestException) {
            // Supabase Auth returns 400 + body containing "invalid_grant" /
            // "Invalid login credentials" for wrong email or password. Map
            // both the 400 case and the 401/403 case to InvalidCredentials
            // so the UI shows "Email or password incorrect" instead of a
            // generic "server error" (oracle round 6 BLOCKER #2).
            throw when (e.statusCode) {
                400 -> if (looksLikeBadAuth(e)) CloudException.InvalidCredentials(e)
                       else if (looksLikeSchemaMissing(e)) CloudException.SchemaMissing(e.error.orEmpty())
                       else CloudException.Server(e.statusCode, e.error)
                401, 403 -> if (looksLikeBadAuth(e)) CloudException.InvalidCredentials(e)
                            else CloudException.AuthExpired(e)
                404 -> if (looksLikeSchemaMissing(e)) CloudException.SchemaMissing(e.error.orEmpty())
                       else CloudException.Server(e.statusCode, e.error)
                409 -> CloudException.Conflict(e.message ?: "conflict")
                in 500..599 -> CloudException.Server(e.statusCode, e.error)
                else -> CloudException.Server(e.statusCode, e.error)
            }
        } catch (e: HttpRequestException) {
            throw CloudException.Network(e)
        } catch (e: IOException) {
            throw CloudException.Network(e)
        } catch (e: Throwable) {
            throw CloudException.Unknown(e)
        }
    }

    private fun looksLikeBadAuth(e: RestException): Boolean {
        val body = (e.error.orEmpty() + " " + (e.message.orEmpty())).lowercase()
        // Newer Supabase Auth (post PR #1721) returns the underscore form
        // "invalid_credentials" in the JSON error code field; the space
        // form "invalid credentials" remains for backward compat. Match
        // both so a Supabase server upgrade doesn't silently flip the
        // user-facing copy from "Email or password incorrect" back to
        // the generic "server error".
        return "invalid_grant" in body ||
            "invalid login credentials" in body ||
            "invalid credentials" in body ||
            "invalid_credentials" in body
    }

    /**
     * PostgREST surfaces missing tables/RPCs with code `PGRST205` (table)
     * or `PGRST202` (RPC), and Postgres throws `42P01 relation does not
     * exist` / `42883 function does not exist` when the schema hasn't
     * been applied. Match any of those to route to friendly
     * "schema not applied" UI instead of perpetual retry. Phase 5 T5.1.
     */
    private fun looksLikeSchemaMissing(e: RestException): Boolean {
        val body = (e.error.orEmpty() + " " + (e.message.orEmpty())).lowercase()
        return "pgrst205" in body ||
            "pgrst202" in body ||
            "42p01" in body ||
            "42883" in body ||
            "could not find the table" in body ||
            "could not find the function" in body ||
            "relation" in body && "does not exist" in body
    }

    private fun UserSession.toCloud(): CloudSession {
        val user = this.user
        return CloudSession(
            ownerId = user?.id ?: error("UserSession.user.id missing"),
            email = user.email ?: "",
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochSec = expiresAt?.epochSeconds ?: 0L
        )
    }

    private fun PostgresAction.toPayload(table: CloudTable): CloudRealtimePayload? {
        // rd_accounts has composite PK (owner_id, rd_number) with NO 'id'
        // column. Every other table has a synthetic uuid 'id' PK. Reading
        // record["id"] on an rd_accounts payload returns null and the
        // ?: return null upstream silently drops every realtime event for
        // that table — phone never gets portal-side account edits until
        // the 5-min poll backstop. Branch on table so rd_accounts pulls
        // rd_number (the natural key) instead.
        val idKey = if (table == CloudTable.RD_ACCOUNTS) "rd_number" else "id"
        return when (this) {
            is PostgresAction.Insert -> CloudRealtimePayload(
                table = table,
                cloudId = record[idKey]?.toString()?.trim('"') ?: return null,
                event = CloudRealtimeEvent.INSERT
            )
            is PostgresAction.Update -> CloudRealtimePayload(
                table = table,
                cloudId = record[idKey]?.toString()?.trim('"') ?: return null,
                event = CloudRealtimeEvent.UPDATE
            )
            is PostgresAction.Delete -> CloudRealtimePayload(
                table = table,
                cloudId = oldRecord[idKey]?.toString()?.trim('"') ?: return null,
                event = CloudRealtimeEvent.DELETE
            )
            is PostgresAction.Select -> null
        }
    }

    companion object {
        private const val TAG = "SupabaseCloudClient"
        private const val TABLE_DEVICES = "devices"
        private const val TABLE_SCAN_SESSIONS = "scan_sessions"
        private const val TABLE_SCAN_LOTS = "scan_lots"
        private const val TABLE_RD_NUMBERS = "rd_numbers"
        private const val TABLE_RD_ACCOUNTS = "rd_accounts"
        private const val PULL_PAGE_SIZE = 500

        // Balances quick recovery vs avoiding a thundering-herd reconnect
        // storm when many devices come back online together (e.g. after a
        // regional Supabase incident). 5s is the supabase-kt default and
        // the floor below which Realtime starts rate-limiting the
        // websocket handshake server-side.
        private val REALTIME_RECONNECT_DELAY = 5.seconds

        // Channel-name namespacing helper — hashes the ownerId so the
        // realtime channel name carries no correlatable PII while still
        // partitioning subscriptions per owner deterministically. The
        // toUInt cast drops the sign so the namespace doesn't look like
        // a "negative id" in any log surface.
        private fun String.hashedNamespace(): String = hashCode().toUInt().toString(16)
    }
}

/**
 * Custom [SessionManager] backed by [EncryptedSharedPreferences] with a
 * fallback path for the broken-Keystore device class on API 26-28
 * (spec §15 amendment). Falls back to plain [android.content.SharedPreferences]
 * with a WARN log; the rest of auth keeps working with an unencrypted
 * token. The cost is the JWT on disk in plaintext on those specific
 * devices, which is the same security posture as any non-banking app
 * on those phones.
 */
private class EncryptedSessionManager(context: Context) : SessionManager {

    private val settings = run {
        val prefs = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // P2β NITPICK: passing the raw Throwable to Log.w prints the
            // full stack trace, which on some OEM builds includes
            // KeyStore alias names + internal state. Truncate to just
            // the message so the diagnostic is still useful but the
            // logcat surface stays clean.
            Log.w(TAG, "EncryptedSharedPreferences unavailable, falling back to plain prefs: ${e.message?.take(120)}")
            context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        }
        SharedPreferencesSettings(prefs)
    }

    private val delegate = SettingsSessionManager(settings, key = SESSION_KEY)

    override suspend fun saveSession(session: UserSession) = delegate.saveSession(session)
    override suspend fun loadSession(): UserSession? = delegate.loadSession()
    override suspend fun deleteSession() = delegate.deleteSession()

    companion object {
        private const val TAG = "EncryptedSessionMgr"
        private const val ENCRYPTED_PREFS_NAME = "supabase_session_encrypted"
        private const val FALLBACK_PREFS_NAME = "supabase_session_fallback"
        private const val SESSION_KEY = "auth_session"
    }
}
