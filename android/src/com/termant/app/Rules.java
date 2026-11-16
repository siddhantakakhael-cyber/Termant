package com.termant.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Automation rules: "when this happens, run this command in Ubuntu".
 * Created with `phone rule add ...` (usually by the agent) and run by the
 * app on its own from then on. All methods must be called on the main thread.
 */
final class Rules {
    static final class Rule {
        int id;
        /** "at 23:00", "charging", "unplugged", "battery-below 20", "wifi-connected", "wifi-lost" or "boot". */
        String trigger;
        String command;
        boolean enabled = true;
        long lastRun;
        int lastExit;
        String lastOutput = "";
    }

    interface Listener {
        void onRulesChanged();
    }

    static final String USAGE =
            "usage:\n"
                    + "  phone rule add TRIGGER -- COMMAND   run COMMAND in Ubuntu whenever TRIGGER happens\n"
                    + "  phone rule list\n"
                    + "  phone rule remove ID | on ID | off ID\n"
                    + "  phone rule run ID                   run it now, to test it\n"
                    + "triggers:\n"
                    + "  at HH:MM          every day at that time (24-hour)\n"
                    + "  charging          the charger is plugged in\n"
                    + "  unplugged         the charger is removed\n"
                    + "  battery-below N   the battery drops under N percent\n"
                    + "  wifi-connected    the phone joins a Wi-Fi network\n"
                    + "  wifi-lost         the phone leaves Wi-Fi\n"
                    + "  boot              the phone has started\n"
                    + "example: phone rule add at 23:00 -- phone wifi off\n";

    private static final long RUN_LIMIT_MINUTES = 15;

    final List<Rule> rules = new ArrayList<>();
    private final TermService service;
    private final File store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private int nextId = 1;
    private int batteryLevel = -1;
    private long wifiWatchedSince;
    private boolean started;

    private final BroadcastReceiver power = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                fire("charging");
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                fire("unplugged");
            } else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100
                        / Math.max(1, intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
                int before = batteryLevel;
                batteryLevel = level;
                if (before < 0 || level >= before) return;
                for (Rule r : new ArrayList<>(rules)) {
                    if (!r.enabled || !r.trigger.startsWith("battery-below ")) continue;
                    int limit = Integer.parseInt(r.trigger.substring(14));
                    if (before >= limit && level < limit) run(r);
                }
            }
        }
    };

    private final ConnectivityManager.NetworkCallback wifi = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            wifiEvent("wifi-connected");
        }

        @Override
        public void onLost(Network network) {
            wifiEvent("wifi-lost");
        }
    };

    Rules(TermService service) {
        this.service = service;
        store = new File(service.getFilesDir(), "rules.json");
        load();
    }

    void setListener(Listener l) {
        listener = l;
    }

    void start() {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        f.addAction(Intent.ACTION_BATTERY_CHANGED);
        service.registerReceiver(power, f);
        try {
            wifiWatchedSince = SystemClock.elapsedRealtime();
            ConnectivityManager cm = (ConnectivityManager) service.getSystemService(Context.CONNECTIVITY_SERVICE);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifi);
        } catch (Exception e) {
            android.util.Log.e("Termant", "cannot watch Wi-Fi", e);
        }
        started = true;
        for (Rule r : rules) schedule(r);
    }

    void stop() {
        if (!started) return;
        started = false;
        try {
            service.unregisterReceiver(power);
            ((ConnectivityManager) service.getSystemService(Context.CONNECTIVITY_SERVICE))
                    .unregisterNetworkCallback(wifi);
        } catch (Exception e) {
            // not registered
        }
    }

    private void wifiEvent(final String event) {
        // Android reports the network that is already connected right after
        // registering; that is not the phone "joining" Wi-Fi.
        if (SystemClock.elapsedRealtime() - wifiWatchedSince < 5000) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                fire(event);
            }
        });
    }

    /** Runs every enabled rule waiting for this event. */
    void fire(String event) {
        for (Rule r : new ArrayList<>(rules)) {
            if (r.enabled && r.trigger.equals(event)) run(r);
        }
    }

    /** A timed rule's alarm went off. */
    void onAlarm(int id) {
        Rule r = find(id);
        if (r == null) return;
        schedule(r); // tomorrow
        if (r.enabled) run(r);
    }

    private Rule find(int id) {
        for (Rule r : rules) if (r.id == id) return r;
        return null;
    }

    // ------------------------------------------------------------ timed rules

    private PendingIntent alarmIntent(Rule r) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Intent i = new Intent(service, TermService.class)
                .setAction(TermService.ACTION_RULE)
                .setData(Uri.parse("termant://rule/" + r.id));
        return Build.VERSION.SDK_INT >= 26
                ? PendingIntent.getForegroundService(service, 0, i, flags)
                : PendingIntent.getService(service, 0, i, flags);
    }

    private void schedule(Rule r) {
        if (!r.trigger.startsWith("at ")) return;
        AlarmManager am = (AlarmManager) service.getSystemService(Context.ALARM_SERVICE);
        PendingIntent fire = alarmIntent(r);
        am.cancel(fire);
        if (!r.enabled) return;
        int[] time = PhoneBridge.parseTime(r.trigger.substring(3));
        Calendar when = Calendar.getInstance();
        when.set(Calendar.HOUR_OF_DAY, time[0]);
        when.set(Calendar.MINUTE, time[1]);
        when.set(Calendar.SECOND, 0);
        when.set(Calendar.MILLISECOND, 0);
        // a little slack, so a rule that has just fired is not set for today again
        if (when.getTimeInMillis() <= System.currentTimeMillis() + 5000) when.add(Calendar.DAY_OF_YEAR, 1);
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when.getTimeInMillis(), fire);
    }

    // ------------------------------------------------------------ running

    private void run(final Rule r) {
        if (!Ubuntu.isInstalled(service)) return;
        android.util.Log.i("Termant", "rule " + r.id + " (" + r.trigger + ") runs: " + r.command);
        PowerManager pm = (PowerManager) service.getSystemService(Context.POWER_SERVICE);
        final PowerManager.WakeLock awake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "termant:rule");
        awake.acquire(RUN_LIMIT_MINUTES * 60 * 1000);
        final String command = r.command;
        new Thread("termant-rule-" + r.id) {
            @Override
            public void run() {
                int exit = -1;
                String output = "";
                try {
                    Ubuntu.prepareHost(service);
                    ProcessBuilder pb = new ProcessBuilder(Ubuntu.command(service, "/bin/bash", "-l", "-c", command));
                    Map<String, String> env = pb.environment();
                    env.clear();
                    for (String e : Ubuntu.hostEnvironment(service)) {
                        int eq = e.indexOf('=');
                        env.put(e.substring(0, eq), e.substring(eq + 1));
                    }
                    pb.redirectErrorStream(true);
                    final Process p = pb.start();
                    p.getOutputStream().close();
                    final StringBuilder out = new StringBuilder();
                    Thread reader = new Thread() {
                        @Override
                        public void run() {
                            try {
                                InputStream in = p.getInputStream();
                                byte[] buf = new byte[4096];
                                int n;
                                while ((n = in.read(buf)) > 0) {
                                    synchronized (out) {
                                        out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                                        if (out.length() > 8000) out.delete(0, out.length() - 4000);
                                    }
                                }
                            } catch (Exception e) {
                                // the process is gone
                            }
                        }
                    };
                    reader.start();
                    if (Build.VERSION.SDK_INT >= 26) {
                        if (p.waitFor(RUN_LIMIT_MINUTES, TimeUnit.MINUTES)) {
                            exit = p.exitValue();
                        } else {
                            p.destroy();
                            exit = 124;
                        }
                    } else {
                        exit = p.waitFor();
                    }
                    reader.join(2000);
                    synchronized (out) {
                        output = out.toString().trim();
                    }
                } catch (Exception e) {
                    output = String.valueOf(e);
                } finally {
                    if (awake.isHeld()) awake.release();
                }
                final int code = exit;
                final String text = output.length() > 600 ? output.substring(output.length() - 600) : output;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        finished(r, code, text);
                    }
                });
            }
        }.start();
    }

    private void finished(Rule r, int exit, String output) {
        r.lastRun = System.currentTimeMillis();
        r.lastExit = exit;
        r.lastOutput = output;
        android.util.Log.i("Termant", "rule " + r.id + " finished with " + exit);
        save();
        if (exit != 0) {
            NotificationManager nm = service.getSystemService(NotificationManager.class);
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(
                        "rules", "Automation rules", NotificationManager.IMPORTANCE_DEFAULT));
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(service, "rules") : new Notification.Builder(service);
            b.setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(Lang.of(service).getString(R.string.rule_failed, describe(r.trigger)))
                    .setContentText(output.isEmpty() ? r.command : output)
                    .setStyle(new Notification.BigTextStyle().bigText(r.command + "\n" + output))
                    .setAutoCancel(true);
            nm.notify(1000 + r.id, b.build());
        }
        changed();
    }

    // ------------------------------------------------------------ `phone rule ...`

    String command(List<String> args) throws PhoneBridge.Refused {
        String verb = args.size() > 1 ? args.get(1) : "";
        switch (verb) {
            case "add":
                return add(args);
            case "list": {
                if (rules.isEmpty()) return "No rules yet.\n";
                StringBuilder b = new StringBuilder();
                for (Rule r : rules) {
                    b.append(r.id).append("  ").append(r.enabled ? "on " : "off").append("  ")
                            .append(r.trigger).append("  ->  ").append(r.command);
                    if (r.lastRun > 0) {
                        b.append("   (last run ").append(new SimpleDateFormat("d MMM HH:mm", Locale.US).format(r.lastRun))
                                .append(", exit ").append(r.lastExit).append(")");
                    }
                    b.append('\n');
                }
                return b.toString();
            }
            case "remove":
            case "on":
            case "off":
            case "run": {
                Rule r = null;
                try {
                    r = find(Integer.parseInt(args.size() > 2 ? args.get(2) : ""));
                } catch (NumberFormatException e) {
                    // reported below
                }
                if (r == null) throw new PhoneBridge.Refused("phone rule " + verb + ": give the rule's number, see: phone rule list");
                if (verb.equals("run")) {
                    run(r);
                    return "Rule " + r.id + " started. See the result with: phone rule list";
                }
                if (verb.equals("remove")) remove(r);
                else setEnabled(r, verb.equals("on"));
                return "Rule " + r.id + (verb.equals("remove") ? " removed." : " is " + verb + ".");
            }
            default:
                throw new PhoneBridge.Refused(USAGE);
        }
    }

    private String add(List<String> args) throws PhoneBridge.Refused {
        int split = args.indexOf("--");
        if (split < 3 || split == args.size() - 1) throw new PhoneBridge.Refused(USAGE);
        String kind = args.get(2);
        String trigger;
        if (kind.equals("at") && split == 4) {
            int[] time = PhoneBridge.parseTime(args.get(3));
            if (time == null) throw new PhoneBridge.Refused("phone rule: time must look like 23:00");
            trigger = String.format(Locale.US, "at %02d:%02d", time[0], time[1]);
        } else if (kind.equals("battery-below") && split == 4 && args.get(3).matches("\\d{1,2}")) {
            trigger = "battery-below " + Integer.parseInt(args.get(3));
        } else if (split == 3 && (kind.equals("charging") || kind.equals("unplugged")
                || kind.equals("wifi-connected") || kind.equals("wifi-lost") || kind.equals("boot"))) {
            trigger = kind;
        } else {
            throw new PhoneBridge.Refused("phone rule: unknown trigger.\n" + USAGE);
        }
        StringBuilder command = new StringBuilder();
        for (int i = split + 1; i < args.size(); i++) command.append(i > split + 1 ? " " : "").append(args.get(i));
        if (rules.size() >= 50) throw new PhoneBridge.Refused("phone rule: there are already 50 rules; remove some first");

        Rule r = new Rule();
        r.id = nextId++;
        r.trigger = trigger;
        r.command = command.toString();
        rules.add(r);
        schedule(r);
        save();
        changed();
        return "Rule " + r.id + " added: " + describe(trigger) + ", run: " + r.command
                + "\nIt runs by itself from now on, as long as Termant is not force-stopped.";
    }

    void remove(Rule r) {
        r.enabled = false;
        schedule(r); // cancels its alarm
        rules.remove(r);
        save();
        changed();
    }

    void setEnabled(Rule r, boolean enabled) {
        r.enabled = enabled;
        schedule(r);
        save();
        changed();
    }

    /** The trigger in words, for people. */
    static String describe(String trigger) {
        if (trigger.startsWith("at ")) return "Every day at " + trigger.substring(3);
        if (trigger.startsWith("battery-below ")) return "Battery below " + trigger.substring(14) + "%";
        switch (trigger) {
            case "charging":
                return "When charging starts";
            case "unplugged":
                return "When the charger is removed";
            case "wifi-connected":
                return "When Wi-Fi connects";
            case "wifi-lost":
                return "When Wi-Fi disconnects";
            case "boot":
                return "When the phone starts";
            default:
                return trigger;
        }
    }

    private void changed() {
        if (listener != null) listener.onRulesChanged();
    }

    // ------------------------------------------------------------ storage

    private void save() {
        try {
            JSONArray list = new JSONArray();
            for (Rule r : rules) {
                list.put(new JSONObject().put("id", r.id).put("trigger", r.trigger).put("command", r.command)
                        .put("enabled", r.enabled).put("lastRun", r.lastRun).put("lastExit", r.lastExit)
                        .put("lastOutput", r.lastOutput));
            }
            Installer.write(store, new JSONObject().put("next", nextId).put("rules", list).toString());
        } catch (Exception e) {
            android.util.Log.e("Termant", "cannot save the rules", e);
        }
    }

    private void load() {
        if (!store.isFile()) return;
        try (InputStream in = new FileInputStream(store)) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            JSONObject o = new JSONObject(new String(b.toByteArray(), StandardCharsets.UTF_8));
            nextId = o.optInt("next", 1);
            JSONArray list = o.getJSONArray("rules");
            for (int i = 0; i < list.length(); i++) {
                JSONObject j = list.getJSONObject(i);
                Rule r = new Rule();
                r.id = j.getInt("id");
                r.trigger = j.getString("trigger");
                r.command = j.getString("command");
                r.enabled = j.optBoolean("enabled", true);
                r.lastRun = j.optLong("lastRun");
                r.lastExit = j.optInt("lastExit");
                r.lastOutput = j.optString("lastOutput");
                rules.add(r);
                nextId = Math.max(nextId, r.id + 1);
            }
        } catch (Exception e) {
            android.util.Log.e("Termant", "cannot read the rules", e);
        }
    }
}
