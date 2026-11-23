package com.drivedeck.protonmock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Answers a Quick switch request the way the Proton API client for Fire TV does: one
 * QUICK_SWITCH_RESULT broadcast with "ok" and "message", sent only to apps holding the
 * QUICK_SWITCH permission. The scenario comes from {@link ControlReceiver}.
 */
public final class QuickSwitchReceiver extends BroadcastReceiver {
    static final String TAG = "ProtonMock";
    static final String ACTION_RESULT = "com.jimbobsmith.protonapi.firetv.QUICK_SWITCH_RESULT";
    static final String PERMISSION = "com.jimbobsmith.protonapi.firetv.permission.QUICK_SWITCH";
    private static final String[] SERVERS = {"CA#101", "CA#202", "US-NY#303", "CH#404"};

    @Override
    public void onReceive(final Context context, final Intent intent) {
        final SharedPreferences prefs = ControlReceiver.prefs(context);
        final String scenario = prefs.getString("scenario", "ok");
        final int delay = Math.max(0, Math.min(8000, prefs.getInt("delay", 3000)));
        final int count = prefs.getInt("requests", 0) + 1;
        prefs.edit().putInt("requests", count).apply();
        Log.i(TAG, "quick switch request " + count + ", scenario " + scenario);
        if ("never".equals(scenario)) {
            return;
        }
        final PendingResult pending = goAsync();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            final boolean ok;
            final String message;
            switch (scenario) {
                case "fail":
                    ok = false;
                    message = "Could not reach a favorite server (stand-in). The previous tunnel stayed up.";
                    break;
                case "no-favorites":
                    ok = false;
                    message = "No favorites are saved yet.";
                    break;
                default:
                    ok = true;
                    message = SERVERS[count % SERVERS.length];
            }
            context.sendBroadcast(new Intent(ACTION_RESULT)
                    .putExtra("ok", ok).putExtra("message", message), PERMISSION);
            Log.i(TAG, "answered ok=" + ok + " message=" + message);
            pending.finish();
        }, delay);
    }
}
