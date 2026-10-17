package org.schabi.newpipe.player;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import androidx.preference.PreferenceManager;
import org.schabi.newpipe.R;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Self-contained public-video check for a dedicated emulator; never targets a physical device. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 29)
public class MediaSessionFoundationTest {
    @Test public void exactPublicIdentityAndRealTransport() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var automation = instrumentation.getUiAutomation();
        automation.adoptShellPermissionIdentity(Manifest.permission.MEDIA_CONTENT_CONTROL);
        MediaController selected = null;
        var context = instrumentation.getTargetContext();
        assertTrue("This test is emulator-only", Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish"));
        var preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String choiceKey = context.getString(R.string.preferred_open_action_key);
        String previousChoice = preferences.getString(choiceKey, null);
        try {
            var manager = (MediaSessionManager) instrumentation.getTargetContext()
                    .getSystemService(Context.MEDIA_SESSION_SERVICE);
            String videoId = InstrumentationRegistry.getArguments().getString("videoId", "aqz-KE-bpKQ");
            assertTrue("Use an exact public test ID", videoId.matches("[A-Za-z0-9_-]{11}"));
            String expected = "https://www.youtube.com/watch?v=" + videoId;
            preferences.edit().putString(choiceKey, context.getString(R.string.video_player_key)).commit();
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(expected))
                    .setPackage(context.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            long launchDeadline = SystemClock.elapsedRealtime() + 45_000;
            while (selected == null && SystemClock.elapsedRealtime() < launchDeadline) {
                for (var controller : manager.getActiveSessions(null)) {
                    if (!controller.getPackageName().equals("com.drivedeck.player.test")) continue;
                    var metadata = controller.getMetadata();
                    if (metadata != null && expected.equals(metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI))) {
                        selected = controller;
                        break;
                    }
                }
                if (selected == null) SystemClock.sleep(200);
            }
            assertNotNull("A real player session must expose the exact public URI", selected);
            assertEquals(expected, selected.getMetadata().getString(MediaMetadata.METADATA_KEY_MEDIA_ID));
            assertTrue(selected.getMetadata().getLong(MediaMetadata.METADATA_KEY_DURATION) > 20_000);
            selected.getTransportControls().pause();
            awaitState(selected, PlaybackState.STATE_PAUSED);
            long paused = selected.getPlaybackState().getPosition();
            SystemClock.sleep(700);
            assertTrue("Pause must hold the playback position", Math.abs(selected.getPlaybackState().getPosition() - paused) < 250);
            selected.getTransportControls().play();
            awaitState(selected, PlaybackState.STATE_PLAYING);
            selected.getTransportControls().seekTo(15_000);
            long deadline = SystemClock.elapsedRealtime() + 10_000;
            boolean seekObserved = false;
            while (SystemClock.elapsedRealtime() < deadline) {
                var state = selected.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING
                        && Math.abs(state.getPosition() - 15_000) < 3_000) {
                    seekObserved = true;
                    break;
                }
                SystemClock.sleep(150);
            }
            assertTrue("Seek must reach the real playing session", seekObserved);
        } finally {
            if (selected != null) selected.getTransportControls().pause();
            if (previousChoice == null) preferences.edit().remove(choiceKey).commit();
            else preferences.edit().putString(choiceKey, previousChoice).commit();
            automation.dropShellPermissionIdentity();
        }
    }
    private static void awaitState(MediaController controller, int expected) {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            var state = controller.getPlaybackState();
            if (state != null && state.getState() == expected) return;
            SystemClock.sleep(150);
        }
        fail("Player did not reach expected state " + expected);
    }
}
