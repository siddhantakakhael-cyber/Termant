package com.termant.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.telephony.SmsManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.provider.AlarmClock;
import android.speech.tts.TextToSpeech;
import android.system.Os;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lets programs inside Ubuntu (the `phone` command, and through it the AI
 * agent) use phone features: alarms, notifications, speech, flashlight...
 *
 * Listens on a loopback port. Each request must start with a random token
 * that is stored only inside the app's private Ubuntu files, so other apps on
 * the phone cannot use it.
 */
final class PhoneBridge {
    private static final String HELP =
            "Control the phone from Ubuntu.\n\n"
                    + "  phone alarm HH:MM [label]   set an alarm (24-hour time, or 7am / 7:30pm)\n"
                    + "  phone timer N[s|m|h] [label] ring after a delay, e.g. 10m\n"
                    + "  phone ring [label]          ring loudly now until stopped\n"
                    + "  phone stop                  stop ringing\n"
                    + "  phone notify TITLE [TEXT]   show a notification\n"
                    + "  phone say TEXT              speak aloud\n"
                    + "  phone toast TEXT            show a short message\n"
                    + "  phone vibrate [MS]          vibrate (default 400)\n"
                    + "  phone torch on|off          flashlight\n"
                    + "  phone battery               charge level\n"
                    + "  phone clipboard get|set TEXT\n"
                    + "  phone open URL              open a link\n"
                    + "  phone ask QUESTION [CHOICE...]   ask the user and wait: with choices they pick one, without they type;\n"
                    + "                              shown over whatever app is on screen; prints the answer\n"
                    + "  phone app NAME              open an app by its name (phone apps lists them)\n"
                    + "  phone screen                what is on the screen now, as a numbered list\n"
                    + "  phone tap N | TEXT | X Y    tap item N of that list, the item with these words, or a point\n"
                    + "  phone type TEXT             type into the text box that has the cursor\n"
                    + "  phone swipe up|down|left|right   up shows what is further below\n"
                    + "  phone key back|home|recents|notifications|enter\n"
                    + "                              (screen, tap, type, swipe and key need Screen control: Termant Settings)\n"
                    + "  phone wifi on|off           Wi-Fi\n"
                    + "  phone volume 0-100          media volume\n"
                    + "  phone sms NUMBER TEXT       send a text message (costs what an SMS costs)\n"
                    + "  phone rule ...              automation: run a command when something happens (phone rule help)\n"
                    + "  phone wake on|off           keep running with the screen off\n"
                    + "  phone info                  phone model and Android version\n";

    private final TermService service;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String token;
    private ServerSocket server;
    private TextToSpeech tts;
    private boolean ttsReady;
    private int notificationId = 100;

    PhoneBridge(TermService service) {
        this.service = service;
        byte[] raw = new byte[16];
        new SecureRandom().nextBytes(raw);
        StringBuilder hex = new StringBuilder();
        for (byte b : raw) hex.append(String.format("%02x", b));
        token = hex.toString();
    }

    void start() {
        try {
            server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            android.util.Log.e("Termant", "phone bridge could not start", e);
            return;
        }
        publish();
        new Thread("termant-bridge") {
            @Override
            public void run() {
                while (!server.isClosed()) {
                    try {
                        final Socket client = server.accept();
                        new Thread("termant-bridge-request") {
                            @Override
                            public void run() {
                                serve(client);
                            }
                        }.start();
                    } catch (IOException e) {
                        break;
                    }
                }
            }
        }.start();
    }

    void stop() {
        try {
            if (server != null) server.close();
        } catch (IOException e) {
            // already closed
        }
        if (tts != null) tts.shutdown();
    }

    /** Tells Ubuntu where the bridge is. Called again once Ubuntu is installed. */
    void publish() {
        if (server == null || !Ubuntu.isInstalled(service)) return;
        try {
            File f = new File(Ubuntu.rootfs(service), "etc/termant/bridge");
            Installer.write(f, server.getLocalPort() + " " + token + "\n");
            Os.chmod(f.getPath(), 0600);
        } catch (Exception e) {
            android.util.Log.e("Termant", "phone bridge could not publish", e);
        }
    }

    // ------------------------------------------------------------ protocol

    /** Request: NUL-terminated token, argument count, arguments. Reply: "OK" or "ERR" line, then text. */
    private void serve(Socket client) {
        try (Socket c = client) {
            c.setSoTimeout(15000);
            InputStream in = c.getInputStream();
            String given = field(in);
            int count = Integer.parseInt(field(in));
            if (!token.equals(given) || count < 1 || count > 64) return;
            List<String> args = new ArrayList<>();
            for (int i = 0; i < count; i++) args.add(field(in));

            String status = "OK", text;
            try {
                text = run(args);
            } catch (Refused e) {
                status = "ERR";
                text = e.getMessage();
            } catch (Exception e) {
                status = "ERR";
                text = "phone: " + e;
            }
            if (!text.isEmpty() && !text.endsWith("\n")) text += "\n";
            OutputStream out = c.getOutputStream();
            out.write((status + "\n" + text).getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception e) {
            // broken request: nothing to answer
        }
    }

    private static String field(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        while (true) {
            int ch = in.read();
            if (ch < 0) throw new IOException("short request");
            if (ch == 0) break;
            if (b.size() > 100000) throw new IOException("request too long");
            b.write(ch);
        }
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    static final class Refused extends Exception {
        Refused(String message) {
            super(message);
        }
    }

    private interface Action {
        String run() throws Exception;
    }

    /** Runs on the main thread and waits for the result. */
    private String onMain(final Action action) throws Exception {
        final Object[] result = new Object[1];
        final CountDownLatch done = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    result[0] = action.run();
                } catch (Exception e) {
                    result[0] = e;
                }
                done.countDown();
            }
        });
        if (!done.await(10, TimeUnit.SECONDS)) throw new Refused("phone: the app did not answer");
        if (result[0] instanceof Exception) throw (Exception) result[0];
        return (String) result[0];
    }

    private static String rest(List<String> args, int from) {
        StringBuilder b = new StringBuilder();
        for (int i = from; i < args.size(); i++) {
            if (b.length() > 0) b.append(' ');
            b.append(args.get(i));
        }
        return b.toString().trim();
    }

    // ------------------------------------------------------------ commands

    private String run(final List<String> args) throws Exception {
        String command = args.get(0);
        switch (command) {
            case "help":
            case "--help":
            case "-h":
                return HELP;
            case "alarm":
                return alarm(args);
            case "timer":
                return timer(args);
            case "ring": {
                final String label = rest(args, 1);
                return onMain(new Action() {
                    @Override
                    public String run() {
                        service.startRinging(label.isEmpty() ? "Alarm" : label);
                        return "Ringing. Stop with: phone stop";
                    }
                });
            }
            case "stop":
                return onMain(new Action() {
                    @Override
                    public String run() {
                        service.stopRinging();
                        return "Stopped.";
                    }
                });
            case "notify":
                return notifyUser(args);
            case "say":
                return say(rest(args, 1));
            case "toast": {
                final String text = rest(args, 1);
                if (text.isEmpty()) throw new Refused("usage: phone toast TEXT");
                return onMain(new Action() {
                    @Override
                    public String run() {
                        Toast.makeText(service, text, Toast.LENGTH_LONG).show();
                        return "";
                    }
                });
            }
            case "vibrate": {
                long ms = 400;
                if (args.size() > 1) {
                    try {
                        ms = Math.max(1, Math.min(10000, Long.parseLong(args.get(1))));
                    } catch (NumberFormatException e) {
                        throw new Refused("usage: phone vibrate [MILLISECONDS]");
                    }
                }
                Vibrator v = (Vibrator) service.getSystemService(Context.VIBRATOR_SERVICE);
                if (v == null || !v.hasVibrator()) throw new Refused("This phone cannot vibrate.");
                v.vibrate(ms);
                return "";
            }
            case "torch":
                return torch(args);
            case "rule":
                return onMain(new Action() {
                    @Override
                    public String run() throws Exception {
                        return service.rules.command(args);
                    }
                });
            case "wifi":
                return wifi(args);
            case "volume":
                return volume(args);
            case "sms":
                return sms(args);
            case "battery":
                return battery();
            case "clipboard":
                return clipboard(args);
            case "open":
                return open(rest(args, 1));
            case "ask":
                return Ask.ask(service, args.size() > 1 ? args.get(1) : "",
                        args.size() > 2 ? args.subList(2, args.size()) : new ArrayList<String>());
            case "apps":
                return ScreenService.apps(service);
            case "app": {
                // Android lets an app open others only while it is on screen, or with screen control on
                ScreenService screen = ScreenService.get();
                if (screen == null && !MainActivity.isOnScreen()) {
                    throw new Refused("Android only allows opening apps while Termant is on screen, "
                            + "or with Screen control turned on (Termant \u2192 Settings).");
                }
                String[] opened = ScreenService.openApp(screen != null ? screen : service, rest(args, 1));
                if (screen == null) return opened[0];
                screen.waitForApp(opened[1], 8000);
                return opened[0] + after(screen, 0);
            }
            case "screen":
                return screen().describe(250);
            case "tap": {
                ScreenService screen = screen();
                return screen.tap(args.subList(1, args.size())) + after(screen, 900);
            }
            case "type": {
                ScreenService screen = screen();
                return screen.type(rest(args, 1)) + after(screen, 400);
            }
            case "swipe": {
                ScreenService screen = screen();
                return screen.swipe(args.size() > 1 ? args.get(1) : "") + after(screen, 700);
            }
            case "key": {
                ScreenService screen = screen();
                return screen.key(args.size() > 1 ? args.get(1) : "") + after(screen, 900);
            }
            case "wake": {
                final String mode = args.size() > 1 ? args.get(1) : "";
                if (!mode.equals("on") && !mode.equals("off")) throw new Refused("usage: phone wake on|off");
                return onMain(new Action() {
                    @Override
                    public String run() {
                        service.setWakeLock(mode.equals("on"));
                        return mode.equals("on")
                                ? "The phone will keep running programs with the screen off."
                                : "Normal power saving is back on.";
                    }
                });
            }
            case "info":
                return Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE;
            default:
                throw new Refused("phone: unknown command '" + command + "'. See: phone help");
        }
    }

    /** Accepts 7, 07:00, 7.30, 7am, 7:30pm, 19:05. Returns {hour, minute}. */
    static int[] parseTime(String s) {
        Matcher m = Pattern.compile("(?i)^(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)?$").matcher(s.trim());
        if (!m.matches()) return null;
        int hour = Integer.parseInt(m.group(1));
        int minute = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        String half = m.group(3);
        if (half != null) {
            if (hour < 1 || hour > 12) return null;
            hour = hour % 12 + (half.equalsIgnoreCase("pm") ? 12 : 0);
        }
        if (hour > 23 || minute > 59) return null;
        return new int[]{hour, minute};
    }

    private String alarm(List<String> args) throws Exception {
        final int[] time = args.size() > 1 ? parseTime(args.get(1)) : null;
        if (time == null) throw new Refused("usage: phone alarm HH:MM [label]   (for example: phone alarm 07:00 Wake up)");
        final String label = rest(args, 2).isEmpty() ? "Alarm" : rest(args, 2);
        final String shown = String.format(Locale.US, "%02d:%02d", time[0], time[1]);

        return onMain(new Action() {
            @Override
            public String run() {
                // Preferred: the phone's own Clock app, so the alarm shows up
                // where the user expects it. Android only lets an app do that
                // while it is on screen.
                if (MainActivity.isOnScreen()) {
                    Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                            .putExtra(AlarmClock.EXTRA_HOUR, time[0])
                            .putExtra(AlarmClock.EXTRA_MINUTES, time[1])
                            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (i.resolveActivity(service.getPackageManager()) != null) {
                        try {
                            service.startActivity(i);
                            android.util.Log.i("Termant", "alarm " + shown + " sent to the Clock app");
                            return "Alarm set for " + shown + " in the phone's Clock app (\"" + label + "\").";
                        } catch (Exception e) {
                            // fall through to Termant's own alarm
                        }
                    }
                }
                Calendar when = Calendar.getInstance();
                when.set(Calendar.HOUR_OF_DAY, time[0]);
                when.set(Calendar.MINUTE, time[1]);
                when.set(Calendar.SECOND, 0);
                when.set(Calendar.MILLISECOND, 0);
                if (when.getTimeInMillis() <= System.currentTimeMillis()) when.add(Calendar.DAY_OF_YEAR, 1);
                schedule(when.getTimeInMillis(), label);
                return "Alarm set for " + new SimpleDateFormat("EEE HH:mm", Locale.US).format(when.getTime())
                        + " in Termant (\"" + label + "\"). Termant will ring; do not force-stop the app.";
            }
        });
    }

    private String timer(List<String> args) throws Exception {
        Matcher m = args.size() > 1
                ? Pattern.compile("(?i)^(\\d+)\\s*(s|m|h)?$").matcher(args.get(1)) : null;
        if (m == null || !m.matches()) throw new Refused("usage: phone timer N[s|m|h] [label]   (for example: phone timer 10m Tea)");
        long n = Long.parseLong(m.group(1));
        String unit = m.group(2) == null ? "s" : m.group(2).toLowerCase(Locale.US);
        final long seconds = unit.equals("h") ? n * 3600 : unit.equals("m") ? n * 60 : n;
        if (seconds < 1 || seconds > 7 * 24 * 3600) throw new Refused("phone timer: between 1 second and 7 days");
        final String label = rest(args, 2).isEmpty() ? "Timer" : rest(args, 2);
        return onMain(new Action() {
            @Override
            public String run() {
                long at = System.currentTimeMillis() + seconds * 1000;
                schedule(at, label);
                return "Timer set: Termant rings at "
                        + new SimpleDateFormat("HH:mm:ss", Locale.US).format(at) + " (\"" + label + "\").";
            }
        });
    }

    /** Termant's own alarm: Android wakes the phone at the time and the service rings. */
    private void schedule(long at, String label) {
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        Intent ring = new Intent(service, TermService.class)
                .setAction(TermService.ACTION_RING)
                .setData(Uri.parse("termant://alarm/" + at))
                .putExtra(TermService.EXTRA_LABEL, label);
        PendingIntent fire = Build.VERSION.SDK_INT >= 26
                ? PendingIntent.getForegroundService(service, 0, ring, PendingIntent.FLAG_UPDATE_CURRENT | immutable)
                : PendingIntent.getService(service, 0, ring, PendingIntent.FLAG_UPDATE_CURRENT | immutable);
        PendingIntent show = PendingIntent.getActivity(service, 0,
                new Intent(service, MainActivity.class), immutable);
        AlarmManager am = (AlarmManager) service.getSystemService(Context.ALARM_SERVICE);
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), fire);
        android.util.Log.i("Termant", "own alarm scheduled for " + at);
    }

    private String notifyUser(List<String> args) throws Exception {
        if (args.size() < 2) throw new Refused("usage: phone notify TITLE [TEXT]");
        NotificationManager nm = service.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    "agent", "Messages from Ubuntu", NotificationManager.IMPORTANCE_HIGH));
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(service, "agent") : new Notification.Builder(service);
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        b.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(args.get(1))
                .setContentText(rest(args, 2))
                .setStyle(new Notification.BigTextStyle().bigText(rest(args, 2)))
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(service, 0,
                        new Intent(service, MainActivity.class), immutable));
        nm.notify(notificationId++, b.build());
        return "";
    }

    private synchronized String say(final String text) throws Exception {
        if (text.isEmpty()) throw new Refused("usage: phone say TEXT");
        if (tts == null) {
            final CountDownLatch ready = new CountDownLatch(1);
            tts = new TextToSpeech(service, new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    ttsReady = status == TextToSpeech.SUCCESS;
                    ready.countDown();
                }
            });
            ready.await(10, TimeUnit.SECONDS);
        }
        if (!ttsReady) {
            tts = null;
            throw new Refused("This phone has no text-to-speech voice installed.");
        }
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "termant");
        return "";
    }

    private String torch(List<String> args) throws Exception {
        String mode = args.size() > 1 ? args.get(1) : "";
        if (!mode.equals("on") && !mode.equals("off")) throw new Refused("usage: phone torch on|off");
        CameraManager cm = (CameraManager) service.getSystemService(Context.CAMERA_SERVICE);
        for (String id : cm.getCameraIdList()) {
            Boolean flash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (flash != null && flash) {
                cm.setTorchMode(id, mode.equals("on"));
                return "Flashlight " + mode + ".";
            }
        }
        throw new Refused("This phone has no flashlight.");
    }

    private String wifi(List<String> args) throws Exception {
        String mode = args.size() > 1 ? args.get(1) : "";
        if (!mode.equals("on") && !mode.equals("off")) throw new Refused("usage: phone wifi on|off");
        WifiManager wm = (WifiManager) service.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        boolean done;
        try {
            done = wm != null && wm.setWifiEnabled(mode.equals("on"));
        } catch (Exception e) {
            done = false;
        }
        if (!done) throw new Refused("This phone does not let apps switch Wi-Fi. Switch it in the phone's settings.");
        return "Wi-Fi " + mode + ".";
    }

    private String volume(List<String> args) throws Exception {
        int percent;
        try {
            percent = Integer.parseInt(args.size() > 1 ? args.get(1) : "");
        } catch (NumberFormatException e) {
            percent = -1;
        }
        if (percent < 0 || percent > 100) throw new Refused("usage: phone volume 0-100");
        AudioManager am = (AudioManager) service.getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * percent / 100f), 0);
        } catch (SecurityException e) {
            throw new Refused("The phone is in Do Not Disturb mode and refused to change the volume.");
        }
        return "Media volume " + percent + "%.";
    }

    private String sms(List<String> args) throws Exception {
        String number = args.size() > 1 ? args.get(1) : "";
        String text = rest(args, 2);
        if (!number.matches("\\+?[0-9][0-9 -]{2,18}") || text.isEmpty()) {
            throw new Refused("usage: phone sms NUMBER TEXT   (for example: phone sms +919876543210 Ghar pahunch gaya)");
        }
        if (service.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    MainActivity.askForSms();
                }
            });
            throw new Refused("Termant is not allowed to send SMS yet. The phone is asking the user now "
                    + "(or: Settings > Automation > Allow SMS). Ask the user to allow it, then try again.");
        }
        SmsManager sm = SmsManager.getDefault();
        sm.sendMultipartTextMessage(number.replaceAll("[ -]", ""), null, sm.divideMessage(text), null, null);
        android.util.Log.i("Termant", "sms handed to the phone for " + number);
        return "SMS handed to the phone for sending to " + number + ".";
    }

    private String battery() {
        Intent i = service.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return "unknown";
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        return (level * 100 / Math.max(1, scale)) + "% " + (charging ? "charging" : "not charging");
    }

    private String clipboard(final List<String> args) throws Exception {
        final String mode = args.size() > 1 ? args.get(1) : "";
        if (!mode.equals("get") && !(mode.equals("set") && args.size() > 2)) {
            throw new Refused("usage: phone clipboard get | phone clipboard set TEXT");
        }
        return onMain(new Action() {
            @Override
            public String run() {
                ClipboardManager cm = (ClipboardManager) service.getSystemService(Context.CLIPBOARD_SERVICE);
                if (mode.equals("set")) {
                    cm.setPrimaryClip(ClipData.newPlainText("Termant", rest(args, 2)));
                    return "";
                }
                ClipData clip = cm.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) return "";
                CharSequence text = clip.getItemAt(0).coerceToText(service);
                return text == null ? "" : text.toString();
            }
        });
    }

    private static ScreenService screen() throws Refused {
        ScreenService screen = ScreenService.get();
        if (screen == null) throw new Refused(ScreenService.OFF);
        return screen;
    }

    /** What the screen shows once an action has had time to take effect, so the agent need not ask. */
    private static String after(ScreenService screen, long ms) throws InterruptedException {
        Thread.sleep(ms);
        return "\nThe screen now:\n" + screen.describe(80);
    }

    private String open(final String url) throws Exception {
        if (url.isEmpty()) throw new Refused("usage: phone open URL");
        if (!MainActivity.isOnScreen()) {
            throw new Refused("Android only allows opening things while Termant is on screen.");
        }
        final Uri uri = Uri.parse(url.contains(":") ? url : "https://" + url);
        if ("file".equals(uri.getScheme()) || "content".equals(uri.getScheme())) {
            throw new Refused("phone open: only links can be opened, not files.");
        }
        return onMain(new Action() {
            @Override
            public String run() throws Exception {
                try {
                    service.startActivity(new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) {
                    throw new Refused("No app on this phone can open " + uri);
                }
                return "Opened " + uri;
            }
        });
    }
}
