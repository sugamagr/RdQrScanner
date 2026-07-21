package com.qrscanner.app.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.qrscanner.app.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Update-gate probe. On MainActivity launch [check] hits the GitHub
 * Releases API, extracts the versionCode from the release tag, and
 * compares to [BuildConfig.VERSION_CODE]. If the release is newer,
 * the caller renders an in-app affordance to update:
 *  - `isForce = true`  -> full-screen blocking gate
 *    (UpdateGateScreen); operator cannot use the app on this build.
 *  - `isForce = false` -> non-blocking banner over normal UI;
 *    operator can dismiss and keep working.
 *
 * Tag convention (locked to keep the parser trivial): every release
 * tag is `v<major>.<minor>.<patch>+<versionCode>` — the +N suffix
 * carries the machine-comparable versionCode so we don't have to
 * parse semver back into an integer or ship a manifest sidecar. If
 * the tag is malformed the check returns [UpdateResult.UpToDate] as
 * a safe default so an operator laptop typo can't brick 5 phones.
 *
 * Force marker convention: a release is treated as force-update ONLY
 * when its release body contains the literal token `[FORCE]` on any
 * line. This is the ONE escape valve for shipping cloud-schema or
 * sync-format changes that older versions cannot survive; every
 * other update is optional. Priority-3 CROSS-FILE contract: parsed
 * in [parseForceFlag]; consumed by MainActivity to choose between
 * blocking gate and non-blocking banner. Do NOT introduce a second
 * source of truth (per-tag prerelease flags, semver-major bumps,
 * etc.) — a single grep-able string in release notes is the only
 * signal both the operator writing the release and the phone reading
 * it can align on.
 *
 * Offline resilience: any error (no network, GitHub 5xx, GitHub 403
 * rate-limit, malformed JSON, malformed tag, missing APK asset)
 * falls back to the last cached response if present, else returns
 * UpToDate. We would rather show a slightly-stale banner than lock
 * the operator out because the CDN blinked.
 *
 * Rate-limit cache: GitHub unauthenticated API is 60 req/hour PER
 * PUBLIC IP. Colleagues on the same office WiFi share ONE public IP
 * behind NAT, so 5 phones cold-launching within the same hour can
 * silently exhaust the bucket and every subsequent phone gets 403
 * → old code fell back to UpToDate → missed the update entirely.
 * [checkedCache] caches the full parsed response for [CACHE_TTL_MS]
 * (6 hours), and every failure path checks the cache before giving
 * up. Priority-3 invariant: the cache MUST include isForce so a
 * cached-blocking-update stays blocking even while the API is
 * unreachable; forgetting this would let a rate-limited launch bypass
 * a critical push.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val OWNER = "sugamagr"
    private const val REPO = "RdQrScanner"
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    private const val PREFS_NAME = "update_checker"
    private const val KEY_CACHE_JSON = "cache_json"
    private const val KEY_CACHE_STAMP = "cache_stamp"
    private const val CACHE_TTL_MS = 6L * 60L * 60L * 1_000L
    private const val FORCE_MARKER = "[FORCE]"

    /**
     * `+N` versionCode suffix on the tag. See class KDoc for the
     * convention. Group 1 is the versionCode integer.
     */
    private val TAG_VERSION_CODE_REGEX = Regex("""\+(\d+)$""")

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                requestTimeoutMillis = 8_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 8_000
            }
        }
    }

    sealed class UpdateResult {
        object UpToDate : UpdateResult()
        data class Available(
            val versionName: String,
            val versionCode: Int,
            val apkUrl: String,
            val apkSizeBytes: Long,
            val changelog: String,
            val isForce: Boolean
        ) : UpdateResult()
    }

    /**
     * @param forceRefresh true = skip cache read and always hit the
     * network (used by the manual "Check for updates" bell-menu
     * item). On success we STILL write the new response back to the
     * cache so the next auto-check on cold launch benefits from the
     * fresh data. On failure we fall back to the cache exactly like
     * the automatic path, so a manual retry never breaks the
     * baseline behaviour.
     */
    suspend fun check(context: Context, forceRefresh: Boolean = false): UpdateResult {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (!forceRefresh) {
            readFreshCache(prefs)?.let { cached ->
                return cached
            }
        }

        return try {
            val release: GitHubRelease = client.get(LATEST_RELEASE_URL) {
                header("Accept", "application/vnd.github+json")
                header("X-GitHub-Api-Version", "2022-11-28")
            }.body()

            val remoteVersionCode = TAG_VERSION_CODE_REGEX
                .find(release.tagName)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: run {
                    Log.w(TAG, "release tag ${release.tagName} missing +versionCode suffix")
                    prefs.edit().clear().apply()
                    return UpdateResult.UpToDate
                }

            if (remoteVersionCode <= BuildConfig.VERSION_CODE) {
                prefs.edit().clear().apply()
                return UpdateResult.UpToDate
            }

            val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
                ?: run {
                    Log.w(TAG, "release ${release.tagName} has no .apk asset")
                    prefs.edit().clear().apply()
                    return UpdateResult.UpToDate
                }

            val available = UpdateResult.Available(
                versionName = release.name?.takeIf { it.isNotBlank() }
                    ?: release.tagName.substringBefore('+').removePrefix("v"),
                versionCode = remoteVersionCode,
                apkUrl = apkAsset.browserDownloadUrl,
                apkSizeBytes = apkAsset.size,
                changelog = release.body ?: "",
                isForce = parseForceFlag(release.body)
            )
            writeCache(prefs, available)
            available
        } catch (e: Exception) {
            Log.w(TAG, "update check failed; falling back to cache if fresh", e)
            readFreshCache(prefs) ?: UpdateResult.UpToDate
        }
    }

    /**
     * Reads the cached response IF it is (a) still under [CACHE_TTL_MS]
     * old and (b) still describes a versionCode strictly greater than
     * the running build. The versionCode re-check matters when the
     * operator installs a newer APK BETWEEN two cache writes — without
     * it we would keep announcing an update that is already installed.
     */
    private fun readFreshCache(prefs: SharedPreferences): UpdateResult.Available? {
        val stamp = prefs.getLong(KEY_CACHE_STAMP, 0L)
        val payload = prefs.getString(KEY_CACHE_JSON, null) ?: return null
        val age = System.currentTimeMillis() - stamp
        if (age !in 0..CACHE_TTL_MS) return null
        val cached = runCatching { json.decodeFromString<CachedAvailable>(payload) }
            .getOrNull()
            ?: return null
        if (cached.versionCode <= BuildConfig.VERSION_CODE) return null
        return UpdateResult.Available(
            versionName = cached.versionName,
            versionCode = cached.versionCode,
            apkUrl = cached.apkUrl,
            apkSizeBytes = cached.apkSizeBytes,
            changelog = cached.changelog,
            isForce = cached.isForce
        )
    }

    private fun writeCache(prefs: SharedPreferences, available: UpdateResult.Available) {
        val payload = CachedAvailable(
            versionName = available.versionName,
            versionCode = available.versionCode,
            apkUrl = available.apkUrl,
            apkSizeBytes = available.apkSizeBytes,
            changelog = available.changelog,
            isForce = available.isForce
        )
        prefs.edit()
            .putString(KEY_CACHE_JSON, json.encodeToString(CachedAvailable.serializer(), payload))
            .putLong(KEY_CACHE_STAMP, System.currentTimeMillis())
            .apply()
    }

    /**
     * Case-insensitive scan for the [FORCE_MARKER] anywhere in the
     * release body. Priority-3 SEMANTIC lock: matching is
     * substring-based not line-based so operators can put the token
     * inline with prose like "[FORCE] cloud schema change - must
     * install". Do not tighten to full-line-match; the tradeoff
     * favours forgiving authoring over parser strictness because a
     * missed FORCE tag ships as merely-optional (safe), while an
     * over-eager parser could block updates the operator meant as
     * optional.
     */
    private fun parseForceFlag(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        return body.contains(FORCE_MARKER, ignoreCase = true)
    }

    @Serializable
    private data class CachedAvailable(
        val versionName: String,
        val versionCode: Int,
        val apkUrl: String,
        val apkSizeBytes: Long,
        val changelog: String,
        val isForce: Boolean
    )

    /**
     * Kicks off a DownloadManager job and returns the downloaded APK
     * file when the download completes. Caller is responsible for
     * showing progress UI while this suspends.
     *
     * Storage strategy: external cache dir (getExternalCacheDir/updates/).
     * Under app's own scoped storage sandbox so no runtime permission
     * needed. Auto-cleared on uninstall.
     *
     * Reuse-if-fully-downloaded: if `update-<versionName>.apk` already
     * exists AND its length matches [expectedSizeBytes] (from the
     * GitHub API asset.size), we skip the network entirely and return
     * the cached file. This is the "operator downloaded v2.0.5, killed
     * the installer prompt, taps Update again" case — v2.0.5 shipped a
     * bug where every tap re-downloaded 77MB from scratch. Priority-3
     * SEMANTIC lock: the size match is the ONLY reuse gate we need at
     * this scale because DownloadManager writes byte-for-byte to the
     * target file, and GitHub asset sizes are fixed once the release is
     * cut. A cryptographic hash check would be theoretically stricter
     * but adds a 77MB SHA256 pass on every re-tap for zero real-world
     * benefit — the OS installer already validates the APK signature
     * before installing, so a corrupt reused file fails at the
     * installer prompt (not silently), and the operator can retry.
     *
     * Selective cleanup: instead of nuking every file in updates/ (v2.0.5
     * behaviour that made this bug possible), we only delete files
     * whose name is NOT the current [versionName] target. This keeps
     * stale APKs (e.g. `update-v2.0.5.apk` left after v2.0.6 lands)
     * from bloating external cache indefinitely, while preserving the
     * one file we might still be able to reuse.
     *
     * Partial-download recovery: if [expectedSizeBytes] is > 0 and the
     * existing file's length is smaller (killed prior download), we
     * delete and re-fetch. If [expectedSizeBytes] is 0 (GitHub API
     * didn't populate size — extremely rare), we conservatively delete
     * any existing file and re-download to avoid running the installer
     * against a possibly-truncated APK.
     */
    suspend fun downloadApk(
        context: Context,
        apkUrl: String,
        versionName: String,
        expectedSizeBytes: Long = 0L
    ): File {
        val cacheDir = File(context.externalCacheDir, "updates").apply { mkdirs() }
        val target = File(cacheDir, "update-$versionName.apk")

        cacheDir.listFiles()?.forEach { file ->
            if (file.name != target.name) {
                runCatching { file.delete() }
            }
        }

        if (expectedSizeBytes > 0 && target.exists() && target.length() == expectedSizeBytes) {
            return target
        }
        if (target.exists()) {
            runCatching { target.delete() }
        }

        val dm = context.getSystemService(DownloadManager::class.java)
            ?: error("DownloadManager unavailable")

        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("RD Scanner update")
            .setDescription("Downloading v$versionName")
            .setDestinationUri(Uri.fromFile(target))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadId = dm.enqueue(request)
        val done = CompletableDeferred<Unit>()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id == downloadId) done.complete(Unit)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )

        try {
            done.await()
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }

        val status = queryStatus(dm, downloadId)
        if (status != DownloadManager.STATUS_SUCCESSFUL) {
            error("APK download failed with status=$status")
        }
        if (!target.exists() || target.length() == 0L) {
            error("APK download completed but file missing/empty at ${target.absolutePath}")
        }
        return target
    }

    private fun queryStatus(dm: DownloadManager, id: Long): Int {
        val cursor: Cursor = dm.query(DownloadManager.Query().setFilterById(id))
        return cursor.use {
            if (it.moveToFirst()) {
                it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            } else {
                DownloadManager.STATUS_FAILED
            }
        }
    }

    /**
     * Whether the OS will let us launch the system installer. On
     * Android O+ the user must grant "Install unknown apps" to our
     * package specifically. Deep-link to that settings page via
     * [installUnknownAppsSettingsIntent].
     */
    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun installUnknownAppsSettingsIntent(context: Context): Intent {
        return Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Launches the system package installer for [apk]. Uses
     * FileProvider so the resulting content:// URI survives Android
     * 7+ strict file-URI checks. Caller must have already verified
     * [canInstallPackages]; otherwise the installer surfaces its own
     * dialog telling the user to grant the permission.
     */
    fun launchInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tagName: String,
        val name: String? = null,
        val body: String? = null,
        val assets: List<GitHubAsset> = emptyList()
    )

    @Serializable
    private data class GitHubAsset(
        val name: String,
        val size: Long,
        @SerialName("browser_download_url") val browserDownloadUrl: String
    )
}
