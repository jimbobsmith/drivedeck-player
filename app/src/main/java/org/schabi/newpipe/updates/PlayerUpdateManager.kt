package org.schabi.newpipe.updates

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.AtomicFile
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.R
import org.schabi.newpipe.settings.SettingsActivity
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Standalone public updates; no DD process, credentials, media control or silent installer. */
class PlayerUpdateManager internal constructor(
    context: Context,
    private val clientFactory: () -> GitHubReleaseClient = { GitHubReleaseClient() },
    preferenceName: String = "player_updates"
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val directory = File(app.filesDir, "updates/$preferenceName").apply { mkdirs() }
    private val prepared = AtomicFile(File(directory, "prepared.json"))
    private val lock = Any()
    private val generation = AtomicLong()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var active: GitHubReleaseClient? = null
    private var background = false
    private val mutable = MutableStateFlow(AppUpdateState(phase = UpdatePhase.IDLE,
        repositoryUrl = REPOSITORY.releasesUrl, message = "Check for verified DD Player updates."))
    val state = mutable.asStateFlow()
    val automatic: Boolean get() = prefs.getBoolean("automatic", false)
    val autoDownload: Boolean get() = prefs.getBoolean("auto_download", false)

    init {
        directory.listFiles()?.filter { it.name.startsWith("pending-") }?.forEach { it.delete() }
        val ticket = generation.get()
        scope.launch { restore(ticket) }
    }

    fun setAutomatic(enabled: Boolean) {
        prefs.edit().putBoolean("automatic", enabled).apply()
        reconcileSchedule()
        if (!enabled) cancelBackground() else checkNow()
    }
    fun setAutoDownload(enabled: Boolean) {
        prefs.edit().putBoolean("auto_download", enabled).apply()
        if (!enabled) {
            WorkManager.getInstance(app).cancelUniqueWork(DOWNLOAD_WORK)
            cancelBackground()
        } else if (automatic && state.value.phase == UpdatePhase.AVAILABLE) queueDownload()
    }
    fun reconcileSchedule() {
        val work = WorkManager.getInstance(app)
        if (automatic) {
            val request = PeriodicWorkRequestBuilder<PlayerUpdateCheckWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true).build()).build()
            work.enqueueUniquePeriodicWork(CHECK_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        } else {
            work.cancelUniqueWork(CHECK_WORK)
            work.cancelUniqueWork(DOWNLOAD_WORK)
        }
    }
    private fun queueDownload() {
        if (!automatic || !autoDownload) return
        val request = OneTimeWorkRequestBuilder<PlayerUpdateDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()).build()
        WorkManager.getInstance(app).enqueueUniqueWork(DOWNLOAD_WORK, ExistingWorkPolicy.KEEP, request)
    }
    fun checkNow() {
        if (state.value.phase == UpdatePhase.READY_TO_INSTALL) return
        start(UpdatePhase.CHECKING, "Checking for updates…") { ticket ->
            val result = withClient(ticket) { lookup(it) }
            publish(ticket) {
                applyLookup(result)
                if (state.value.phase == UpdatePhase.AVAILABLE) queueDownload()
            }
        }
    }
    fun downloadAvailable() {
        val release = state.value.release ?: return
        if (state.value.phase != UpdatePhase.AVAILABLE) return
        start(UpdatePhase.DOWNLOADING, "Downloading update…") { download(it, release) }
    }
    private fun start(phase: UpdatePhase, message: String, operation: suspend (Long) -> Unit) = synchronized(lock) {
        val ticket = generation.incrementAndGet()
        active?.let { client -> scope.launch { client.cancel() } }; job?.cancel(); background = false
        mutable.value = state.value.copy(phase = phase, message = message, downloadedBytes = 0, totalBytes = 0)
        job = scope.launch {
            try { operation(ticket) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { publish(ticket) { failure() } }
        }
    }
    fun cancel() = synchronized(lock) {
        generation.incrementAndGet()
        active?.let { client -> scope.launch { client.cancel() } }
        job?.cancel(); background = false
        mutable.value = state.value.copy(phase = if (state.value.release == null) UpdatePhase.IDLE else UpdatePhase.AVAILABLE,
            message = "Update operation cancelled.", downloadedBytes = 0, totalBytes = 0)
    }
    private fun cancelBackground() = synchronized(lock) { if (background) cancel() }
    private fun failure() {
        mutable.value = state.value.copy(phase = UpdatePhase.FAILED,
            message = "Update check or download failed. Check your connection and storage, then retry.")
    }
    private suspend fun <T> withClient(ticket: Long, operation: (GitHubReleaseClient) -> T): T {
        currentCoroutineContext().ensureActive()
        val client = clientFactory()
        synchronized(lock) { ensureCurrent(ticket); active = client }
        try {
            val result = operation(client)
            currentCoroutineContext().ensureActive(); ensureCurrent(ticket)
            return result
        } finally { synchronized(lock) { if (active === client) active = null } }
    }
    private fun lookup(client: GitHubReleaseClient) = client.findNewestRelease(REPOSITORY, "", app.packageName,
        installedVersion(), Build.SUPPORTED_ABIS.toList(), PlayerUpdatePolicy.tagPrefix(app.packageName))
    private fun installedVersion() = AppUpdateVerifier.versionCode(app.packageManager.getPackageInfo(app.packageName, 0))
    private fun applyLookup(result: ReleaseLookupResult) {
        val release = result.newestRelease
        mutable.value = AppUpdateState(phase = if (release == null) UpdatePhase.UP_TO_DATE else UpdatePhase.AVAILABLE,
            release = release, repositoryUrl = REPOSITORY.releasesUrl, lastCheckedAtEpochMillis = System.currentTimeMillis(),
            message = if (release == null) "No newer verified update is available." else "${release.manifest.versionName} is available.")
    }
    private fun apkFile(release: DriveDeckRelease) = File(directory, "${release.manifest.versionCode}-${release.asset.name}")
    private suspend fun download(ticket: Long, release: DriveDeckRelease) {
        check(directory.usableSpace > release.asset.sizeBytes + 64L * 1024 * 1024)
        val staged = File(directory, "pending-$ticket-${release.asset.name}")
        try {
            withClient(ticket) { client -> client.download(release, "", staged) { count, total ->
                ensureCurrent(ticket)
                publish(ticket) { mutable.value = state.value.copy(downloadedBytes = count, totalBytes = total) }
            } }
            publish(ticket) { mutable.value = state.value.copy(phase = UpdatePhase.VERIFYING, message = "Verifying the APK…") }
            check(AppUpdateVerifier.verify(app, staged, release.manifest, release.asset) is ApkVerificationResult.Valid)
            currentCoroutineContext().ensureActive()
            publish(ticket) {
                val apk = apkFile(release)
                if (!staged.renameTo(apk)) staged.copyTo(apk, overwrite = true)
                val output = prepared.startWrite()
                try { output.write(encodeManifest(release.manifest).toByteArray(Charsets.UTF_8)); prepared.finishWrite(output) }
                catch (error: Exception) { prepared.failWrite(output); throw error }
                directory.listFiles()?.filter { it.name.endsWith(".apk") && it != apk && !it.name.startsWith("pending-") }?.forEach { it.delete() }
                mutable.value = state.value.copy(phase = UpdatePhase.READY_TO_INSTALL,
                    downloadedBytes = release.asset.sizeBytes, totalBytes = release.asset.sizeBytes,
                    message = "Verified update ready. Android will ask you to confirm installation.")
            }
        } finally { staged.delete(); File(directory, "${staged.name}.part").delete() }
    }
    private fun restore(ticket: Long) {
        val manifest = runCatching {
            prepared.openRead().use { input ->
                val bytes = MediaUpdateInput.read(input)
                GitHubReleaseParser.parseManifest(String(bytes, Charsets.UTF_8))
            }
        }.getOrNull() ?: return
        if (manifest.packageName != app.packageName || !manifest.tag.startsWith(PlayerUpdatePolicy.tagPrefix(app.packageName))) return
        val asset = manifest.preferredAsset(Build.SUPPORTED_ABIS.toList()) ?: return
        val release = DriveDeckRelease(1, manifest.versionName, "", "", true, manifest, asset, "")
        val apk = apkFile(release)
        if (runCatching { AppUpdateVerifier.verify(app, apk, manifest, asset) }.getOrNull() is ApkVerificationResult.Valid) publish(ticket) {
            mutable.value = state.value.copy(phase = UpdatePhase.READY_TO_INSTALL, release = release,
                downloadedBytes = asset.sizeBytes, totalBytes = asset.sizeBytes, message = "Verified update ready to install.")
        } else publish(ticket) { apk.delete(); prepared.delete() }
    }
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || app.packageManager.canRequestPackageInstalls()
    fun installPermissionIntent() = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
    suspend fun installIntent(): Intent? = withContext(Dispatchers.IO) {
        val ticket = generation.get()
        val release = state.value.release ?: return@withContext null
        if (state.value.phase != UpdatePhase.READY_TO_INSTALL) return@withContext null
        val apk = apkFile(release)
        if (AppUpdateVerifier.verify(app, apk, release.manifest, release.asset) !is ApkVerificationResult.Valid) {
            publish(ticket) { apk.delete(); prepared.delete(); failure() }; return@withContext null
        }
        synchronized(lock) {
            if (ticket != generation.get() || state.value.phase != UpdatePhase.READY_TO_INSTALL) return@synchronized null
            Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = FileProvider.getUriForFile(app, "${app.packageName}.updates", apk)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
    suspend fun backgroundCheck(downloadAutomatically: Boolean): Boolean {
        val ticket: Long
        synchronized(lock) {
            if (!automatic || downloadAutomatically && !autoDownload) return true
            if (state.value.phase in BUSY || state.value.phase == UpdatePhase.READY_TO_INSTALL) return true
            ticket = generation.incrementAndGet(); background = true
            mutable.value = state.value.copy(phase = UpdatePhase.CHECKING, message = "Checking for updates…")
        }
        return try {
            val result = withClient(ticket) { lookup(it) }
            publish(ticket) { applyLookup(result) }
            val release = result.newestRelease
            if (release != null) {
                if (downloadAutomatically) {
                    publish(ticket) { mutable.value = state.value.copy(phase = UpdatePhase.DOWNLOADING, message = "Downloading update…") }
                    download(ticket, release)
                    publish(ticket) { notifyUser("DD Player update ready", "Tap to review and install the verified update.") }
                } else publish(ticket) {
                    notifyUser("DD Player update available", "Open Updates to download and install."); queueDownload()
                }
            }
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { publish(ticket) { failure() }; false }
        finally {
            publish(ticket) {
                background = false
                // WorkManager can cancel a running job when network/battery constraints change.
                // Never leave the next worker permanently blocked by an abandoned busy phase.
                if (state.value.phase in BUSY) mutable.value = state.value.copy(
                    phase = if (state.value.release == null) UpdatePhase.IDLE else UpdatePhase.AVAILABLE,
                    message = "Update operation paused. It can be retried.",
                    downloadedBytes = 0, totalBytes = 0)
            }
        }
    }
    fun onPackageReplaced() {
        reconcileSchedule()
        notifyUser("DD Player updated", "Tap to open Updates.")
    }
    private fun notifyUser(title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "DD Player updates", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(app, SettingsActivity::class.java).putExtra(OPEN_UPDATES, true)
        val pending = PendingIntent.getActivity(app, 2401, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching { manager.notify(2401, NotificationCompat.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_newpipe_update)
            .setContentTitle(title).setContentText(text).setContentIntent(pending).setAutoCancel(true).build()) }
    }
    private fun ensureCurrent(ticket: Long) { if (ticket != generation.get()) throw CancellationException("Update superseded") }
    private fun publish(ticket: Long, operation: () -> Unit) = synchronized(lock) { if (ticket == generation.get()) operation() }
    companion object {
        const val OPEN_UPDATES = "org.schabi.newpipe.updates.OPEN_SETTINGS"
        const val CHECK_WORK = "dd-player-update-check"
        const val DOWNLOAD_WORK = "dd-player-update-download"
        private const val CHANNEL = "dd_player_updates"
        val REPOSITORY = GitHubReleaseRepository("jimbobsmith", "drivedeck-player")
        private val BUSY = setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)
        @Volatile private var instance: PlayerUpdateManager? = null
        @JvmStatic fun get(context: Context): PlayerUpdateManager = instance ?: synchronized(this) {
            instance ?: PlayerUpdateManager(context).also { instance = it }
        }
        internal fun encodeManifest(manifest: DriveDeckReleaseManifest): String = JSONObject()
            .put("schemaVersion", manifest.schemaVersion).put("packageName", manifest.packageName).put("versionCode", manifest.versionCode)
            .put("versionName", manifest.versionName).put("tag", manifest.tag).put("commitSha", manifest.commitSha)
            .put("assets", JSONArray().apply { manifest.assets.forEach { put(JSONObject().put("name", it.name).put("abi", it.abi).put("sha256", it.sha256).put("sizeBytes", it.sizeBytes)) } }).toString()
    }
}

object PlayerUpdatePolicy {
    fun tagPrefix(packageName: String) = when (packageName) {
        "com.drivedeck.player.test" -> "player-test-"
        "com.drivedeck.player" -> "player-release-"
        else -> throw IllegalArgumentException("Unsupported Player update package")
    }
}
internal object MediaUpdateInput {
    fun read(input: java.io.InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) { val one = input.read(); if (one < 0) break; output.write(one) }
            else output.write(buffer, 0, count)
            check(output.size() <= 32_768)
        }
        return output.toByteArray()
    }
}
class PlayerUpdateCheckWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = if (runAttemptCount >= 3 || PlayerUpdateManager.get(applicationContext).backgroundCheck(false)) Result.success() else Result.retry()
}
class PlayerUpdateDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = if (runAttemptCount >= 3 || PlayerUpdateManager.get(applicationContext).backgroundCheck(true)) Result.success() else Result.retry()
}
class PlayerUpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) PlayerUpdateManager.get(context).onPackageReplaced()
    }
}
