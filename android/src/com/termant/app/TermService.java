package com.termant.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;

/**
 * Owns the terminal sessions. Running as a foreground service keeps shells and
 * whatever runs in them alive while the app is in the background.
 */
public class TermService extends Service implements TerminalSessionClient {
    static final String ACTION_EXIT = "com.termant.app.EXIT";
    static final String ACTION_RING = "com.termant.app.RING";
    static final String ACTION_RULE = "com.termant.app.RULE";
    static final String ACTION_BOOT = "com.termant.app.BOOT";
    static final String ACTION_STOP_RING = "com.termant.app.STOP_RING";
    static final String EXTRA_LABEL = "label";
    private static final String ALARM_CHANNEL = "alarm";
    private static final int ALARM_NOTIFICATION_ID = 2;
    private static final long RING_LIMIT_MS = 5 * 60 * 1000;
    static final int MAX_SESSIONS = 8;
    private static final String CHANNEL = "sessions";
    private static final int NOTIFICATION_ID = 1;

    /** The visible screen, if any. */
    interface Screen {
        void onSessionTextChanged(TerminalSession session);

        void onSessionListChanged();

        void onSessionExited(TerminalSession session);

        void onExitRequested();
    }

    class LocalBinder extends Binder {
        TermService service() {
            return TermService.this;
        }
    }

    final List<TerminalSession> sessions = new ArrayList<>();
    /** Sessions that were opened as AI agent tabs. */
    final Set<TerminalSession> agentSessions = new HashSet<>();
    /** The chat with the agent; lives here so a reply keeps arriving while the screen is closed. */
    Chat chat;
    /** Whether the terminal, not the chat, was the screen last shown. */
    boolean terminalShown;
    /** Automation rules; they keep running as long as this service does. */
    Rules rules;
    private PhoneBridge bridge;
    private MediaPlayer ringer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ringTimeout = new Runnable() {
        @Override
        public void run() {
            stopRinging();
        }
    };
    private final IBinder binder = new LocalBinder();
    private Screen screen;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        applyTerminalColors();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        startForeground(NOTIFICATION_ID, buildNotification());
        chat = new Chat(this);
        rules = new Rules(this);
        rules.start();
        bridge = new PhoneBridge(this);
        bridge.start();
    }

    /** Terminal palette matching the app's dark theme. */
    private static void applyTerminalColors() {
        String[] ansi = {
                "#484f58", "#ff7b72", "#3fb950", "#d29922", "#58a6ff", "#bc8cff", "#39c5cf", "#b1bac4",
                "#6e7681", "#ffa198", "#56d364", "#e3b341", "#79c0ff", "#d2a8ff", "#56d4dd", "#f0f6fc"};
        java.util.Properties p = new java.util.Properties();
        p.setProperty("foreground", "#e6edf3");
        p.setProperty("background", "#0d1117");
        p.setProperty("cursor", "#ff7a45");
        for (int i = 0; i < ansi.length; i++) p.setProperty("color" + i, ansi[i]);
        com.termux.terminal.TerminalColors.COLOR_SCHEME.updateWith(p);
    }

    /** Called when Ubuntu has just been (re)installed. */
    void onUbuntuReady() {
        if (bridge != null) bridge.publish();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_EXIT.equals(action)) {
            exit();
        } else if (ACTION_RING.equals(action)) {
            String label = intent.getStringExtra(EXTRA_LABEL);
            startRinging(label == null ? "Alarm" : label);
        } else if (ACTION_STOP_RING.equals(action)) {
            stopRinging();
        } else if (ACTION_RULE.equals(action) && intent.getData() != null) {
            try {
                rules.onAlarm(Integer.parseInt(intent.getData().getLastPathSegment()));
            } catch (NumberFormatException e) {
                // not one of ours
            }
        } else if (ACTION_BOOT.equals(action)) {
            rules.fire("boot");
        }
        // Restarted by Android if it is ever killed, so rules keep working.
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        killAll();
        setWakeLock(false);
        stopRinging();
        rules.stop();
        if (bridge != null) bridge.stop();
        super.onDestroy();
    }

    void setScreen(Screen s) {
        screen = s;
    }

    // ------------------------------------------------------------ sessions

    TerminalSession createSession() {
        return createSession(Ubuntu.shellCommand(this));
    }

    TerminalSession createSession(String[] command) {
        if (sessions.size() >= MAX_SESSIONS) return null;
        TerminalSession session = new TerminalSession(
                Ubuntu.proot(this).getPath(),
                getFilesDir().getPath(),
                command,
                Ubuntu.hostEnvironment(this),
                5000,
                this);
        sessions.add(session);
        changed();
        return session;
    }

    void removeSession(TerminalSession session) {
        session.finishIfRunning();
        sessions.remove(session);
        agentSessions.remove(session);
        changed();
    }

    void killAll() {
        for (TerminalSession s : sessions) s.finishIfRunning();
        sessions.clear();
        agentSessions.clear();
    }

    /** Stops everything: sessions, notification, service and the screen. */
    void exit() {
        killAll();
        chat.stop();
        setWakeLock(false);
        stopRinging();
        if (screen != null) screen.onExitRequested();
        stopForeground(true);
        stopSelf();
    }

    private void changed() {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
        if (screen != null) screen.onSessionListChanged();
    }

    // ------------------------------------------------------------ ringing

    /** Plays the phone's alarm sound in a loop, with a notification to stop it. */
    void startRinging(String label) {
        stopRinging();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    ALARM_CHANNEL, "Alarms", NotificationManager.IMPORTANCE_HIGH);
            channel.setSound(null, null); // the sound is played below, on the alarm volume
            nm.createNotificationChannel(channel);
        }
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, TermService.class).setAction(ACTION_STOP_RING), immutable);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), immutable);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, ALARM_CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(label)
                .setContentText(getString(R.string.alarm_ringing))
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .setContentIntent(stop)
                .setFullScreenIntent(open, true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.alarm_stop), stop).build());
        nm.notify(ALARM_NOTIFICATION_ID, b.build());

        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (sound == null) sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        if (sound == null) sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        try {
            MediaPlayer player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            player.setDataSource(this, sound);
            player.setLooping(true);
            player.prepare();
            player.start();
            ringer = player;
        } catch (Exception e) {
            android.util.Log.e("Termant", "cannot play the alarm sound", e);
        }
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) v.vibrate(new long[]{0, 600, 400}, 0);
        android.util.Log.i("Termant", "ringing: " + label);
        handler.postDelayed(ringTimeout, RING_LIMIT_MS);
    }

    void stopRinging() {
        handler.removeCallbacks(ringTimeout);
        if (ringer != null) {
            try {
                ringer.stop();
            } catch (Exception e) {
                // not playing
            }
            ringer.release();
            ringer = null;
        }
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) v.cancel();
        getSystemService(NotificationManager.class).cancel(ALARM_NOTIFICATION_ID);
    }

    boolean hasWakeLock() {
        return wakeLock != null;
    }

    /** Stops Android from pausing the processor while the screen is off. */
    void setWakeLock(boolean on) {
        if (on == (wakeLock != null)) return;
        if (on) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "termant:sessions");
            wakeLock.acquire();
        } else {
            wakeLock.release();
            wakeLock = null;
        }
    }

    private Notification buildNotification() {
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), immutable);
        PendingIntent exit = PendingIntent.getService(this, 1,
                new Intent(this, TermService.class).setAction(ACTION_EXIT), immutable);

        int count = sessions.size();
        String text = count == 0 ? getString(R.string.notification_text_none) : count == 1
                ? getString(R.string.notification_text_one)
                : getString(R.string.notification_text_many, count);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, getString(R.string.notification_exit), exit).build());
        return b.build();
    }

    // ------------------------------------------------------------ TerminalSessionClient

    @Override
    public void onTextChanged(TerminalSession session) {
        if (screen != null) screen.onSessionTextChanged(session);
    }

    @Override
    public void onTitleChanged(TerminalSession session) {
        if (screen != null) screen.onSessionListChanged();
    }

    @Override
    public void onSessionFinished(TerminalSession session) {
        if (screen != null) {
            screen.onSessionExited(session);
        } else if (session.getExitStatus() == 0) {
            sessions.remove(session);
            agentSessions.remove(session);
            changed();
        }
    }

    @Override
    public void onCopyTextToClipboard(TerminalSession session, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Termant", text));
    }

    @Override
    public void onPasteTextFromClipboard(TerminalSession session) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        if (text != null && text.length() > 0 && session.getEmulator() != null) {
            session.getEmulator().paste(text.toString());
        }
    }

    @Override
    public void onBell(TerminalSession session) {
    }

    @Override
    public void onColorsChanged(TerminalSession session) {
    }

    @Override
    public void onTerminalCursorStateChange(boolean state) {
    }

    @Override
    public Integer getTerminalCursorStyle() {
        return null;
    }

    @Override
    public void logError(String tag, String message) {
        android.util.Log.e(tag, message);
    }

    @Override
    public void logWarn(String tag, String message) {
        android.util.Log.w(tag, message);
    }

    @Override
    public void logInfo(String tag, String message) {
    }

    @Override
    public void logDebug(String tag, String message) {
    }

    @Override
    public void logVerbose(String tag, String message) {
    }

    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception e) {
        android.util.Log.e(tag, message, e);
    }

    @Override
    public void logStackTrace(String tag, Exception e) {
        android.util.Log.e(tag, "error", e);
    }
}
