package org.schabi.newpipe.mediasync

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.room.InvalidationTracker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.schabi.newpipe.NewPipeDatabase
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Pure decisions for automatic sync, kept free of Android classes so they are unit tested. */
object MediaSyncAutoPolicy {
    /** Run only when automatic sync is on and nothing needs the user (conflicts or a restored service). */
    fun shouldRun(autoSync: Boolean, blocked: Boolean, conflicts: Int) = autoSync && !blocked && conflicts == 0

    /** WorkManager retries with back-off while the service is unreachable; other results are final for this run. */
    fun retry(code: String) = code == "offline"

    /** Seconds after the app comes to the front before it pulls, and how recent a sync makes that unnecessary. */
    const val FOREGROUND_DELAY_SECONDS = 2L
    const val FOREGROUND_MIN_GAP_MS = 30_000L

    /** Pull when the app comes to the front, unless automatic sync is paused or a sync just finished. */
    fun syncOnForeground(autoSync: Boolean, lastSyncAt: Long, now: Long) = autoSync && (lastSyncAt <= 0 || now - lastSyncAt >= FOREGROUND_MIN_GAP_MS || now < lastSyncAt)
}

/**
 * Automatic two-way sync. A periodic job runs about every 15 minutes with network, one job runs
 * shortly after the app starts or comes to the front, and one runs 30 seconds after the last local library change
 * (the delay restarts on each change, so playback progress writes do not cause a sync mid-song).
 * The work uses the same MediaSyncClient as Sync now and never starts playback.
 */
object MediaSyncAuto {
    private const val PERIODIC = "dd-media-sync-periodic"
    private const val SOON = "dd-media-sync-soon"
    private const val DEBOUNCE_SECONDS = 30L
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var observer: InvalidationTracker.Observer? = null

    /** Called from App.onCreate and after sign-in or resuming. Does nothing without an enrollment. */
    @JvmStatic fun install(context: Context) {
        val app = context.applicationContext
        if (!MediaSyncSecrets(app).exists()) { cancel(app); return }
        io.execute {
            try {
                // A paused enrollment keeps nothing scheduled and no database observer.
                if (!MediaSyncClient.savedAuto(NewPipeDatabase.getInstance(app)).first) { cancel(app); return@execute }
                val work = WorkManager.getInstance(app)
                work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequestBuilder<MediaSyncWorker>(15, TimeUnit.MINUTES).setConstraints(network)
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build())
                syncSoon(app, 10)
                watchLocalChanges(app)
            } catch (_: Exception) { /* Sync is optional; the app keeps working without it. */ }
        }
    }

    @JvmStatic fun syncSoon(context: Context, delaySeconds: Long = DEBOUNCE_SECONDS) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(SOON, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<MediaSyncWorker>().setInitialDelay(delaySeconds, TimeUnit.SECONDS).setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build())
    }

    private var started = 0
    @Volatile private var foreground = false

    /**
     * Called once from App.onCreate. A process on a TV or phone can live for days, so app start alone is not
     * enough: when the first activity starts, sync in a couple of seconds (unless paused or just synced), so the
     * history and resume positions from other devices are here before a video is opened.
     */
    @JvmStatic fun watchForeground(app: Application) {
        if (foreground) return
        foreground = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { if (started++ == 0) onForeground(app) }
            override fun onActivityStopped(activity: Activity) { if (started > 0) started-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun onForeground(app: Context) {
        if (!MediaSyncSecrets(app).exists()) return
        io.execute {
            try {
                val (auto, last) = MediaSyncClient.savedAuto(NewPipeDatabase.getInstance(app))
                if (MediaSyncAutoPolicy.syncOnForeground(auto, last, System.currentTimeMillis())) syncSoon(app, MediaSyncAutoPolicy.FOREGROUND_DELAY_SECONDS)
            } catch (_: Exception) { /* Sync is optional. */ }
        }
    }

    /** Sign-out, disconnect or pause: stop all scheduled work and stop listening to the database. */
    @JvmStatic fun cancel(context: Context) {
        val app = context.applicationContext
        io.execute {
            try {
                val work = WorkManager.getInstance(app); work.cancelUniqueWork(PERIODIC); work.cancelUniqueWork(SOON)
                observer?.let { NewPipeDatabase.getInstance(app).invalidationTracker.removeObserver(it) }; observer = null
            } catch (_: Exception) { /* nothing scheduled */ }
        }
    }

    private fun watchLocalChanges(app: Context) {
        if (observer != null) return
        // Tables the sync adapters read. dd_media_sync itself is excluded so a sync does not trigger another.
        val tables = arrayOf("subscriptions", "streams", "stream_state", "stream_history", "playlists", "playlist_stream_join", "feed_group", "feed_group_subscription_join")
        val next = object : InvalidationTracker.Observer(tables) {
            override fun onInvalidated(changed: Set<String>) { syncSoon(app) }
        }
        NewPipeDatabase.getInstance(app).invalidationTracker.addObserver(next); observer = next
    }
}

class MediaSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val secrets = MediaSyncSecrets(applicationContext)
        return try {
            // Enrollment is checked and used under the same lock that disconnect takes, so a worker waiting
            // behind a disconnect finds no enrollment and stops instead of recreating the cleared state.
            val result = MediaSyncClient.exclusive {
                if (!secrets.exists()) null
                else MediaSyncClient(NewPipeDatabase.getInstance(applicationContext), secrets.load()).syncIfAllowed()
            }
            if (result != null && !MediaSyncAutoPolicy.retry(result.optString("code"))) {
                // Cosmetic and outside the sync lock: titles for videos that arrived as bare IDs.
                try { MediaSyncHydrator.run(NewPipeDatabase.getInstance(applicationContext)) } catch (_: Exception) { }
            }
            if (result != null && MediaSyncAutoPolicy.retry(result.optString("code"))) Result.retry() else Result.success()
        } catch (_: SyncFailure) {
            // Needs the user (upgrade, restore, rejected credentials). It is recorded in the sync state, so
            // no further automatic run contacts the service until the user acts in settings.
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
