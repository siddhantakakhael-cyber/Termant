package com.termant.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Starts Termant after the phone restarts, but only if there is something to
 * keep running: automation rules, or check-ins of the agent's own.
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        java.io.File rules = new java.io.File(context.getFilesDir(), "rules.json");
        if (!Ubuntu.isInstalled(context)) return;
        boolean caring = Care.on(context)
                && (!Care.times(context).isEmpty() || new java.io.File(context.getFilesDir(), "care.json").length() > 40);
        if (rules.length() < 40 && !caring) return; // an empty list is shorter than this
        Intent start = new Intent(context, TermService.class).setAction(TermService.ACTION_BOOT);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(start);
        else context.startService(start);
    }
}
