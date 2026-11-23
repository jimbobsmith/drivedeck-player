package org.schabi.newpipe.settings;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.preference.Preference;

import com.google.android.material.snackbar.Snackbar;

import org.schabi.newpipe.R;
import org.schabi.newpipe.drivedeck.ProtonQuickSwitch;
import org.schabi.newpipe.util.PermissionHelper;

import kotlin.Unit;

public class VideoAudioSettingsFragment extends BasePreferenceFragment {
    private SharedPreferences.OnSharedPreferenceChangeListener listener;

    @Override
    public void onCreatePreferences(@Nullable final Bundle savedInstanceState,
                                    @Nullable final String rootKey) {
        addPreferencesFromResourceRegistry();

        listener = (sharedPreferences, s) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && s.equals(getString(R.string.minimize_on_exit_key))) {
                final String newSetting = sharedPreferences.getString(s, null);
                if (newSetting != null
                        && newSetting.equals(getString(R.string.minimize_on_exit_popup_key))
                        && !Settings.canDrawOverlays(getContext())) {

                    Snackbar.make(getListView(), R.string.permission_display_over_apps,
                                    Snackbar.LENGTH_INDEFINITE)
                            .setAction(R.string.settings, view ->
                                    PermissionHelper.checkSystemAlertWindowPermission(getContext()))
                            .show();

                }
            }
        };
    }

    @Override
    public boolean onPreferenceTreeClick(final Preference preference) {
        if (getString(R.string.caption_settings_key).equals(preference.getKey())) {
            try {
                startActivity(new Intent(Settings.ACTION_CAPTIONING_SETTINGS));
            } catch (final ActivityNotFoundException e) {
                Toast.makeText(getActivity(), R.string.general_error, Toast.LENGTH_SHORT).show();
            }
        }

        return super.onPreferenceTreeClick(preference);
    }

    @Override
    public void onResume() {
        super.onResume();
        showProtonStatus();
        getPreferenceManager().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(listener);
    }

    /** DD: say whether the Proton app can be asked to switch, every time this screen shows. */
    private void showProtonStatus() {
        final Preference status = findPreference(getString(R.string.drivedeck_proton_switch_status_key));
        if (status == null) {
            return;
        }
        final ProtonQuickSwitch.Status found = ProtonQuickSwitch.INSTANCE.status(requireContext());
        if (found instanceof ProtonQuickSwitch.Status.Ready) {
            final boolean mock = ProtonQuickSwitch.MOCK_PACKAGE.equals(
                    ((ProtonQuickSwitch.Status.Ready) found).getPackageName());
            status.setSummary(mock ? R.string.drivedeck_proton_switch_status_mock
                    : R.string.drivedeck_proton_switch_status_ready);
        } else if (found == ProtonQuickSwitch.Status.NoPermission.INSTANCE) {
            status.setSummary(R.string.drivedeck_proton_switch_no_permission);
        } else {
            status.setSummary(R.string.drivedeck_proton_switch_status_missing);
        }
        showSwitchNow(found instanceof ProtonQuickSwitch.Status.Ready);
    }

    /** DD: offer "Switch server now" only while the Proton app can be asked. */
    private void showSwitchNow(final boolean ready) {
        final Preference switchNow = findPreference(getString(R.string.drivedeck_proton_switch_now_key));
        if (switchNow == null) {
            return;
        }
        switchNow.setVisible(ready);
        if (!ready) {
            return;
        }
        final boolean running = ProtonQuickSwitch.INSTANCE.isSwitching();
        switchNow.setEnabled(!running);
        if (running) {
            switchNow.setSummary(R.string.drivedeck_proton_switch_now_running);
        }
        switchNow.setOnPreferenceClickListener(preference -> {
            preference.setEnabled(false);
            preference.setSummary(R.string.drivedeck_proton_switch_now_running);
            ProtonQuickSwitch.INSTANCE.requestSwitch(requireContext(), result -> {
                if (isAdded()) {
                    preference.setEnabled(true);
                    preference.setSummary(describe(result));
                }
                return Unit.INSTANCE;
            });
            return true;
        });
    }

    private String describe(final ProtonQuickSwitch.Result result) {
        if (result instanceof ProtonQuickSwitch.Result.Switched) {
            return getString(R.string.drivedeck_proton_switch_now_done,
                    ((ProtonQuickSwitch.Result.Switched) result).getMessage());
        } else if (result instanceof ProtonQuickSwitch.Result.Failed) {
            final String reason = ((ProtonQuickSwitch.Result.Failed) result).getMessage();
            return getString(R.string.drivedeck_proton_switch_failed, reason.isEmpty()
                    ? getString(R.string.drivedeck_proton_switch_no_reason) : reason);
        } else if (result == ProtonQuickSwitch.Result.NoAnswer.INSTANCE) {
            return getString(R.string.drivedeck_proton_switch_no_answer);
        } else if (result == ProtonQuickSwitch.Result.NoPermission.INSTANCE) {
            return getString(R.string.drivedeck_proton_switch_no_permission);
        }
        return getString(R.string.drivedeck_proton_switch_status_missing);
    }

    @Override
    public void onPause() {
        super.onPause();
        getPreferenceManager().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(listener);
    }
}
