package com.termant.app;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Lets the agent read the phone's notifications (`phone notifications`). Android starts this
 * service only after the user allowed Termant under "Notification access" in the phone's settings.
 */
public final class NotifyListener extends NotificationListenerService {
    private static final int KEPT = 80;
    /** The newest notifications, also those that are gone again: {time in ms, app, title, text}. */
    private static final List<String[]> seen = new ArrayList<>();
    private static volatile NotifyListener live;

    @Override
    public void onListenerConnected() {
        live = this;
        try {
            StatusBarNotification[] now = getActiveNotifications();
            if (now != null) for (StatusBarNotification n : now) keep(n);
        } catch (RuntimeException e) {
            // Android did not hand them over; new ones still arrive
        }
        android.util.Log.i("Termant", "notifications: reading is on");
    }

    @Override
    public void onListenerDisconnected() {
        live = null;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification n) {
        keep(n);
    }

    private void keep(StatusBarNotification n) {
        if (n == null || n.isOngoing()) return;
        Notification of = n.getNotification();
        Bundle extras = of == null ? null : of.extras;
        if (extras == null) return;
        String title = text(extras.getCharSequence(Notification.EXTRA_TITLE));
        CharSequence big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        String words = text(big != null ? big : extras.getCharSequence(Notification.EXTRA_TEXT));
        if (title.isEmpty() && words.isEmpty()) return;
        String app = n.getPackageName();
        try {
            app = String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(app, 0)));
        } catch (Exception e) {
            // the package name will do
        }
        String[] line = {String.valueOf(n.getPostTime()), app, title, words};
        synchronized (seen) {
            // an app that keeps changing one notification (a download, a timer) gets one line
            for (Iterator<String[]> i = seen.iterator(); i.hasNext();) {
                String[] old = i.next();
                if (old[1].equals(app) && old[2].equals(title) && old[3].equals(words)) i.remove();
            }
            seen.add(line);
            while (seen.size() > KEPT) seen.remove(0);
        }
    }

    private static String text(CharSequence c) {
        if (c == null) return "";
        String s = c.toString().replaceAll("\\s+", " ").trim();
        return s.length() > 300 ? s.substring(0, 300) + "\u2026" : s;
    }

    /** Whether the user allowed Termant to read notifications. */
    static boolean allowed(Context c) {
        String all = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return all != null && all.contains(c.getPackageName() + "/");
    }

    /** `phone notifications [N | allow | clear]`. */
    static String command(Context c, List<String> args) throws PhoneBridge.Refused {
        String what = args.size() > 1 ? args.get(1) : "";
        if (what.equals("allow")) {
            if (allowed(c)) return "Termant may read notifications already.";
            try {
                c.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                throw new PhoneBridge.Refused("The settings page could not be opened. The user opens it: Android Settings > "
                        + "Notifications > Notification access (also called Device & app notifications) > Termant.");
            }
            return "The phone's \"Notification access\" page is open. Ask the user to turn Termant on there, then try again.";
        }
        if (!allowed(c)) {
            throw new PhoneBridge.Refused("Termant may not read notifications yet. The user allows it once: Android Settings > "
                    + "Notifications > Notification access > Termant. `phone notifications allow` opens that page.");
        }
        if (what.equals("clear")) {
            synchronized (seen) {
                seen.clear();
            }
            return "The list is empty now; new notifications are added as they come.";
        }
        int count = 20;
        if (what.matches("\\d{1,3}")) count = Math.max(1, Math.min(KEPT, Integer.parseInt(what)));
        else if (!what.isEmpty()) throw new PhoneBridge.Refused("usage: phone notifications [N | allow | clear]");
        List<String[]> lines;
        synchronized (seen) {
            lines = new ArrayList<>(seen.subList(Math.max(0, seen.size() - count), seen.size()));
        }
        if (lines.isEmpty()) {
            return live == null ? "No notifications yet (reading has only just been allowed; they are listed as they come)."
                    : "No notifications since reading was allowed.";
        }
        SimpleDateFormat when = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
        StringBuilder b = new StringBuilder("Notifications, newest last (").append(lines.size()).append("):\n");
        for (String[] l : lines) {
            b.append(when.format(new Date(Long.parseLong(l[0])))).append("  ").append(l[1]).append(": ").append(l[2]);
            if (!l[3].isEmpty()) b.append(l[2].isEmpty() ? "" : " - ").append(l[3]);
            b.append('\n');
        }
        return b.toString().trim();
    }
}
