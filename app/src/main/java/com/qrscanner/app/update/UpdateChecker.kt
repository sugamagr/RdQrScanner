package com.qrscanner.app.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
 * Force-update gate. On MainActivity launch [check] hits the GitHub
 * Releases API, extracts the versionCode from the release tag, and
 * compares to [BuildConfig.VERSION_CODE]. If the release is newer,
 * the caller shows [com.qrscanner.app.ui.update.UpdateGateScreen] and
 * blocks all further app UI until the user taps Download & install.
 *
 * Tag convention (locked to keep the parser trivial): every release
 * tag is `v<major>.<minor>.<patch>+<versionCode>` — the +N suffix
 * carries the machine-comparable versionCode so we don't have to
 * parse semver back into an integer or ship a manifest sidecar. If
 * the tag is malformed the check returns [UpdateResult.UpToDate] as
 * a safe default so an operator laptop typo can't brick 5 phones.
 *
 * Offline resilience: any error (no network, GitHub 5xx, malformed
 * JSON, malformed tag, missing APK asset) returns UpToDate. We would
 * rather let an operator work on a stale build than lock them out of
 * the app because the CDN blinked. The next launch retries.
 *
 * Rate limit: GitHub unauthenticated API is 60 req/hour per IP. At
 * 5 phones checking once per launch that's absurdly under the cap
 * even if operators relaunch every minute.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val OWNER = "sugamagr"
    private const val REPO = "RdQrScanner"
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

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
            val changelog: String
        ) : UpdateResult()
    }

    suspend fun check(): UpdateResult {
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
                    return UpdateResult.UpToDate
                }

            if (remoteVersionCode <= BuildConfig.VERSION_CODE) {
                return UpdateResult.UpToDate
            }

            val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
                ?: run {
                    Log.w(TAG, "release ${release.tagName} has no .apk asset")
                    return UpdateResult.UpToDate
                }

            UpdateResult.Available(
                versionName = release.name?.takeIf { it.isNotBlank() }
                    ?: release.tagName.substringBefore('+').removePrefix("v"),
                versionCode = remoteVersionCode,
                apkUrl = apkAsset.browserDownloadUrl,
                apkSizeBytes = apkAsset.size,
                changelog = release.body ?: ""
            )
        } catch (e: Exception) {
            Log.w(TAG, "update check failed; assuming up-to-date", e)
            UpdateResult.UpToDate
        }
    }

    /**
     * Kicks off a DownloadManager job and returns the downloaded APK
     * file when the download completes. Caller is responsible for
     * showing progress UI while this suspends.
     *
     * Storage strategy: external cache dir (getExternalCacheDir/updates/).
     * Under app's own scoped storage sandbox so no runtime permission
     * needed. Auto-cleared on uninstall. Any prior half-downloaded
     * copy is deleted before the new job starts so a killed download
     * can't corrupt the next attempt.
     */
    suspend fun downloadApk(
        context: Context,
        apkUrl: String,
        versionName: String
    ): File {
        val dm = context.getSystemService(DownloadManager::class.java)
            ?: error("DownloadManager unavailable")

        val cacheDir = File(context.externalCacheDir, "updates").apply { mkdirs() }
        cacheDir.listFiles()?.forEach { runCatching { it.delete() } }
        val target = File(cacheDir, "update-$versionName.apk")

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
