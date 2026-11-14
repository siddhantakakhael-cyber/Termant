package com.termant.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Starts Termant after the phone restarts, but only if there are automation rules to keep running. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        java.io.File rules = new java.io.File(context.getFilesDir(), "rules.json");
        if (!Ubuntu.isInstalled(context) || rules.length() < 40) return; // an empty list is shorter than this
        Intent start = new Intent(context, TermService.class).setAction(TermService.ACTION_BOOT);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(start);
        else context.startService(start);
    }
}
