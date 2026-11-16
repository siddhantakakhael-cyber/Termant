package com.termant.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Screen control ("Accessibility control"): lets the `phone` command, and
 * through it the AI agent, read what is on the screen and tap, type and
 * swipe in any app, the way a person would.
 *
 * Android keeps this off until the user turns it on by hand in Settings →
 * Accessibility. It does nothing by itself: it only acts when a
 * `phone screen|tap|type|swipe|key` command arrives. It refuses to type
 * into password boxes, and Android hides what is typed there.
 */
public final class ScreenService extends AccessibilityService {
    private static volatile ScreenService instance;

    /** The running service, or null while screen control is off. */
    static ScreenService get() {
        return instance;
    }

    /** Whether the user has turned screen control on in Android's settings. */
    static boolean isOn(Context c) {
        if (instance != null) return true;
        String enabled = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        String mine = new ComponentName(c, ScreenService.class).flattenToString();
        String mineShort = new ComponentName(c, ScreenService.class).flattenToShortString();
        for (String one : enabled.split(":")) {
            if (one.equalsIgnoreCase(mine) || one.equalsIgnoreCase(mineShort)) return true;
        }
        return false;
    }

    static final String OFF = "Screen control is off. Ask the user to turn it on: "
            + "Termant \u2192 Settings \u2192 Screen control. Do not try another way.";

    @Override
    protected void onServiceConnected() {
        instance = this;
        android.util.Log.i("Termant", "screen control: on");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        android.util.Log.i("Termant", "screen control: off");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // nothing is watched or recorded; the screen is read only when asked
    }

    @Override
    public void onInterrupt() {
    }

    /** Turns screen control off again (the user can turn it back on in Android's settings). */
    void turnOff() {
        if (Build.VERSION.SDK_INT >= 24) disableSelf();
    }

    // ------------------------------------------------------------ reading

    /** The items of the last description, so that "tap 7" finds item 7 again. */
    private final List<Rect> items = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();

    private static String str(CharSequence s) {
        return s == null ? "" : s.toString().replaceAll("\\s+", " ").trim();
    }

    private static String shorten(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "\u2026" : s;
    }

    private Point size() {
        Point p = new Point();
        ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealSize(p);
        return p;
    }

    private String appName(CharSequence pkg) {
        if (pkg == null) return "?";
        try {
            PackageManager pm = getPackageManager();
            return str(pm.getApplicationLabel(pm.getApplicationInfo(pkg.toString(), 0)));
        } catch (Exception e) {
            return pkg.toString();
        }
    }

    /**
     * Android hands out what it remembers of a screen, and it only forgets
     * when it is told about changes, which this service does not listen to
     * (it watches nothing). So ask it to forget before every look, or a page
     * that was scrolled would be described as it was before.
     */
    private void forget() {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                clearCache();
            } else {
                android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
                if (info != null) setServiceInfo(info); // sending the settings again empties the memory too
            }
        } catch (Exception e) {
            // then the look may be a little old
        }
    }

    /** The window in front: the active one, else (a dialog of another app, say) the front-most app window. */
    private AccessibilityNodeInfo front() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) return root;
        try {
            AccessibilityNodeInfo other = null;
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) { // front to back
                if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                if (w.isActive() || w.isFocused()) return r;
                if (other == null) other = r;
            }
            return other;
        } catch (Exception e) {
            return null;
        }
    }

    /** The same, waiting a moment when a screen is just changing over. */
    private AccessibilityNodeInfo root() {
        forget();
        for (int i = 0; i < 6; i++) {
            AccessibilityNodeInfo root = front();
            if (root != null) return root;
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * What is on the screen, one numbered line per thing that can be read
     * or used: its kind, its words and where its middle is.
     */
    synchronized String describe(int limit) {
        AccessibilityNodeInfo root = root();
        items.clear();
        labels.clear();
        if (root == null) {
            return "Nothing can be read from the screen right now (it may be off, locked, or showing something protected).";
        }
        StringBuilder out = new StringBuilder();
        Point size = size();
        CharSequence pkg = root.getPackageName();
        out.append("App: ").append(appName(pkg)).append(" (").append(pkg).append("), screen ")
                .append(size.x).append('x').append(size.y).append('\n');
        walk(root, out, limit, 0);
        if (items.isEmpty()) out.append("(nothing readable here; try: phone swipe up, or phone key back)\n");
        else if (items.size() >= limit) out.append("(more items not listed; swipe to see further)\n");
        return out.toString();
    }

    private void walk(AccessibilityNodeInfo n, StringBuilder out, int limit, int depth) {
        if (n == null || depth > 50 || items.size() >= limit || !n.isVisibleToUser()) return;
        String text = n.isPassword() ? "(hidden)" : str(n.getText());
        String desc = str(n.getContentDescription());
        String label = !text.isEmpty() ? text : desc;
        boolean leafButton = n.isClickable() && n.getChildCount() == 0;
        if (!label.isEmpty() || n.isEditable() || n.isCheckable() || leafButton) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (!r.isEmpty()) {
                items.add(r);
                labels.add(label);
                String kind = n.isEditable() ? "input" : n.isCheckable() ? (n.isChecked() ? "switch, on" : "switch, off")
                        : n.isClickable() ? "button" : "text";
                out.append(items.size()).append(". [").append(kind).append("] ");
                out.append(label.isEmpty() ? "(no label)" : '"' + shorten(label, 140) + '"');
                if (!text.isEmpty() && !desc.isEmpty() && !desc.equals(text)) out.append(" (").append(shorten(desc, 60)).append(')');
                if (n.isEditable() && n.isFocused()) out.append(" (typing goes here)");
                if (!n.isEnabled()) out.append(" (greyed out)");
                out.append(" @").append(r.centerX()).append(',').append(r.centerY()).append('\n');
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), out, limit, depth + 1);
    }

    // ------------------------------------------------------------ acting

    private boolean gesture(Path path, long ms) throws InterruptedException {
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, ms)).build();
        final CountDownLatch done = new CountDownLatch(1);
        final boolean[] ok = {false};
        boolean sent = dispatchGesture(g, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                ok[0] = true;
                done.countDown();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                done.countDown();
            }
        }, null);
        if (!sent) return false;
        done.await(ms + 3000, TimeUnit.MILLISECONDS);
        return ok[0];
    }

    private String tapAt(int x, int y, String what) throws Exception {
        Point size = size();
        if (x < 0 || y < 0 || x >= size.x || y >= size.y) {
            throw new PhoneBridge.Refused("That point is outside the screen (" + size.x + "x" + size.y + ").");
        }
        Path p = new Path();
        p.moveTo(x, y);
        if (!gesture(p, 60)) throw new PhoneBridge.Refused("The tap did not go through. Look again with: phone screen");
        return "Tapped " + what + " at " + x + "," + y + ".";
    }

    /** Taps item N of the last description, the item with these words, or a point. */
    String tap(List<String> target) throws Exception {
        if (target.size() == 2 && target.get(0).matches("\\d+") && target.get(1).matches("\\d+")) {
            return tapAt(Integer.parseInt(target.get(0)), Integer.parseInt(target.get(1)), "the point");
        }
        StringBuilder joined = new StringBuilder();
        for (String t : target) joined.append(joined.length() == 0 ? "" : " ").append(t);
        String words = joined.toString().trim();
        if (words.isEmpty()) throw new PhoneBridge.Refused("usage: phone tap N | phone tap TEXT | phone tap X Y");
        Rect r;
        String what;
        synchronized (this) {
            if (words.matches("\\d+")) {
                int n = Integer.parseInt(words);
                if (items.isEmpty()) describe(200);
                if (n < 1 || n > items.size()) {
                    throw new PhoneBridge.Refused("There is no item " + n + ". Look first with: phone screen");
                }
                r = items.get(n - 1);
                what = "item " + n + (labels.get(n - 1).isEmpty() ? "" : " \"" + shorten(labels.get(n - 1), 40) + "\"");
            } else {
                describe(400);
                String want = words.toLowerCase(Locale.ROOT);
                int found = -1;
                for (int i = 0; i < labels.size() && found < 0; i++) {
                    if (labels.get(i).toLowerCase(Locale.ROOT).equals(want)) found = i;
                }
                for (int i = 0; i < labels.size() && found < 0; i++) {
                    if (labels.get(i).toLowerCase(Locale.ROOT).contains(want)) found = i;
                }
                if (found < 0) {
                    throw new PhoneBridge.Refused("Nothing on the screen says \"" + words
                            + "\". Look with: phone screen (it may need: phone swipe up)");
                }
                r = items.get(found);
                what = "\"" + shorten(labels.get(found), 40) + "\"";
            }
        }
        return tapAt(r.centerX(), r.centerY(), what);
    }

    private static AccessibilityNodeInfo firstEditable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 50) return null;
        if (n.isEditable() && n.isVisibleToUser()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo found = firstEditable(n.getChild(i), depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo inputBox() throws Exception {
        AccessibilityNodeInfo root = root();
        AccessibilityNodeInfo box = root == null ? null : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (box == null || !box.isEditable()) box = firstEditable(root, 0);
        if (box == null) throw new PhoneBridge.Refused("There is no text box on the screen. Tap one first.");
        return box;
    }

    /** Puts text into the box that has the cursor (or the only box there is). */
    String type(String text) throws Exception {
        if (text.isEmpty()) throw new PhoneBridge.Refused("usage: phone type TEXT");
        AccessibilityNodeInfo box = inputBox();
        if (box.isPassword()) {
            throw new PhoneBridge.Refused("This is a password box. Passwords, PINs and OTPs are for the user to type: "
                    + "ask them to do it and tell you when they are done.");
        }
        box.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        if (!box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            throw new PhoneBridge.Refused("This box did not take the text.");
        }
        return "Typed.";
    }

    /** up = the finger moves up, so the page moves on to what is below. */
    String swipe(String direction) throws Exception {
        Point s = size();
        float cx = s.x / 2f, cy = s.y / 2f, dx = s.x * 0.32f, dy = s.y * 0.26f;
        Path p = new Path();
        switch (direction) {
            case "up":
                p.moveTo(cx, cy + dy);
                p.lineTo(cx, cy - dy);
                break;
            case "down":
                p.moveTo(cx, cy - dy);
                p.lineTo(cx, cy + dy);
                break;
            case "left":
                p.moveTo(cx + dx, cy);
                p.lineTo(cx - dx, cy);
                break;
            case "right":
                p.moveTo(cx - dx, cy);
                p.lineTo(cx + dx, cy);
                break;
            default:
                throw new PhoneBridge.Refused("usage: phone swipe up|down|left|right  (up shows what is further below)");
        }
        if (!gesture(p, 320)) throw new PhoneBridge.Refused("The swipe did not go through.");
        return "Swiped " + direction + ".";
    }

    String key(String name) throws Exception {
        int action;
        switch (name) {
            case "back":
                action = GLOBAL_ACTION_BACK;
                break;
            case "home":
                action = GLOBAL_ACTION_HOME;
                break;
            case "recents":
                action = GLOBAL_ACTION_RECENTS;
                break;
            case "notifications":
                action = GLOBAL_ACTION_NOTIFICATIONS;
                break;
            case "enter": {
                if (Build.VERSION.SDK_INT < 30) {
                    throw new PhoneBridge.Refused("Enter needs Android 11 or newer. Tap the search or send button instead.");
                }
                if (!inputBox().performAction(android.R.id.accessibilityActionImeEnter)) {
                    throw new PhoneBridge.Refused("Enter did nothing here. Tap the search or send button instead.");
                }
                return "Pressed enter.";
            }
            default:
                throw new PhoneBridge.Refused("usage: phone key back|home|recents|notifications|enter");
        }
        if (!performGlobalAction(action)) throw new PhoneBridge.Refused("The phone did not accept '" + name + "'.");
        return "Pressed " + name + ".";
    }

    // ------------------------------------------------------------ apps

    private static List<ResolveInfo> launchable(Context c) {
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        return c.getPackageManager().queryIntentActivities(main, 0);
    }

    /** The apps that can be opened, one name per line. */
    static String apps(Context c) {
        PackageManager pm = c.getPackageManager();
        List<String> names = new ArrayList<>();
        for (ResolveInfo r : launchable(c)) names.add(str(r.loadLabel(pm)) + "  (" + r.activityInfo.packageName + ")");
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        StringBuilder out = new StringBuilder();
        for (String n : names) out.append(n).append('\n');
        return out.toString();
    }

    /** Waits until this app's window is the one in front (a first start takes a few seconds). */
    void waitForApp(String pkg, long ms) throws InterruptedException {
        long until = android.os.SystemClock.elapsedRealtime() + ms;
        while (android.os.SystemClock.elapsedRealtime() < until) {
            AccessibilityNodeInfo root = front();
            if (root != null && pkg.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) break;
            Thread.sleep(250);
        }
        Thread.sleep(700); // let it draw its first screen
    }

    /** Opens the app with this name (or package name). Returns what to say, and the app's package. */
    static String[] openApp(Context from, String name) throws Exception {
        if (name.isEmpty()) throw new PhoneBridge.Refused("usage: phone app NAME   (names: phone apps)");
        PackageManager pm = from.getPackageManager();
        String want = name.toLowerCase(Locale.ROOT);
        ResolveInfo found = null;
        List<ResolveInfo> all = launchable(from);
        for (ResolveInfo r : all) {
            if (str(r.loadLabel(pm)).toLowerCase(Locale.ROOT).equals(want) || r.activityInfo.packageName.equals(name)) {
                found = r;
                break;
            }
        }
        if (found == null) {
            for (ResolveInfo r : all) {
                if (str(r.loadLabel(pm)).toLowerCase(Locale.ROOT).contains(want)) {
                    found = r;
                    break;
                }
            }
        }
        if (found == null) throw new PhoneBridge.Refused("No app called \"" + name + "\" is installed. See: phone apps");
        Intent open = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(found.activityInfo.packageName, found.activityInfo.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        from.startActivity(open);
        return new String[]{"Opened " + str(found.loadLabel(pm)) + ".", found.activityInfo.packageName};
    }
}
