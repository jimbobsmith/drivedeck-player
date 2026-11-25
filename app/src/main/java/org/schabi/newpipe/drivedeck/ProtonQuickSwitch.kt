package org.schabi.newpipe.drivedeck

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import org.schabi.newpipe.BuildConfig

/**
 * Asks the Proton API client for Fire TV (jimbobsmith/protonvpn-linux-ui, `firetv/`) on this
 * device for a Quick switch, and waits for its reported result. The Proton app chooses the
 * server: its oldest last-connected favorite that is not the current one.
 *
 * The Proton app's local request: an explicit broadcast of [ACTION_SWITCH] to its
 * `QuickSwitchReceiver`, which needs [PERMISSION]. It answers with [ACTION_RESULT] (extras
 * `ok` and `message`), sent only to apps holding [PERMISSION]. It is not a network service.
 *
 * Test builds also look for the stand-in at [MOCK_PACKAGE] (the `proton-mock` module), which
 * answers the same way, so this can be tested on an emulator without a VPN.
 */
object ProtonQuickSwitch {
    private const val TAG = "ProtonQuickSwitch"

    const val PROTON_PACKAGE = "com.jimbobsmith.protonapi.firetv"
    const val MOCK_PACKAGE = "com.drivedeck.protonmock"
    const val ACTION_SWITCH = "com.jimbobsmith.protonapi.firetv.QUICK_SWITCH"
    const val ACTION_RESULT = "com.jimbobsmith.protonapi.firetv.QUICK_SWITCH_RESULT"
    const val PERMISSION = "com.jimbobsmith.protonapi.firetv.permission.QUICK_SWITCH"

    /** The Proton app says a switch can take minutes; a timeout is "no answer", not a failure. */
    const val SWITCH_TIMEOUT_MS = 10 * 60_000L

    sealed interface Status {
        /** Neither the Proton app nor (in Test builds) the stand-in is installed. */
        data object NotInstalled : Status

        /**
         * Installed, but this app does not hold [PERMISSION]. Android grants it only to apps
         * installed or updated after the Proton app, so DD PipePipe needs a reinstall or update.
         */
        data object NoPermission : Status

        data class Ready(val packageName: String) : Status
    }

    sealed interface Result {
        data class Switched(val message: String) : Result
        data class Failed(val message: String) : Result
        data object NoAnswer : Result
        data object NotInstalled : Result
        data object NoPermission : Result
    }

    /** Automatic switches in this process, shared by the player and the video page. */
    val automaticBudget = ProtonBlockPolicy.Budget()

    private val main = Handler(Looper.getMainLooper())
    private val waiting = mutableListOf<(Result) -> Unit>()
    private var receiver: BroadcastReceiver? = null
    private var timeout: Runnable? = null

    fun candidates(): List<String> =
        if (BuildConfig.DEBUG) listOf(PROTON_PACKAGE, MOCK_PACKAGE) else listOf(PROTON_PACKAGE)

    fun status(context: Context): Status {
        val installed = candidates().firstOrNull { isInstalled(context, it) } ?: return Status.NotInstalled
        val granted = ContextCompat.checkSelfPermission(context, PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
        return if (granted) Status.Ready(installed) else Status.NoPermission
    }

    /** True while a switch this app asked for has not answered yet. */
    @Synchronized
    fun isSwitching(): Boolean = receiver != null

    /**
     * Ask for a Quick switch. A second request while one runs waits for the same answer, so two
     * players never stack switches. [done] runs on the main thread.
     */
    @Synchronized
    fun requestSwitch(context: Context, done: (Result) -> Unit) {
        val app = context.applicationContext
        val target = when (val status = status(app)) {
            Status.NotInstalled -> return post(done, Result.NotInstalled)
            Status.NoPermission -> return post(done, Result.NoPermission)
            is Status.Ready -> status.packageName
        }
        waiting.add(done)
        if (receiver != null) return

        val listener = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val ok = intent.getBooleanExtra("ok", false)
                val message = intent.getStringExtra("message").orEmpty()
                finish(app, if (ok) Result.Switched(message) else Result.Failed(message))
            }
        }
        receiver = listener
        // Only an app holding the permission (the Proton app) can send the result.
        ContextCompat.registerReceiver(
            app, listener, IntentFilter(ACTION_RESULT), PERMISSION, main,
            ContextCompat.RECEIVER_EXPORTED,
        )
        val giveUp = Runnable { finish(app, Result.NoAnswer) }
        timeout = giveUp
        main.postDelayed(giveUp, SWITCH_TIMEOUT_MS)

        Log.i(TAG, "asking $target for a quick switch")
        app.sendBroadcast(Intent(ACTION_SWITCH).setPackage(target), PERMISSION)
    }

    @Synchronized
    private fun finish(context: Context, result: Result) {
        val listener = receiver ?: return
        receiver = null
        timeout?.let(main::removeCallbacks)
        timeout = null
        try {
            context.unregisterReceiver(listener)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "result receiver was already gone", e)
        }
        val callbacks = waiting.toList()
        waiting.clear()
        Log.i(TAG, "quick switch finished: $result")
        callbacks.forEach { post(it, result) }
    }

    private fun post(done: (Result) -> Unit, result: Result) {
        main.post { done(result) }
    }

    private fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
