package com.qrscanner.app.cloud

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.qrscanner.app.BuildConfig
import com.qrscanner.app.cloud.dto.DeviceDto
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
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

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
            reconnectDelay = REALTIME_RECONNECT_DELAY_MS
        }
    }

    override val sessionStatus: Flow<CloudSessionStatus> =
        supabase.auth.sessionStatus.map { status ->
            when (status) {
                is SessionStatus.Initializing -> CloudSessionStatus.Initializing
                is SessionStatus.Authenticated -> CloudSessionStatus.Authenticated(status.session.toCloud())
                is SessionStatus.NotAuthenticated -> CloudSessionStatus.NotAuthenticated
                is SessionStatus.RefreshFailure -> CloudSessionStatus.RefreshFailure(status.cause.toString())
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
        supabase.postgrest.rpc(
            function = "next_display_number",
            parameters = buildJsonObject { put("p_owner_id", ownerId) }
        ).data.toIntOrNull() ?: error("next_display_number returned non-int: ${'$'}{this}")
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

        val devices = supabase.postgrest.from(TABLE_DEVICES)
            .select(columns = Columns.ALL) {
                filter {
                    eq("owner_id", ownerId)
                    gt("updated_at", sinceIso)
                }
                order("updated_at", Order.ASCENDING)
                limit(PULL_PAGE_SIZE.toLong())
            }
            .decodeList<DeviceDto>()

        val sessions = supabase.postgrest.from(TABLE_SCAN_SESSIONS)
            .select(columns = Columns.ALL) {
                filter {
                    eq("owner_id", ownerId)
                    or {
                        gt("updated_at", sinceIso)
                        gt("deleted_at", sinceIso)
                    }
                }
                order("updated_at", Order.ASCENDING)
                limit(PULL_PAGE_SIZE.toLong())
            }
            .decodeList<ScanSessionDto>()

        val lots = supabase.postgrest.from(TABLE_SCAN_LOTS)
            .select(columns = Columns.ALL) {
                filter {
                    eq("owner_id", ownerId)
                    or {
                        gt("updated_at", sinceIso)
                        gt("deleted_at", sinceIso)
                    }
                }
                order("updated_at", Order.ASCENDING)
                limit(PULL_PAGE_SIZE.toLong())
            }
            .decodeList<ScanLotDto>()

        val rdNumbers = supabase.postgrest.from(TABLE_RD_NUMBERS)
            .select(columns = Columns.ALL) {
                filter {
                    eq("owner_id", ownerId)
                    or {
                        gt("updated_at", sinceIso)
                        gt("deleted_at", sinceIso)
                    }
                }
                order("updated_at", Order.ASCENDING)
                limit(PULL_PAGE_SIZE.toLong())
            }
            .decodeList<RdNumberDto>()

        val highWater = maxOf(
            since,
            devices.maxOfOrNull { com.qrscanner.app.cloud.mappers.IsoTime.toEpochMillis(it.updatedAt) } ?: since,
            sessions.maxOfOrNull { com.qrscanner.app.cloud.mappers.IsoTime.toEpochMillis(it.updatedAt) } ?: since,
            lots.maxOfOrNull { com.qrscanner.app.cloud.mappers.IsoTime.toEpochMillis(it.updatedAt) } ?: since,
            rdNumbers.maxOfOrNull { com.qrscanner.app.cloud.mappers.IsoTime.toEpochMillis(it.updatedAt) } ?: since
        )

        CloudDelta(
            devices = devices,
            sessions = sessions,
            lots = lots,
            rdNumbers = rdNumbers,
            highWaterMark = highWater
        )
    }

    override fun observeRealtimeChanges(ownerId: String): Flow<CloudRealtimePayload> = callbackFlow {
        supabase.realtime.connect()
        val channel = supabase.realtime.channel("rt:owner:$ownerId")

        val sessionFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = TABLE_SCAN_SESSIONS
            filter = "owner_id=eq.$ownerId"
        }
        val lotFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = TABLE_SCAN_LOTS
            filter = "owner_id=eq.$ownerId"
        }
        val rdFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = TABLE_RD_NUMBERS
            filter = "owner_id=eq.$ownerId"
        }
        val deviceFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = TABLE_DEVICES
            filter = "owner_id=eq.$ownerId"
        }

        listOf(
            sessionFlow to CloudTable.SCAN_SESSIONS,
            lotFlow to CloudTable.SCAN_LOTS,
            rdFlow to CloudTable.RD_NUMBERS,
            deviceFlow to CloudTable.DEVICES
        ).forEach { (flow, table) ->
            launch {
                flow.collect { action ->
                    val payload = action.toPayload(table) ?: return@collect
                    trySend(payload)
                }
            }
        }

        channel.subscribe()
        awaitClose {
            launch {
                runCatching { channel.unsubscribe() }
                runCatching { supabase.realtime.removeChannel(channel) }
            }
        }
    }

    private suspend inline fun <T> runCloud(block: () -> T): T {
        try {
            return block()
        } catch (e: RestException) {
            throw when (e.statusCode) {
                401, 403 -> CloudException.AuthExpired(e)
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
        return when (this) {
            is PostgresAction.Insert -> CloudRealtimePayload(
                table = table,
                cloudId = record["id"]?.toString()?.trim('"') ?: return null,
                event = CloudRealtimeEvent.INSERT
            )
            is PostgresAction.Update -> CloudRealtimePayload(
                table = table,
                cloudId = record["id"]?.toString()?.trim('"') ?: return null,
                event = CloudRealtimeEvent.UPDATE
            )
            is PostgresAction.Delete -> CloudRealtimePayload(
                table = table,
                cloudId = oldRecord["id"]?.toString()?.trim('"') ?: return null,
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
        private const val PULL_PAGE_SIZE = 500
        private const val REALTIME_RECONNECT_DELAY_MS = 5_000L
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
            Log.w(TAG, "EncryptedSharedPreferences unavailable, falling back to plain prefs", e)
            context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        }
        SharedPreferencesSettings(prefs)
    }

    private val delegate = SettingsSessionManager(settings, key = SESSION_KEY)

    override suspend fun saveSession(session: UserSession) = delegate.saveSession(session)
    override suspend fun loadSessionOrNull(): UserSession? = delegate.loadSessionOrNull()
    override suspend fun deleteSession() = delegate.deleteSession()

    companion object {
        private const val TAG = "EncryptedSessionMgr"
        private const val ENCRYPTED_PREFS_NAME = "supabase_session_encrypted"
        private const val FALLBACK_PREFS_NAME = "supabase_session_fallback"
        private const val SESSION_KEY = "auth_session"
    }
}
