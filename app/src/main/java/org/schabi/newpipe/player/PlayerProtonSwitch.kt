package org.schabi.newpipe.player

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import com.google.android.exoplayer2.PlaybackException
import org.schabi.newpipe.R
import org.schabi.newpipe.drivedeck.ProtonBlockPolicy
import org.schabi.newpipe.drivedeck.ProtonBlockPolicy.Decision
import org.schabi.newpipe.drivedeck.ProtonQuickSwitch
import org.schabi.newpipe.util.InfoCache

/**
 * Owns the "YouTube blocked this address" recovery of a [Player]: ask the Proton app on this
 * device for a Quick switch, show what is happening on the player, and continue the video at
 * the same position once the new tunnel is up.
 *
 * [PlayerErrorHandler] asks [handle] first. When it returns false, or the switch does not
 * happen, the player's own error handling runs as before.
 */
class PlayerProtonSwitch(private val player: Player) {

    private val main = Handler(Looper.getMainLooper())

    private val hideWhenPlaying = object : Runnable {
        override fun run() {
            if (player.isPlaying || hideChecks++ > MAX_HIDE_CHECKS) {
                showStatus(null)
            } else {
                main.postDelayed(this, 500L)
            }
        }
    }
    private var hideChecks = 0

    /**
     * @param fallback the player's own handling of [error], run when no switch happens
     * @return true when this took the error over
     */
    fun handle(error: PlaybackException, fallback: () -> Unit): Boolean {
        ProtonBlockPolicy.classify(error) ?: return false
        val context = player.context
        val mode = ProtonBlockPolicy.Mode.of(
            player.prefs.getString(context.getString(R.string.drivedeck_proton_switch_key), null)
        )
        if (mode == ProtonBlockPolicy.Mode.OFF) return false

        val status = ProtonQuickSwitch.status(context)
        if (status !is ProtonQuickSwitch.Status.Ready) {
            if (status == ProtonQuickSwitch.Status.NoPermission) {
                toast(R.string.drivedeck_proton_switch_no_permission)
            }
            return false
        }

        val item = player.currentMetadata?.url ?: ""
        val canAsk = player.parentActivity != null
        val decision = if (ProtonQuickSwitch.isSwitching()) {
            // Another player already asked; wait for the same answer.
            Decision.SWITCH
        } else {
            ProtonBlockPolicy.decide(mode, protonReady = true, budgetAllows = budget.allows(item), canAsk = canAsk)
        }

        // Keep the position so the video continues where it stopped.
        player.setRecovery()
        when (decision) {
            Decision.NONE -> return false
            Decision.PAUSED -> {
                toast(R.string.drivedeck_proton_switch_paused, budget.count())
                return false
            }
            Decision.SWITCH -> {
                if (!ProtonQuickSwitch.isSwitching()) budget.record(item)
                switchNow(fallback)
            }
            Decision.ASK -> ask(fallback)
        }
        return true
    }

    private fun ask(fallback: () -> Unit) {
        val activity = player.parentActivity ?: return fallback()
        player.onBuffering()
        showStatus(activity.getString(R.string.drivedeck_proton_switch_blocked))
        try {
            AlertDialog.Builder(activity)
                .setTitle(R.string.drivedeck_proton_switch_ask_title)
                .setMessage(R.string.drivedeck_proton_switch_ask_message)
                .setPositiveButton(R.string.drivedeck_proton_switch_ask_yes) { _, _ -> switchNow(fallback) }
                .setNegativeButton(R.string.drivedeck_proton_switch_ask_no) { _, _ ->
                    showStatus(null)
                    fallback()
                }
                .setCancelable(false)
                .show()
                // D-pad users start on the action, not on "Not now".
                .getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
        } catch (e: Exception) {
            // The activity went away under the dialog.
            showStatus(null)
            fallback()
        }
    }

    private fun switchNow(fallback: () -> Unit) {
        player.onBuffering()
        showStatus(player.context.getString(R.string.drivedeck_proton_switch_switching))
        ProtonQuickSwitch.requestSwitch(player.context) { result ->
            when (result) {
                is ProtonQuickSwitch.Result.Switched -> {
                    showStatus(player.context.getString(R.string.drivedeck_proton_switch_continuing))
                    // Resolved stream URLs belong to the old address: resolve them again.
                    InfoCache.getInstance().clearCache()
                    player.reloadPlayQueueManager()
                    hideChecks = 0
                    main.removeCallbacks(hideWhenPlaying)
                    main.postDelayed(hideWhenPlaying, 500L)
                }
                is ProtonQuickSwitch.Result.Failed -> giveUp(
                    player.context.getString(
                        R.string.drivedeck_proton_switch_failed,
                        result.message.ifBlank { player.context.getString(R.string.drivedeck_proton_switch_no_reason) },
                    ),
                    fallback,
                )
                ProtonQuickSwitch.Result.NoAnswer ->
                    giveUp(player.context.getString(R.string.drivedeck_proton_switch_no_answer), fallback)
                ProtonQuickSwitch.Result.NotInstalled,
                ProtonQuickSwitch.Result.NoPermission -> {
                    showStatus(null)
                    fallback()
                }
            }
        }
    }

    private fun giveUp(message: String, fallback: () -> Unit) {
        showStatus(null)
        Toast.makeText(player.context, message, Toast.LENGTH_LONG).show()
        fallback()
    }

    private fun showStatus(text: String?) {
        val view = player.binding?.protonSwitchStatus ?: return
        view.text = text.orEmpty()
        view.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun toast(resId: Int, vararg args: Any) {
        Toast.makeText(player.context, player.context.getString(resId, *args), Toast.LENGTH_LONG).show()
    }

    fun release() {
        main.removeCallbacks(hideWhenPlaying)
    }

    companion object {
        private const val MAX_HIDE_CHECKS = 120

        private val budget get() = ProtonQuickSwitch.automaticBudget
    }
}
