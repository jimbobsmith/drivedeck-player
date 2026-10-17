package org.schabi.newpipe.settings;

import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.Preference;

import org.schabi.newpipe.NewVersionWorker;
import org.schabi.newpipe.BuildConfig;
import org.schabi.newpipe.R;

public class UpdateSettingsFragment extends BasePreferenceFragment {
    private final Preference.OnPreferenceChangeListener updatePreferenceChange
            = (preference, checkForUpdates) -> {
        defaultPreferences.edit()
                .putBoolean(getString(R.string.update_app_key), (boolean) checkForUpdates).apply();

        if ((boolean) checkForUpdates) {
            NewVersionWorker.enqueueNewVersionCheckingWork(requireContext(), true);
        }
        return true;
    };

    private final Preference.OnPreferenceClickListener manualUpdateClick
            = preference -> {
        Toast.makeText(getContext(), R.string.checking_updates_toast, Toast.LENGTH_SHORT).show();
        NewVersionWorker.enqueueNewVersionCheckingWork(requireContext(), true);
        return true;
    };

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        addPreferencesFromResourceRegistry();
        if (!BuildConfig.ENABLE_APP_UPDATES) {
            getPreferenceScreen().removeAll();
            final Preference status = new Preference(requireContext());
            status.setTitle(R.string.player_updates_unavailable);
            status.setSummary(R.string.player_updates_unavailable_summary);
            status.setSelectable(false);
            getPreferenceScreen().addPreference(status);
            return;
        }

        findPreference(getString(R.string.update_app_key))
                .setOnPreferenceChangeListener(updatePreferenceChange);
        findPreference(getString(R.string.manual_update_key))
                .setOnPreferenceClickListener(manualUpdateClick);
    }
}
