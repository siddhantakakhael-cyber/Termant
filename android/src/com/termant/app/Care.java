package com.termant.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looking after the user without being asked: at the times of day chosen in
 * Settings, and whenever the agent planned to come back to something, the
 * agent writes the first message of its own accord ("How did the exam go?").
 * The message lands in the chat and, when Termant is not on screen, in a
 * notification that can be answered right there.
 */
final class Care {
    /** One thing the agent wants to come back to. A null topic is a daily check-in that had to wait. */
    static final class FollowUp {
        int id;
        long when;
        String topic;
        int tries;
    }

    static final String DEFAULT_TIMES = "09:00 21:00";
    static final int MAX_TIMES = 4, MAX_FOLLOW_UPS = 10, MAX_TOPIC = 200;
    /** Never more often than this, whatever was planned. */
    static final int MAX_PER_DAY = 6;
    private static final long MIN_GAP_MS = 30 * 60 * 1000;
    /** Someone who has just written is in the middle of something: wait. */
    private static final long JUST_WROTE_MS = 3 * 60 * 1000;
    private static final long WAIT_MS = 10 * 60 * 1000;
    private static final int MAX_TRIES = 3, MAX_TRIES_PLANNED = 12;
    /** After this many daily check-ins without an answer, no more until the user writes. */
    private static final int MAX_UNANSWERED = 3;
    /** Follow-ups do not wake anybody: one that falls into the night waits for the morning. */
    private static final int NIGHT_FROM = 23, NIGHT_TO = 7;
    private static final long SOON_MS = 60 * 60 * 1000;
    private static final String CHANNEL = "care";
    private static final int NOTIFICATION_ID = 4;
    static final String REPLY_KEY = "care_reply";

    static final String USAGE =
            "Check-ins: the agent writes to the user first, like a friend who asks how things are.\n\n"
                    + "  phone care                       what is planned\n"
                    + "  phone care in N[m|h|d] TOPIC     come back to something later, e.g. phone care in 20h \"how the exam went\"\n"
                    + "  phone care at HH:MM TOPIC        the same at the next time the clock shows HH:MM\n"
                    + "  phone care cancel ID             drop a planned one\n"
                    + "  phone care now [TOPIC]           write to the user right now\n"
                    + "  phone care times HH:MM ...       the daily times (up to " + MAX_TIMES + "); phone care times none\n"
                    + "  phone care on | off              only when the user asks for it\n\n"
                    + "At most " + MAX_PER_DAY + " check-ins a day; one planned more than an hour ahead that falls between "
                    + NIGHT_FROM + ":00 and 0" + NIGHT_TO + ":00 waits for the morning.\n";

    private final TermService service;
    private final File store;
    final List<FollowUp> followUps = new ArrayList<>();
    private int nextId = 1;
    /** The check-in the agent is writing right now, or null. */
    private Chat.Message asking;
    /** The user answered from the notification and the agent's reply to that is awaited. */
    private boolean answering;
    /** The message that is in the notification now, or null. */
    private Chat.Message shown;
    private PowerManager.WakeLock awake;

    Care(TermService service) {
        this.service = service;
        store = new File(service.getFilesDir(), "care.json");
        load();
    }

    // ------------------------------------------------------------ settings

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("termant", Context.MODE_PRIVATE);
    }

    static boolean on(Context c) {
        return prefs(c).getBoolean("care_on", true);
    }

    /** The daily times as "09:00 21:00"; empty when there are none. */
    static String timesText(Context c) {
        return prefs(c).getString("care_times", DEFAULT_TIMES);
    }

    static List<int[]> times(Context c) {
        List<int[]> all = new ArrayList<>();
        for (String word : timesText(c).trim().split("[\\s,]+")) {
            int[] t = word.isEmpty() ? null : PhoneBridge.parseTime(word);
            if (t != null && all.size() < MAX_TIMES) all.add(t);
        }
        return all;
    }

    /** Takes "9:00, 21:30", "8am 1pm 9pm" or "" (no daily times). Returns what was understood, or null. */
    static String normalize(String typed) {
        StringBuilder clean = new StringBuilder();
        int count = 0;
        String text = typed.trim().replaceAll("(?i)\\s+(am|pm)\\b", "$1");
        if (text.equalsIgnoreCase("none") || text.isEmpty()) return "";
        for (String word : text.split("[\\s,;]+")) {
            int[] t = PhoneBridge.parseTime(word);
            if (t == null || ++count > MAX_TIMES) return null;
            String shown = String.format(Locale.US, "%02d:%02d", t[0], t[1]);
            if ((" " + clean + " ").contains(" " + shown + " ")) continue;
            clean.append(clean.length() == 0 ? "" : " ").append(shown);
        }
        return clean.toString();
    }

    void setOn(boolean on) {
        prefs(service).edit().putBoolean("care_on", on).commit();
        android.util.Log.i("Termant", "care: turned " + (on ? "on" : "off"));
        schedule();
    }

    boolean setTimes(String typed) {
        String clean = normalize(typed);
        if (clean == null) return false;
        prefs(service).edit().putString("care_times", clean).commit();
        android.util.Log.i("Termant", "care: daily times: " + (clean.isEmpty() ? "none" : clean));
        schedule();
        return true;
    }

    // ------------------------------------------------------------ alarms

    void start() {
        schedule();
    }

    private PendingIntent alarm(String what) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Intent i = new Intent(service, TermService.class)
                .setAction(TermService.ACTION_CARE)
                .setData(Uri.parse("termant://care/" + what));
        return Build.VERSION.SDK_INT >= 26
                ? PendingIntent.getForegroundService(service, 0, i, flags)
                : PendingIntent.getService(service, 0, i, flags);
    }

    /** Sets every alarm again from what is stored; safe to call at any time. */
    private void schedule() {
        AlarmManager am = (AlarmManager) service.getSystemService(Context.ALARM_SERVICE);
        boolean on = on(service);
        List<int[]> times = times(service);
        for (int slot = 0; slot < MAX_TIMES; slot++) {
            PendingIntent fire = alarm("daily/" + slot);
            am.cancel(fire);
            if (!on || slot >= times.size()) continue;
            Calendar when = Calendar.getInstance();
            when.set(Calendar.HOUR_OF_DAY, times.get(slot)[0]);
            when.set(Calendar.MINUTE, times.get(slot)[1]);
            when.set(Calendar.SECOND, 0);
            when.set(Calendar.MILLISECOND, 0);
            // a little slack, so a time that has just come is not set for today again
            if (when.getTimeInMillis() <= System.currentTimeMillis() + 5000) when.add(Calendar.DAY_OF_YEAR, 1);
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when.getTimeInMillis(), fire);
        }
        for (FollowUp f : followUps) {
            PendingIntent fire = alarm("follow/" + f.id);
            am.cancel(fire);
            if (on) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, Math.max(f.when, System.currentTimeMillis() + 1000), fire);
        }
    }

    private void cancel(FollowUp f) {
        ((AlarmManager) service.getSystemService(Context.ALARM_SERVICE)).cancel(alarm("follow/" + f.id));
    }

    /** One of this class's alarms went off. */
    void onAlarm(Uri what) {
        List<String> path = what.getPathSegments();
        if (path.size() != 2) return;
        if (path.get(0).equals("daily")) {
            schedule(); // tomorrow
            if (on(service)) attempt(null, 0);
            return;
        }
        FollowUp due = null;
        for (FollowUp f : followUps) if (String.valueOf(f.id).equals(path.get(1))) due = f;
        if (due == null) return;
        followUps.remove(due);
        save();
        if (on(service)) attempt(due.topic, due.tries);
    }

    /** A check-in whose time has come: now if that suits, a little later if not, at some point not at all. */
    private void attempt(String topic, int tries) {
        long now = System.currentTimeMillis();
        String wait = !Ubuntu.isInstalled(service) ? "Ubuntu is not set up"
                : asking != null || service.chat.isBusy() ? "the agent is busy"
                : now - Chat.lastWrote(service) < JUST_WROTE_MS ? "the user has just written"
                : now - prefs(service).getLong("care_last", 0) < MIN_GAP_MS ? "the last check-in was a moment ago"
                : null;
        if (wait == null && countToday() >= MAX_PER_DAY) {
            android.util.Log.i("Termant", "care: skipped, " + MAX_PER_DAY + " check-ins today already");
            return;
        }
        if (wait == null && topic == null && unanswered() >= MAX_UNANSWERED) {
            // someone who does not answer is left in peace until they write again
            android.util.Log.i("Termant", "care: skipped, the last " + MAX_UNANSWERED + " went unanswered");
            return;
        }
        if (wait == null) {
            begin(topic);
            return;
        }
        // a plain daily check-in is not worth chasing for long; something the agent planned is
        if (tries >= (topic == null ? MAX_TRIES : MAX_TRIES_PLANNED) || !Ubuntu.isInstalled(service)) {
            android.util.Log.i("Termant", "care: skipped, " + wait);
            return;
        }
        FollowUp later = new FollowUp();
        later.id = nextId++;
        later.when = now + WAIT_MS;
        later.topic = topic;
        later.tries = tries + 1;
        followUps.add(later);
        save();
        schedule();
        android.util.Log.i("Termant", "care: waits 10 minutes, " + wait);
    }

    /** How many check-ins in a row stand at the end of the chat with no word from the user. */
    private int unanswered() {
        List<Chat.Message> all = service.chat.messages;
        int n = 0;
        for (int i = all.size() - 1; i >= 0 && all.get(i).first; i--) n++;
        return n;
    }

    private int countToday() {
        String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        return today.equals(prefs(service).getString("care_day", "")) ? prefs(service).getInt("care_count", 0) : 0;
    }

    // ------------------------------------------------------------ asking

    /** Lets the agent write to the user now. False if it is busy with something. */
    private boolean begin(String topic) {
        if (asking != null || service.chat.isBusy()) return false;
        asking = service.chat.checkIn(prompt(topic));
        if (asking == null) return false;
        PowerManager pm = (PowerManager) service.getSystemService(Context.POWER_SERVICE);
        release();
        awake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "termant:care");
        awake.acquire(5 * 60 * 1000); // an alarm wakes the phone only for a moment; the model needs longer
        String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        prefs(service).edit().putString("care_day", today).putInt("care_count", countToday() + 1)
                .putLong("care_last", System.currentTimeMillis()).apply();
        android.util.Log.i("Termant", "care: check-in started" + (topic == null ? "" : " about: " + topic));
        return true;
    }

    private void release() {
        if (awake != null && awake.isHeld()) awake.release();
        awake = null;
    }

    /** What the agent is told; the user never sees this. */
    private String prompt(String topic) {
        StringBuilder facts = new StringBuilder();
        facts.append("- Now: ").append(new SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.US).format(new Date())).append('\n');
        Intent battery = service.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            boolean plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            if (level >= 0 && scale > 0) {
                facts.append("- Battery: ").append(level * 100 / scale).append("%, ")
                        .append(plugged ? "charging" : "not charging").append('\n');
            }
        }
        long wrote = Chat.lastWrote(service);
        if (wrote > 0) {
            long minutes = Math.max(0, System.currentTimeMillis() - wrote) / 60000;
            facts.append("- The user last wrote to you ").append(minutes < 90 ? minutes + " minutes"
                    : minutes < 48 * 60 ? minutes / 60 + " hours" : minutes / (60 * 24) + " days").append(" ago\n");
        } else {
            facts.append("- The user has not written to you yet\n");
        }
        facts.append("- Language of the app: ").append(Lang.HINDI.equals(Lang.current(service))
                ? "Hindi written in Latin letters (Hinglish)" : "English").append('\n');
        return "[Written by the Termant app, not by the user. The user does not see this text.]\n\n"
                + "You look after the user the way a caring friend does, and this time you speak first: "
                + "the user has not asked anything. "
                + (topic == null
                ? "It is one of the times of day at which you check in on them."
                : "You planned to come back to this: \"" + topic + "\".")
                + "\n\n" + facts + "\n"
                + "Write one short, warm message of at most three sentences that ends with ONE question for the user.\n"
                + "- Make it personal. Use what you know about them from your notes and from this conversation: "
                + "how something they mentioned went, a plan, their health, their work, the people they talked about. "
                + (topic == null ? "If you know nothing yet, ask how they are and one thing that helps you get to know them.\n"
                : "Ask about the thing you planned to come back to.\n")
                + "- Fit the time of day (morning, afternoon, evening, night). Mention the battery only if it is below 20% and not charging.\n"
                + "- Do not ask something you already asked in this conversation, and do not list what you can do.\n"
                + "- Write in the language the user writes to you in; if you cannot tell, use the app's language.\n"
                + "- Your message itself is what the user gets as a notification: do not run any command now, "
                + "do not use phone ask or phone notify, and change nothing on the phone.\n\n"
                + "When the user answers: reply like a friend, keep what is worth knowing with learn note, "
                + "and if something deserves a later question, plan it with phone care in (see your guide).";
    }

    /** The chat changed, or Termant came to the screen or left it. Main thread. */
    void sync() {
        NotificationManager nm = service.getSystemService(NotificationManager.class);
        List<Chat.Message> all = service.chat.messages;
        Chat.Message last = all.isEmpty() ? null : all.get(all.size() - 1);
        boolean onScreen = MainActivity.isOnScreen();
        if ((asking != null || answering) && !service.chat.isBusy()) {
            Chat.Message asked = asking;
            asking = null;
            answering = false;
            release();
            // a check-in that failed or was stopped is no longer in the chat
            boolean good = last != null && !last.user && !last.failed && !last.text.trim().isEmpty()
                    && (asked == null || last == asked);
            if (asked != null) {
                android.util.Log.i("Termant", good ? "care: asked: " + oneLine(last.text) : "care: nothing was asked");
            }
            if (good && !onScreen) {
                show(last);
                return;
            }
        }
        if (shown != null && !answering && (onScreen || last != shown)) {
            // it is in front of the user in the chat, or the conversation has moved on (answered from the card)
            nm.cancel(NOTIFICATION_ID);
            shown = null;
        }
    }

    private static String oneLine(String text) {
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() > 300 ? t.substring(0, 300) : t;
    }

    // ------------------------------------------------------------ notification

    private Notification.Builder builder(String text) {
        Context words = Lang.of(service);
        NotificationManager nm = service.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, words.getString(R.string.care_channel), NotificationManager.IMPORTANCE_DEFAULT));
        }
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        // Android writes the typed answer into this one, so it must stay changeable
        int mutable = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? 1 << 25 : 0);
        PendingIntent open = PendingIntent.getActivity(service, 4,
                new Intent(service, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), immutable);
        PendingIntent reply = PendingIntent.getService(service, 5,
                new Intent(service, TermService.class).setAction(TermService.ACTION_CARE_REPLY), mutable);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(service, CHANNEL) : new Notification.Builder(service);
        b.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Termant")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .addAction(new Notification.Action.Builder(null, words.getString(R.string.care_reply), reply)
                        .addRemoteInput(new RemoteInput.Builder(REPLY_KEY)
                                .setLabel(words.getString(R.string.care_reply_hint)).build())
                        .build());
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_DEFAULT).setDefaults(Notification.DEFAULT_ALL);
        return b;
    }

    private void show(Chat.Message message) {
        try {
            shown = message;
            service.getSystemService(NotificationManager.class)
                    .notify(NOTIFICATION_ID, builder(message.text.trim()).build());
            android.util.Log.i("Termant", "care: shown as a notification");
        } catch (Exception e) {
            android.util.Log.e("Termant", "care: cannot notify", e);
        }
    }

    /** The user typed an answer into the notification. */
    void onReply(Intent intent) {
        Bundle typed = RemoteInput.getResultsFromIntent(intent);
        CharSequence answer = typed == null ? null : typed.getCharSequence(REPLY_KEY);
        NotificationManager nm = service.getSystemService(NotificationManager.class);
        if (answer == null || answer.toString().trim().isEmpty() || service.chat.isBusy()) {
            // nothing to send, or the agent is in the middle of something else: the chat is the place
            nm.cancel(NOTIFICATION_ID);
            shown = null;
            return;
        }
        String text = answer.toString().trim();
        android.util.Log.i("Termant", "care: answered from the notification");
        try {
            // shows the answer under the question and ends Android's "sending" state
            Notification.Builder b = builder(shown == null ? "" : shown.text.trim());
            b.setRemoteInputHistory(new CharSequence[]{text});
            b.setOnlyAlertOnce(true);
            nm.notify(NOTIFICATION_ID, b.build());
        } catch (Exception e) {
            nm.cancel(NOTIFICATION_ID);
        }
        answering = true;
        service.chat.send(text);
    }

    // ------------------------------------------------------------ the command

    private static final Pattern DELAY = Pattern.compile("(?i)^(\\d+)\\s*(m|min|h|d)$");

    /** "phone care ...", for the agent and for the terminal. Main thread. */
    String command(List<String> args) throws PhoneBridge.Refused {
        String what = args.size() > 1 ? args.get(1) : "list";
        switch (what) {
            case "help":
            case "--help":
                return USAGE;
            case "list":
            case "status":
                return describe();
            case "on":
            case "off":
                setOn(what.equals("on"));
                return what.equals("on") ? "Check-ins are on.\n" + describe() : "Check-ins are off: the agent will not write first.";
            case "times": {
                String typed = rest(args, 2);
                if (typed.isEmpty()) throw new PhoneBridge.Refused("usage: phone care times HH:MM [HH:MM ...]   or: phone care times none");
                if (!setTimes(typed)) {
                    throw new PhoneBridge.Refused("phone care times: up to " + MAX_TIMES + " times of day such as 09:00 21:30, or none");
                }
                return describe();
            }
            case "now": {
                if (!Ubuntu.isInstalled(service)) throw new PhoneBridge.Refused("Ubuntu is not set up yet.");
                if (!on(service)) {
                    throw new PhoneBridge.Refused("Check-ins are turned off (Termant \u2192 Settings). Turn them on only if the user asks: phone care on");
                }
                if (countToday() >= MAX_PER_DAY) {
                    throw new PhoneBridge.Refused("That is enough for today: " + MAX_PER_DAY + " check-ins already.");
                }
                String topic = topic(rest(args, 2), false);
                if (!begin(topic)) throw new PhoneBridge.Refused("The agent is busy right now; try again when it is done.");
                return "The agent is writing to the user now.";
            }
            case "in":
            case "at": {
                if (!on(service)) {
                    throw new PhoneBridge.Refused("Check-ins are turned off (Termant \u2192 Settings). Turn them on only if the user asks: phone care on");
                }
                long when;
                long now = System.currentTimeMillis();
                String spec = args.size() > 2 ? args.get(2) : "";
                if (what.equals("in")) {
                    Matcher m = DELAY.matcher(spec);
                    if (!m.matches()) throw new PhoneBridge.Refused("usage: phone care in N[m|h|d] TOPIC   (for example: phone care in 20h \"how the exam went\")");
                    long n = Long.parseLong(m.group(1));
                    String unit = m.group(2).toLowerCase(Locale.US);
                    long minutes = unit.equals("d") ? n * 24 * 60 : unit.equals("h") ? n * 60 : n;
                    if (minutes < 1 || minutes > 30L * 24 * 60) throw new PhoneBridge.Refused("phone care in: between 1 minute and 30 days");
                    when = now + minutes * 60000;
                } else {
                    int[] t = PhoneBridge.parseTime(spec);
                    if (t == null) throw new PhoneBridge.Refused("usage: phone care at HH:MM TOPIC   (for example: phone care at 18:30 \"how the interview went\")");
                    Calendar c = Calendar.getInstance();
                    c.set(Calendar.HOUR_OF_DAY, t[0]);
                    c.set(Calendar.MINUTE, t[1]);
                    c.set(Calendar.SECOND, 0);
                    c.set(Calendar.MILLISECOND, 0);
                    if (c.getTimeInMillis() <= now + 30000) c.add(Calendar.DAY_OF_YEAR, 1);
                    when = c.getTimeInMillis();
                }
                String topic = topic(rest(args, 3), true);
                int planned = 0;
                for (FollowUp f : followUps) {
                    if (f.topic == null) continue;
                    planned++;
                    if (f.topic.equalsIgnoreCase(topic)) throw new PhoneBridge.Refused("Already planned: " + line(f));
                }
                if (planned >= MAX_FOLLOW_UPS) {
                    throw new PhoneBridge.Refused("There are " + MAX_FOLLOW_UPS + " planned already (phone care). Cancel one first: phone care cancel ID");
                }
                long asked = when;
                // within the hour the user is still up; anything later must not wake them
                if (when - now > SOON_MS) when = outOfTheNight(when);
                FollowUp f = new FollowUp();
                f.id = nextId++;
                f.when = when;
                f.topic = topic;
                followUps.add(f);
                save();
                schedule();
                android.util.Log.i("Termant", "care: planned for " + new Date(when) + ": " + topic);
                return "Planned: " + line(f) + (when != asked ? "\n(moved to the morning: nobody is woken for this)" : "")
                        + "\nYou will be started then to write to the user. Do not tell the user a reminder is set; just do it.";
            }
            case "cancel":
            case "remove": {
                String id = args.size() > 2 ? args.get(2) : "";
                for (FollowUp f : new ArrayList<>(followUps)) {
                    if (!String.valueOf(f.id).equals(id)) continue;
                    remove(f);
                    return "Dropped: " + line(f);
                }
                throw new PhoneBridge.Refused("phone care cancel: no planned check-in with the ID '" + id + "'. See: phone care");
            }
            default:
                throw new PhoneBridge.Refused("phone care: unknown '" + what + "'.\n\n" + USAGE);
        }
    }

    void remove(FollowUp f) {
        cancel(f);
        followUps.remove(f);
        save();
        android.util.Log.i("Termant", "care: dropped: " + f.topic);
    }

    /** Starts a check-in from Settings. False if the agent is busy. */
    boolean now() {
        return Ubuntu.isInstalled(service) && begin(null);
    }

    private static String topic(String typed, boolean needed) throws PhoneBridge.Refused {
        String t = typed.replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) {
            if (needed) throw new PhoneBridge.Refused("Say what to come back to, e.g. phone care in 20h \"how the exam went\"");
            return null;
        }
        if (t.length() > MAX_TOPIC) throw new PhoneBridge.Refused("The topic is too long (at most " + MAX_TOPIC + " letters).");
        return t;
    }

    private static String rest(List<String> args, int from) {
        StringBuilder b = new StringBuilder();
        for (int i = from; i < args.size(); i++) b.append(i == from ? "" : " ").append(args.get(i));
        return b.toString().trim();
    }

    static long outOfTheNight(long when) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(when);
        int hour = c.get(Calendar.HOUR_OF_DAY);
        if (hour < NIGHT_FROM && hour >= NIGHT_TO) return when;
        if (hour >= NIGHT_FROM) c.add(Calendar.DAY_OF_YEAR, 1);
        c.set(Calendar.HOUR_OF_DAY, NIGHT_TO);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static String when(long at) {
        return new SimpleDateFormat("EEE d MMM HH:mm", Locale.US).format(new Date(at));
    }

    private static String line(FollowUp f) {
        return f.id + "  " + when(f.when) + "  " + f.topic;
    }

    /** The follow-ups the agent planned, soonest first (not the ones that merely wait a few minutes). */
    List<FollowUp> planned() {
        List<FollowUp> all = new ArrayList<>();
        for (FollowUp f : followUps) if (f.topic != null) all.add(f);
        java.util.Collections.sort(all, new java.util.Comparator<FollowUp>() {
            @Override
            public int compare(FollowUp a, FollowUp b) {
                return Long.compare(a.when, b.when);
            }
        });
        return all;
    }

    private String describe() {
        StringBuilder b = new StringBuilder();
        boolean on = on(service);
        String times = timesText(service).trim();
        b.append("Check-ins: ").append(on ? "on" : "off (turned off by the user)").append('\n');
        b.append("Daily at: ").append(times.isEmpty() ? "no fixed times" : times).append('\n');
        b.append("Today so far: ").append(countToday()).append(" of at most ").append(MAX_PER_DAY).append('\n');
        List<FollowUp> planned = planned();
        if (planned.isEmpty()) {
            b.append("Nothing planned to come back to.");
        } else {
            b.append("Planned (ID, when, about):");
            for (FollowUp f : planned) b.append("\n  ").append(line(f));
        }
        return b.toString();
    }

    // ------------------------------------------------------------ storage

    private void save() {
        try {
            JSONArray list = new JSONArray();
            for (FollowUp f : followUps) {
                JSONObject o = new JSONObject().put("id", f.id).put("when", f.when).put("tries", f.tries);
                if (f.topic != null) o.put("topic", f.topic);
                list.put(o);
            }
            Installer.write(store, new JSONObject().put("next", nextId).put("followUps", list).toString());
        } catch (Exception e) {
            android.util.Log.e("Termant", "care: cannot save", e);
        }
    }

    private void load() {
        if (!store.isFile()) return;
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = new java.io.FileInputStream(store)) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bytes.write(buf, 0, n);
            }
            JSONObject o = new JSONObject(new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
            nextId = Math.max(1, o.optInt("next", 1));
            JSONArray list = o.optJSONArray("followUps");
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject j = list.getJSONObject(i);
                FollowUp f = new FollowUp();
                f.id = j.getInt("id");
                f.when = j.getLong("when");
                f.tries = j.optInt("tries");
                f.topic = j.has("topic") ? j.getString("topic") : null;
                followUps.add(f);
                nextId = Math.max(nextId, f.id + 1);
            }
        } catch (Exception e) {
            android.util.Log.e("Termant", "care: cannot read what was planned", e);
        }
    }
}
