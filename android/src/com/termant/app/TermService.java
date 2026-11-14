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
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns the terminal sessions. Running as a foreground service keeps shells and
 * whatever runs in them alive while the app is in the background.
 */
public class TermService extends Service implements TerminalSessionClient {
    static final String ACTION_EXIT = "com.termant.app.EXIT";
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
    private final IBinder binder = new LocalBinder();
    private Screen screen;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_EXIT.equals(intent.getAction())) {
            exit();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        killAll();
        setWakeLock(false);
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
        changed();
    }

    void killAll() {
        for (TerminalSession s : sessions) s.finishIfRunning();
        sessions.clear();
    }

    /** Stops everything: sessions, notification, service and the screen. */
    void exit() {
        killAll();
        setWakeLock(false);
        if (screen != null) screen.onExitRequested();
        stopForeground(true);
        stopSelf();
    }

    private void changed() {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
        if (screen != null) screen.onSessionListChanged();
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
        String text = count == 1
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
            if (sessions.isEmpty()) exit();
            else changed();
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
