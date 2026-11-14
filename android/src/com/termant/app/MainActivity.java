package com.termant.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

/** The screen: first-run setup, then the terminal with session tabs and an extra-keys row. */
public class MainActivity extends Activity
        implements ServiceConnection, TermService.Screen, Installer.Listener, TerminalViewClient {

    private static final int REQUEST_STORAGE = 1;
    private static final int BAR = 0xff1d1d1d;
    private static final int KEY_TEXT = 0xffdddddd;
    private static final int ACCENT = 0xffe95420;

    private SharedPreferences prefs;
    private TermService service;
    private TerminalSession current;

    private View setupPanel;
    private TextView setupStatus, setupError;
    private ProgressBar setupBar;
    private Button retryButton;

    private View terminalPanel;
    private TerminalView terminal;
    private LinearLayout tabs;
    private TextView ctrlKey, altKey;
    private boolean ctrl, alt;
    private int fontSize, minFont, maxFont;

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("termant", MODE_PRIVATE);

        float density = getResources().getDisplayMetrics().density;
        minFont = (int) (5 * density);
        maxFont = (int) (40 * density);
        fontSize = Math.max(minFont, Math.min(maxFont, prefs.getInt("font", (int) (12 * density))));

        FrameLayout rootView = new FrameLayout(this);
        rootView.setFitsSystemWindows(true);
        terminalPanel = buildTerminalPanel();
        setupPanel = buildSetupPanel();
        rootView.addView(terminalPanel, match());
        rootView.addView(setupPanel, match());
        terminalPanel.setVisibility(View.GONE);
        setupPanel.setVisibility(View.GONE);
        setContentView(rootView);
        applyKeepScreenOn();

        Intent intent = new Intent(this, TermService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        if (!bindService(intent, this, 0)) {
            throw new IllegalStateException("Cannot bind terminal service");
        }
    }

    @Override
    protected void onDestroy() {
        Installer.detach(this);
        if (service != null) {
            service.setScreen(null);
            service = null;
        }
        try {
            unbindService(this);
        } catch (IllegalArgumentException e) {
            // not bound
        }
        super.onDestroy();
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((TermService.LocalBinder) binder).service();
        service.setScreen(this);
        if (Ubuntu.isInstalled(this) && !Installer.isRunning()) {
            showTerminal();
        } else {
            startInstall();
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
        finish();
    }

    @Override
    public void onBackPressed() {
        // Leave without ending the sessions; the notification brings them back.
        moveTaskToBack(true);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    // ------------------------------------------------------------ first-run setup

    private View buildSetupPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(28), dp(96), dp(28), dp(28));
        box.setBackgroundColor(0xff300a24);

        TextView title = new TextView(this);
        title.setText(R.string.setup_title);
        title.setTextSize(24);
        title.setTextColor(0xffffffff);
        box.addView(title);

        setupStatus = new TextView(this);
        setupStatus.setTextSize(15);
        setupStatus.setTextColor(0xffeadfe6);
        setupStatus.setPadding(0, dp(24), 0, dp(10));
        box.addView(setupStatus);

        setupBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        setupBar.setMax(100);
        box.addView(setupBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView note = new TextView(this);
        note.setText(R.string.setup_note);
        note.setTextSize(13);
        note.setTextColor(0xffc9b3c2);
        note.setPadding(0, dp(20), 0, 0);
        box.addView(note);

        setupError = new TextView(this);
        setupError.setTextSize(14);
        setupError.setTextColor(0xffffb4a1);
        setupError.setPadding(0, dp(20), 0, dp(12));
        setupError.setTextIsSelectable(true);
        setupError.setVisibility(View.GONE);
        box.addView(setupError);

        retryButton = new Button(this);
        retryButton.setText(R.string.retry);
        retryButton.setVisibility(View.GONE);
        retryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startInstall();
            }
        });
        box.addView(retryButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xff300a24);
        scroll.addView(box);
        return scroll;
    }

    private void startInstall() {
        terminalPanel.setVisibility(View.GONE);
        setupPanel.setVisibility(View.VISIBLE);
        setupError.setVisibility(View.GONE);
        retryButton.setVisibility(View.GONE);
        setupBar.setVisibility(View.VISIBLE);
        setupBar.setIndeterminate(true);
        setupStatus.setText(R.string.step_find);
        hideKeyboard();
        Installer.start(this, this);
    }

    @Override
    public void onInstallProgress(String status, int percent) {
        setupStatus.setText(status);
        setupBar.setIndeterminate(percent < 0);
        if (percent >= 0) setupBar.setProgress(percent);
    }

    @Override
    public void onInstallDone() {
        if (service != null) showTerminal();
    }

    @Override
    public void onInstallError(String message) {
        setupStatus.setText(R.string.setup_failed);
        setupBar.setVisibility(View.GONE);
        setupError.setText(message);
        setupError.setVisibility(View.VISIBLE);
        retryButton.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------ terminal screen

    private View buildTerminalPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xff000000);

        // top bar: session tabs, new session, menu
        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(BAR);
        tabs = new LinearLayout(this);
        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.addView(tabs);
        bar.addView(tabScroll, new LinearLayout.LayoutParams(0, dp(40), 1));

        TextView plus = barButton("+", getString(R.string.menu_new));
        plus.setTextSize(22);
        plus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newSession();
            }
        });
        bar.addView(plus, new LinearLayout.LayoutParams(dp(48), dp(40)));

        final TextView more = barButton("\u22ee", "Menu");
        more.setTextSize(20);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(more);
            }
        });
        bar.addView(more, new LinearLayout.LayoutParams(dp(48), dp(40)));
        panel.addView(bar);

        terminal = new TerminalView(this, null);
        terminal.setTerminalViewClient(this);
        terminal.setTextSize(fontSize);
        terminal.setTypeface(Typeface.MONOSPACE);
        terminal.setFocusable(true);
        terminal.setFocusableInTouchMode(true);
        terminal.setKeepScreenOn(false);
        panel.addView(terminal, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        panel.addView(buildKeyRow(new Object[][]{
                {"ESC", KeyEvent.KEYCODE_ESCAPE}, {"/", "/"}, {"-", "-"},
                {"HOME", KeyEvent.KEYCODE_MOVE_HOME}, {"\u2191", KeyEvent.KEYCODE_DPAD_UP},
                {"END", KeyEvent.KEYCODE_MOVE_END}, {"PGUP", KeyEvent.KEYCODE_PAGE_UP}}));
        panel.addView(buildKeyRow(new Object[][]{
                {"TAB", KeyEvent.KEYCODE_TAB}, {"CTRL", "ctrl"}, {"ALT", "alt"},
                {"\u2190", KeyEvent.KEYCODE_DPAD_LEFT}, {"\u2193", KeyEvent.KEYCODE_DPAD_DOWN},
                {"\u2192", KeyEvent.KEYCODE_DPAD_RIGHT}, {"PGDN", KeyEvent.KEYCODE_PAGE_DOWN}}));
        return panel;
    }

    private TextView barButton(String label, String description) {
        TextView v = new TextView(this);
        v.setText(label);
        v.setContentDescription(description);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(KEY_TEXT);
        v.setClickable(true);
        v.setFocusable(false);
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        v.setBackgroundResource(ripple.resourceId);
        return v;
    }

    private View buildKeyRow(Object[][] keys) {
        LinearLayout row = new LinearLayout(this);
        row.setBackgroundColor(BAR);
        for (final Object[] key : keys) {
            final TextView v = barButton((String) key[0], (String) key[0]);
            v.setTextSize(13);
            v.setTypeface(Typeface.DEFAULT_BOLD);
            if ("ctrl".equals(key[1])) ctrlKey = v;
            if ("alt".equals(key[1])) altKey = v;
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                    onExtraKey(key[1]);
                }
            });
            row.addView(v, new LinearLayout.LayoutParams(0, dp(38), 1));
        }
        return row;
    }

    private void onExtraKey(Object key) {
        if ("ctrl".equals(key)) {
            ctrl = !ctrl;
            showModifiers();
            return;
        }
        if ("alt".equals(key)) {
            alt = !alt;
            showModifiers();
            return;
        }
        if (current == null || !current.isRunning()) return;
        if (key instanceof String) {
            boolean c = readControlKey(), a = readAltKey();
            terminal.inputCodePoint(((String) key).codePointAt(0), c, a);
        } else {
            int code = (Integer) key;
            // The view reads and clears the sticky CTRL/ALT state itself.
            terminal.onKeyDown(code, new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, 0));
            terminal.onKeyUp(code, new KeyEvent(0, 0, KeyEvent.ACTION_UP, code, 0, 0));
        }
    }

    private void showModifiers() {
        ctrlKey.setTextColor(ctrl ? ACCENT : KEY_TEXT);
        altKey.setTextColor(alt ? ACCENT : KEY_TEXT);
    }

    private void showTerminal() {
        setupPanel.setVisibility(View.GONE);
        terminalPanel.setVisibility(View.VISIBLE);
        try {
            Ubuntu.prepareHost(this);
        } catch (Exception e) {
            Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
        }
        if (service.sessions.isEmpty()) {
            service.createSession();
        }
        TerminalSession wanted = service.sessions.contains(current)
                ? current : service.sessions.get(service.sessions.size() - 1);
        select(wanted);
        terminal.requestFocus();
        showKeyboard();
    }

    private void select(TerminalSession session) {
        current = session;
        terminal.attachSession(session);
        terminal.onScreenUpdated();
        rebuildTabs();
    }

    private void newSession() {
        if (service == null) return;
        TerminalSession s = service.createSession();
        if (s == null) {
            Toast.makeText(this, R.string.max_sessions, Toast.LENGTH_SHORT).show();
            return;
        }
        select(s);
    }

    private void closeSession(TerminalSession session) {
        if (service == null || session == null) return;
        service.removeSession(session);
        if (service.sessions.isEmpty()) {
            service.exit();
            return;
        }
        if (session == current) select(service.sessions.get(service.sessions.size() - 1));
    }

    private void rebuildTabs() {
        tabs.removeAllViews();
        if (service == null) return;
        int i = 0;
        for (final TerminalSession s : service.sessions) {
            i++;
            String title = s.getTitle();
            String label = (title == null || title.isEmpty()) ? "Ubuntu " + i : i + ": " + title;
            if (label.length() > 22) label = label.substring(0, 21) + "\u2026";
            TextView tab = barButton(label, label);
            tab.setTextSize(13);
            tab.setPadding(dp(14), 0, dp(14), 0);
            tab.setSingleLine(true);
            if (s == current) {
                tab.setTextColor(0xffffffff);
                tab.setBackgroundColor(0xff000000);
            } else {
                tab.setTextColor(0xff9a9a9a);
            }
            tab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    select(s);
                }
            });
            tabs.addView(tab, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    // ------------------------------------------------------------ menu

    private void showMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        Menu m = popup.getMenu();
        m.add(0, 1, 0, R.string.menu_new);
        m.add(0, 2, 0, R.string.menu_close);
        m.add(0, 3, 0, R.string.menu_paste);
        m.add(0, 4, 0, R.string.menu_reset);
        m.add(0, 5, 0, R.string.menu_keep_on).setCheckable(true).setChecked(prefs.getBoolean("keep_on", false));
        m.add(0, 6, 0, R.string.menu_wake).setCheckable(true)
                .setChecked(service != null && service.hasWakeLock());
        if (!Ubuntu.hasStorageAccess(this)) m.add(0, 7, 0, R.string.menu_storage);
        m.add(0, 8, 0, R.string.menu_reinstall);
        m.add(0, 9, 0, R.string.menu_about);
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                onMenu(item.getItemId());
                return true;
            }
        });
        popup.show();
    }

    private void onMenu(int id) {
        switch (id) {
            case 1:
                newSession();
                break;
            case 2:
                closeSession(current);
                break;
            case 3:
                if (service != null && current != null) service.onPasteTextFromClipboard(current);
                break;
            case 4:
                if (current != null && current.getEmulator() != null) {
                    current.reset();
                    terminal.onScreenUpdated();
                }
                break;
            case 5:
                prefs.edit().putBoolean("keep_on", !prefs.getBoolean("keep_on", false)).apply();
                applyKeepScreenOn();
                break;
            case 6:
                if (service != null) service.setWakeLock(!service.hasWakeLock());
                break;
            case 7:
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
                break;
            case 8:
                confirmReinstall();
                break;
            case 9:
                showAbout();
                break;
            default:
                break;
        }
    }

    private void applyKeepScreenOn() {
        if (prefs.getBoolean("keep_on", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        if (request != REQUEST_STORAGE) return;
        boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        Toast.makeText(this, ok ? R.string.storage_granted : R.string.storage_denied, Toast.LENGTH_LONG).show();
    }

    private void confirmReinstall() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.reinstall_title)
                .setMessage(R.string.reinstall_text)
                .setPositiveButton(R.string.reinstall_confirm, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (service == null) return;
                        service.killAll();
                        current = null;
                        Ubuntu.marker(MainActivity.this).delete();
                        rebuildTabs();
                        startInstall();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showAbout() {
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            // keep empty
        }
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.about_text, version))
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private void showKeyboard() {
        terminal.post(new Runnable() {
            @Override
            public void run() {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.showSoftInput(terminal, 0);
            }
        });
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
    }

    // ------------------------------------------------------------ TermService.Screen

    @Override
    public void onSessionTextChanged(TerminalSession session) {
        if (session == current) terminal.onScreenUpdated();
    }

    @Override
    public void onSessionListChanged() {
        rebuildTabs();
    }

    @Override
    public void onSessionExited(TerminalSession session) {
        // A clean "exit" closes the tab; after a failure the message stays
        // until Enter is pressed, so it can be read.
        if (session.getExitStatus() == 0) closeSession(session);
        else if (session == current) terminal.onScreenUpdated();
    }

    @Override
    public void onExitRequested() {
        finish();
    }

    // ------------------------------------------------------------ TerminalViewClient

    @Override
    public float onScale(float scale) {
        if (scale < 0.9f || scale > 1.1f) {
            int step = Math.max(1, dp(1));
            fontSize = Math.max(minFont, Math.min(maxFont, fontSize + (scale > 1 ? step : -step)));
            terminal.setTextSize(fontSize);
            prefs.edit().putInt("font", fontSize).apply();
            return 1.0f;
        }
        return scale;
    }

    @Override
    public void onSingleTapUp(MotionEvent e) {
        terminal.requestFocus();
        showKeyboard();
    }

    @Override
    public boolean shouldBackButtonBeMappedToEscape() {
        return false;
    }

    @Override
    public boolean shouldEnforceCharBasedInput() {
        // Keyboards with word prediction otherwise rewrite what was typed.
        return true;
    }

    @Override
    public boolean shouldUseCtrlSpaceWorkaround() {
        return false;
    }

    @Override
    public boolean isTerminalViewSelected() {
        return true;
    }

    @Override
    public void copyModeChanged(boolean copyMode) {
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
        if (keyCode == KeyEvent.KEYCODE_ENTER && session != null && !session.isRunning()) {
            closeSession(session);
            return true;
        }
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        return false;
    }

    @Override
    public boolean onLongPress(MotionEvent event) {
        return false;
    }

    @Override
    public boolean readControlKey() {
        boolean was = ctrl;
        if (was) {
            ctrl = false;
            showModifiers();
        }
        return was;
    }

    @Override
    public boolean readAltKey() {
        boolean was = alt;
        if (was) {
            alt = false;
            showModifiers();
        }
        return was;
    }

    @Override
    public boolean readShiftKey() {
        return false;
    }

    @Override
    public boolean readFnKey() {
        return false;
    }

    @Override
    public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
        return false;
    }

    @Override
    public void onEmulatorSet() {
        terminal.setTerminalCursorBlinkerRate(600);
        terminal.setTerminalCursorBlinkerState(true, true);
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
