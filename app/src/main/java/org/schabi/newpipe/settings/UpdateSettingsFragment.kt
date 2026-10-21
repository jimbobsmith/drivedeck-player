package org.schabi.newpipe.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.launch
import org.schabi.newpipe.R
import org.schabi.newpipe.updates.PlayerUpdateManager
import org.schabi.newpipe.updates.UpdatePhase

class UpdateSettingsFragment : BasePreferenceFragment() {
    private val manager get() = PlayerUpdateManager.get(requireContext())
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResourceRegistry()
        findPreference<SwitchPreferenceCompat>("player_auto_updates")!!.apply {
            isChecked = manager.automatic
            setOnPreferenceChangeListener { _, value ->
                manager.setAutomatic(value as Boolean)
                if (value && Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                true
            }
        }
        findPreference<SwitchPreferenceCompat>("player_auto_download")!!.apply {
            isChecked = manager.autoDownload
            setOnPreferenceChangeListener { _, value -> manager.setAutoDownload(value as Boolean); true }
        }
        findPreference<Preference>("player_update_check")!!.setOnPreferenceClickListener { manager.checkNow(); true }
        findPreference<Preference>("player_update_download")!!.setOnPreferenceClickListener { manager.downloadAvailable(); true }
        findPreference<Preference>("player_update_cancel")!!.setOnPreferenceClickListener { manager.cancel(); true }
        findPreference<Preference>("player_update_install")!!.setOnPreferenceClickListener {
            if (!manager.canInstall()) openInstaller(manager.installPermissionIntent())
            else lifecycleScope.launch { manager.installIntent()?.let { openInstaller(it) } }
            true
        }
    }
    private fun openInstaller(intent: Intent) {
        try { startActivity(intent) }
        catch (_: ActivityNotFoundException) { installerUnavailable() }
        catch (_: SecurityException) { installerUnavailable() }
    }
    private fun installerUnavailable() {
        Toast.makeText(requireContext(), R.string.player_update_installer_unavailable, Toast.LENGTH_LONG).show()
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { manager.state.collect { render() } }
        }
    }
    override fun onResume() { super.onResume(); render() }
    private fun render() {
        val state = manager.state.value
        val busy = state.phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)
        findPreference<Preference>("player_update_status")?.summary = state.message +
            if (state.totalBytes > 0) " (${state.downloadedBytes * 100 / state.totalBytes}%)" else ""
        findPreference<Preference>("player_update_check")?.isEnabled = !busy && state.phase != UpdatePhase.READY_TO_INSTALL
        findPreference<Preference>("player_update_download")?.apply { isVisible = state.phase == UpdatePhase.AVAILABLE; isEnabled = !busy }
        findPreference<Preference>("player_update_install")?.apply {
            isVisible = state.phase == UpdatePhase.READY_TO_INSTALL
            setTitle(if (manager.canInstall()) R.string.player_update_install else R.string.player_update_permission)
        }
        findPreference<Preference>("player_update_cancel")?.isVisible = busy
        findPreference<Preference>("player_auto_download")?.isEnabled = manager.automatic
    }
}
