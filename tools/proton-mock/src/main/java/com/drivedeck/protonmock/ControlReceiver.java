package com.drivedeck.protonmock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/** Sets the stand-in's scenario and delay from adb, and resets its request count. */
public final class ControlReceiver extends BroadcastReceiver {
    static SharedPreferences prefs(final Context context) {
        return context.getSharedPreferences("mock", Context.MODE_PRIVATE);
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
        final String scenario = intent.getStringExtra("scenario");
        final int delay = intent.getIntExtra("delay", 3000);
        prefs(context).edit()
                .putString("scenario", scenario == null ? "ok" : scenario)
                .putInt("delay", delay)
                .putInt("requests", 0)
                .apply();
        Log.i(QuickSwitchReceiver.TAG, "scenario " + scenario + ", delay " + delay);
    }
}
