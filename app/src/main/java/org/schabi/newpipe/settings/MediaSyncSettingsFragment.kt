package org.schabi.newpipe.settings

import android.os.Bundle
import android.os.Build
import android.text.InputType
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import org.json.JSONObject
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.mediasync.MediaSyncClient
import org.schabi.newpipe.mediasync.MediaSyncEnrollmentInput
import org.schabi.newpipe.mediasync.MediaSyncCrypto
import org.schabi.newpipe.mediasync.MediaSyncSecrets
import java.util.concurrent.Executors

/** Explicit user-initiated sync only; no worker/service or automatic playback. */
class MediaSyncSettingsFragment : BasePreferenceFragment() {
    private var busy = false
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val field = EditText(requireContext()).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; isSaveEnabled = false; maxLines = 1; hint = getString(R.string.media_sync_passphrase) }
            AlertDialog.Builder(requireContext()).setTitle(R.string.media_sync_import).setMessage(R.string.media_sync_import_summary).setView(field)
                .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.media_sync_import) { _, _ ->
                    val passphrase = field.text.toString(); field.text.clear()
                    perform {
                        val text = requireContext().contentResolver.openInputStream(uri)?.use { input ->
                            String(MediaSyncEnrollmentInput.read(input), Charsets.UTF_8)
                        } ?: throw IllegalStateException()
                        val config = MediaSyncCrypto.unprotect(JSONObject(text), passphrase)
                        val secrets = MediaSyncSecrets(requireContext()); MediaSyncCrypto.check(!secrets.exists(), "disconnect_before_enrollment")
                        val db = NewPipeDatabase.getInstance(requireContext()); MediaSyncCrypto.check(db.mediaSyncStateDAO().read() == null, "disconnect_before_enrollment")
                        secrets.save(config); MediaSyncClient(db, config).status()
                    }
                }.show()
        }
    }
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResourceRegistry()
        findPreference<Preference>("media_sync_import")!!.setOnPreferenceClickListener {
            if (!busy) picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")); true
        }
        for (action in listOf("sync", "keep-local", "use-remote", "disconnect", "library", "profiles")) {
            findPreference<Preference>("media_sync_$action")!!.setOnPreferenceClickListener {
                val run = {
                    perform {
                        val db = NewPipeDatabase.getInstance(requireContext()); val secrets = MediaSyncSecrets(requireContext())
                        if (action == "disconnect") { db.runInTransaction { db.mediaSyncStateDAO().clear() }; secrets.delete(); JSONObject().put("enabled", false).put("code", "disconnected") }
                        else if (action == "library") MediaSyncClient(db, secrets.load()).enableLibrary()
                        else if (action == "profiles") MediaSyncClient(db, secrets.load()).enableProfiles()
                        else MediaSyncClient(db, secrets.load()).sync(if (action == "sync") null else action)
                    }
                }
                if (action == "sync") run()
                else AlertDialog.Builder(requireContext()).setMessage(when(action) { "library" -> R.string.media_sync_library_confirm; "profiles" -> R.string.media_sync_profiles_confirm; else -> R.string.media_sync_confirm }).setNegativeButton(R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ -> run() }.show()
                true
            }
        }
        perform {
            val secrets = MediaSyncSecrets(requireContext())
            if (secrets.exists()) MediaSyncClient(NewPipeDatabase.getInstance(requireContext()), secrets.load()).status()
            else if (NewPipeDatabase.getInstance(requireContext()).mediaSyncStateDAO().read() != null) JSONObject().put("enabled", true).put("code", "failed")
            else JSONObject().put("enabled", false).put("code", "disconnected")
        }
    }
    private fun perform(operation: () -> JSONObject) {
        if (busy) return
        if (Build.VERSION.SDK_INT < 26) { findPreference<Preference>("media_sync_status")!!.setSummary(R.string.media_sync_requires_android); return }
        busy = true; for (key in listOf("import", "sync", "keep-local", "use-remote", "disconnect", "library", "profiles")) findPreference<Preference>("media_sync_$key")!!.isEnabled = false
        val application = requireActivity().application
        executor.execute {
            val result = try { operation() } catch (e: Exception) { JSONObject().put("enabled", MediaSyncSecrets(application).exists()).put("code", if (e is org.schabi.newpipe.mediasync.SyncFailure && e.code in listOf("restored_service", "library_upgrade_required", "profiles_upgrade_required", "profile_not_portable", "playlist_not_portable", "invalid_playlist_name")) e.code else "failed") }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                busy = false
                val enabled = result.optBoolean("enabled")
                val text = when (result.optString("code")) {
                    "disconnected" -> R.string.media_sync_disconnected
                    "ready" -> R.string.media_sync_ready
                    "synced" -> R.string.media_sync_synced
                    "offline" -> R.string.media_sync_offline
                    "pending" -> R.string.media_sync_pending
                    "conflict" -> R.string.media_sync_conflict
                    "library_upgrade_required" -> R.string.media_sync_library_upgrade
                    "profiles_upgrade_required" -> R.string.media_sync_profiles_upgrade
                    "profile_not_portable" -> R.string.media_sync_profiles_limit
                    "playlist_not_portable", "invalid_playlist_name" -> R.string.media_sync_library_limit
                    "restored_service" -> R.string.media_sync_restored
                    else -> R.string.media_sync_failed
                }
                findPreference<Preference>("media_sync_status")!!.setSummary(text)
                findPreference<Preference>("media_sync_import")!!.isEnabled = !enabled
                findPreference<Preference>("media_sync_library")!!.isEnabled = enabled && !result.optBoolean("libraryEnabled")
                findPreference<Preference>("media_sync_profiles")!!.isEnabled = enabled && !result.optBoolean("profilesEnabled")
                findPreference<Preference>("media_sync_sync")!!.isEnabled = enabled
                findPreference<Preference>("media_sync_disconnect")!!.isEnabled = enabled
                for (key in listOf("keep-local", "use-remote")) findPreference<Preference>("media_sync_$key")!!.isEnabled = enabled && result.optInt("conflicts") > 0
            }
        }
    }
    companion object { private val executor = Executors.newSingleThreadExecutor() }
}
