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
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import java.util.List;
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
    private static final int REQUEST_VOICE = 2;
    private static final int REQUEST_SMS = 3;

    // One palette for the whole app; the terminal colours in TermService match it.
    static final int BG = 0xff0d1117;
    static final int SURFACE = 0xff161b22;
    static final int SURFACE_HIGH = 0xff262c36;
    static final int TEXT = 0xffe6edf3;
    static final int MUTED = 0xff8b949e;
    static final int ACCENT = 0xffff7a45;
    static final int ON_ACCENT = 0xff1b0d05;
    static final int ERROR = 0xffffa198;
    private static final int KEY_TEXT = 0xffc9d1d9;

    private static boolean onScreen;

    /** Android only lets an app open other apps (Clock, browser) while it is visible. */
    static boolean isOnScreen() {
        return onScreen;
    }

    private SharedPreferences prefs;
    private TermService service;
    private TerminalSession current;

    private View setupPanel;
    private TextView setupStatus, setupError;
    private ProgressBar setupBar;
    private Button retryButton;

    private View terminalPanel;
    private ChatView chatPanel;
    private SettingsView settingsPanel;
    private static MainActivity visible;
    private TerminalView terminal;
    private LinearLayout tabs;
    private View askBar, keyRows;
    private EditText askInput;
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
        chatPanel = new ChatView(this, new ChatView.Host() {
            @Override
            public void onOpenTerminal() {
                showTerminal();
            }

            @Override
            public void onVoice() {
                startVoice();
            }

            @Override
            public void onSettings() {
                showSettings();
            }

            @Override
            public void onPickWorkspace() {
                pickWorkspace();
            }

            @Override
            public void onPickModel() {
                pickModel(false);
            }

            @Override
            public void onOpenArena() {
                openArena();
            }
        });
        settingsPanel = new SettingsView(this, new SettingsView.Host() {
            @Override
            public void onCloseSettings() {
                showChat();
            }

            @Override
            public void onPickModel() {
                pickModel(false);
            }

            @Override
            public void onPickWorkspace() {
                pickWorkspace();
            }

            @Override
            public void onAskSms() {
                askForSms();
            }

            @Override
            public void onOpenArena() {
                openArena();
            }
        });
        settingsPanel.setVisibility(View.GONE);
        rootView.addView(chatPanel, match());
        rootView.addView(settingsPanel, match());
        rootView.addView(terminalPanel, match());
        rootView.addView(setupPanel, match());
        chatPanel.setVisibility(View.GONE);
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
    protected void onStart() {
        super.onStart();
        onScreen = true;
        visible = this;
    }

    @Override
    protected void onStop() {
        onScreen = false;
        if (visible == this) visible = null;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        Installer.detach(this);
        chatPanel.detach();
        settingsPanel.detach();
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
            showReady();
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
        if (terminalPanel.getVisibility() == View.VISIBLE || settingsPanel.getVisibility() == View.VISIBLE) {
            showChat();
            return;
        }
        // Leave without ending the sessions; the notification brings them back.
        moveTaskToBack(true);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private Drawable rounded(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    /** Rounded shape that shows a touch ripple. */
    private Drawable pressable(int color, float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(0x40ffffff),
                rounded(color, radiusDp), rounded(0xffffffff, radiusDp));
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    // ------------------------------------------------------------ first-run setup

    private View buildSetupPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(28), dp(72), dp(28), dp(28));

        TextView mark = new TextView(this);
        mark.setText(">_");
        mark.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        mark.setTextSize(22);
        mark.setTextColor(ON_ACCENT);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(rounded(ACCENT, 16));
        box.addView(mark, new LinearLayout.LayoutParams(dp(64), dp(64)));

        TextView title = new TextView(this);
        title.setText(R.string.setup_title);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(TEXT);
        title.setPadding(0, dp(24), 0, 0);
        box.addView(title);

        setupStatus = new TextView(this);
        setupStatus.setTextSize(15);
        setupStatus.setTextColor(TEXT);
        setupStatus.setPadding(0, dp(20), 0, dp(10));
        box.addView(setupStatus);

        setupBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        setupBar.setMax(100);
        setupBar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        setupBar.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        setupBar.setProgressBackgroundTintList(ColorStateList.valueOf(SURFACE_HIGH));
        box.addView(setupBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView note = new TextView(this);
        note.setText(R.string.setup_note);
        note.setTextSize(13);
        note.setTextColor(MUTED);
        note.setPadding(0, dp(20), 0, 0);
        box.addView(note);

        setupError = new TextView(this);
        setupError.setTextSize(14);
        setupError.setTextColor(ERROR);
        setupError.setPadding(0, dp(20), 0, dp(16));
        setupError.setTextIsSelectable(true);
        setupError.setVisibility(View.GONE);
        box.addView(setupError);

        retryButton = new Button(this);
        retryButton.setText(R.string.retry);
        retryButton.setAllCaps(false);
        retryButton.setTextSize(16);
        retryButton.setTextColor(ON_ACCENT);
        retryButton.setBackground(pressable(ACCENT, 26));
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
        scroll.setBackgroundColor(BG);
        scroll.addView(box);
        return scroll;
    }

    private void startInstall() {
        terminalPanel.setVisibility(View.GONE);
        chatPanel.setVisibility(View.GONE);
        settingsPanel.setVisibility(View.GONE);
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
        if (service == null) return;
        showReady();
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
        panel.setBackgroundColor(BG);

        // top bar: session tabs, AI, new session, menu
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(6), dp(2), dp(6));
        tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.addView(tabs);
        bar.addView(tabScroll, new LinearLayout.LayoutParams(0, dp(36), 1));

        TextView ai = label("\u2726 " + getString(R.string.chat_back), getString(R.string.chat_back_description));
        ai.setTextSize(14);
        ai.setTypeface(Typeface.DEFAULT_BOLD);
        ai.setTextColor(ON_ACCENT);
        ai.setBackground(pressable(ACCENT, 18));
        ai.setPadding(dp(14), 0, dp(14), 0);
        ai.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showChat();
            }
        });
        LinearLayout.LayoutParams aiSize = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        aiSize.leftMargin = dp(6);
        bar.addView(ai, aiSize);

        TextView plus = label("+", getString(R.string.menu_new));
        plus.setTextSize(24);
        plus.setBackground(pressable(0x00000000, 18));
        plus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newSession();
            }
        });
        bar.addView(plus, new LinearLayout.LayoutParams(dp(44), dp(36)));

        final TextView more = label("\u22ee", "Menu");
        more.setTextSize(20);
        more.setBackground(pressable(0x00000000, 18));
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(more);
            }
        });
        bar.addView(more, new LinearLayout.LayoutParams(dp(40), dp(36)));
        panel.addView(bar);

        terminal = new TerminalView(this, null);
        terminal.setTerminalViewClient(this);
        terminal.setTextSize(fontSize);
        terminal.setTypeface(Typeface.MONOSPACE);
        terminal.setFocusable(true);
        terminal.setFocusableInTouchMode(true);
        terminal.setBackgroundColor(BG);
        FrameLayout terminalFrame = new FrameLayout(this);
        terminalFrame.setPadding(dp(6), 0, dp(6), 0);
        terminalFrame.addView(terminal, match());
        panel.addView(terminalFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        askBar = buildAskBar();
        askBar.setVisibility(View.GONE);
        panel.addView(askBar);

        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(3), dp(2), dp(3), dp(4));
        rows.addView(buildKeyRow(new Object[][]{
                {"ESC", KeyEvent.KEYCODE_ESCAPE}, {"/", "/"}, {"-", "-"},
                {"HOME", KeyEvent.KEYCODE_MOVE_HOME}, {"\u2191", KeyEvent.KEYCODE_DPAD_UP},
                {"END", KeyEvent.KEYCODE_MOVE_END}, {"PGUP", KeyEvent.KEYCODE_PAGE_UP}}));
        rows.addView(buildKeyRow(new Object[][]{
                {"TAB", KeyEvent.KEYCODE_TAB}, {"CTRL", "ctrl"}, {"ALT", "alt"},
                {"\u2190", KeyEvent.KEYCODE_DPAD_LEFT}, {"\u2193", KeyEvent.KEYCODE_DPAD_DOWN},
                {"\u2192", KeyEvent.KEYCODE_DPAD_RIGHT}, {"PGDN", KeyEvent.KEYCODE_PAGE_DOWN}}));
        keyRows = rows;
        panel.addView(rows);
        return panel;
    }

    /** Plain-language box for the agent: a normal text field, so any keyboard, language or voice typing works. */
    private View buildAskBar() {
        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(rounded(SURFACE, 24));
        pill.setPadding(dp(16), 0, dp(4), 0);

        askInput = new EditText(this);
        askInput.setHint(R.string.ask_hint);
        askInput.setHintTextColor(MUTED);
        askInput.setTextColor(TEXT);
        askInput.setTextSize(15);
        askInput.setBackground(null);
        askInput.setPadding(0, dp(10), 0, dp(10));
        askInput.setMaxLines(4);
        askInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        askInput.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        askInput.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean focused) {
                // The extra keys are for the terminal; hide them while writing a request.
                keyRows.setVisibility(focused ? View.GONE : View.VISIBLE);
            }
        });
        pill.addView(askInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView mic = label("\uD83C\uDFA4", getString(R.string.ask_voice));
        mic.setTextSize(17);
        mic.setBackground(pressable(0x00000000, 20));
        mic.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startVoice();
            }
        });
        pill.addView(mic, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView send = label("\u2191", getString(R.string.ask_send));
        send.setTextSize(20);
        send.setTypeface(Typeface.DEFAULT_BOLD);
        send.setTextColor(ON_ACCENT);
        send.setBackground(pressable(ACCENT, 20));
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendToAgent(askInput.getText().toString());
            }
        });
        pill.addView(send, new LinearLayout.LayoutParams(dp(40), dp(40)));

        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(8), dp(4), dp(8), dp(4));
        wrap.addView(pill, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    private void sendToAgent(String text) {
        final String request = text.trim();
        final TerminalSession session = current;
        if (request.isEmpty() || session == null || !session.isRunning() || session.getEmulator() == null) return;
        askInput.setText("");
        session.getEmulator().paste(request.replace('\n', ' '));
        // Enter goes separately, after the agent's input box has taken the text.
        terminal.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (session.isRunning()) session.write("\r");
            }
        }, 200);
    }

    private void startVoice() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        try {
            startActivityForResult(i, REQUEST_VOICE);
        } catch (Exception e) {
            Toast.makeText(this, R.string.voice_missing, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQUEST_VOICE || result != RESULT_OK || data == null) return;
        java.util.ArrayList<String> heard = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (heard == null || heard.isEmpty()) return;
        // Shown in the box first, so a misheard request can be fixed before sending.
        if (chatPanel.getVisibility() == View.VISIBLE) {
            chatPanel.setDraft(heard.get(0));
            return;
        }
        askInput.setText(heard.get(0));
        askInput.setSelection(askInput.length());
        askInput.requestFocus();
    }

    private TextView label(String text, String description) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setContentDescription(description);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(KEY_TEXT);
        v.setClickable(true);
        v.setFocusable(false);
        v.setSingleLine(true);
        return v;
    }

    private View buildKeyRow(Object[][] keys) {
        LinearLayout row = new LinearLayout(this);
        for (final Object[] key : keys) {
            final TextView v = label((String) key[0], (String) key[0]);
            v.setTextSize(12);
            v.setTypeface(Typeface.DEFAULT_BOLD);
            v.setBackground(pressable(SURFACE, 8));
            if ("ctrl".equals(key[1])) ctrlKey = v;
            if ("alt".equals(key[1])) altKey = v;
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                    onExtraKey(key[1]);
                }
            });
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(36), 1);
            size.setMargins(dp(2), dp(2), dp(2), dp(2));
            row.addView(v, size);
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
        ctrlKey.setTextColor(ctrl ? ON_ACCENT : KEY_TEXT);
        ctrlKey.setBackground(pressable(ctrl ? ACCENT : SURFACE, 8));
        altKey.setTextColor(alt ? ON_ACCENT : KEY_TEXT);
        altKey.setBackground(pressable(alt ? ACCENT : SURFACE, 8));
    }

    /** Ubuntu is installed: bring its helper files up to date and show the chat (or the terminal, if that was open). */
    private void showReady() {
        try {
            Ubuntu.prepareHost(this);
            Ubuntu.refreshFiles(this);
        } catch (Exception e) {
            Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
        }
        service.onUbuntuReady();
        chatPanel.attach(service.chat);
        settingsPanel.attach(service.rules);
        if (service.terminalShown) showTerminal();
        else showChat();
    }

    private void showChat() {
        if (service != null) service.terminalShown = false;
        hideKeyboard();
        setupPanel.setVisibility(View.GONE);
        terminalPanel.setVisibility(View.GONE);
        settingsPanel.setVisibility(View.GONE);
        chatPanel.refreshChips();
        chatPanel.setVisibility(View.VISIBLE);
    }

    private void showSettings() {
        hideKeyboard();
        settingsPanel.refresh();
        chatPanel.setVisibility(View.GONE);
        settingsPanel.setVisibility(View.VISIBLE);
    }

    /** Opens the Arena.ai website in the phone's browser; signing in happens there. */
    private void openArena() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://arena.ai/")));
        } catch (Exception e) {
            Toast.makeText(this, R.string.arena_no_browser, Toast.LENGTH_LONG).show();
        }
    }

    /** Asks Android for permission to send text messages, if the app is on screen. */
    static void askForSms() {
        if (visible != null) {
            visible.requestPermissions(new String[]{Manifest.permission.SEND_SMS}, REQUEST_SMS);
        }
    }

    private void settingsChanged() {
        chatPanel.refreshChips();
        settingsPanel.refresh();
    }

    // ------------------------------------------------------------ workspace and model

    private void pickWorkspace() {
        final List<String> folders = Config.workspaces(this);
        String[] names = new String[folders.size() + 1];
        int current = -1;
        for (int i = 0; i < folders.size(); i++) {
            String f = folders.get(i);
            names[i] = f.equals("/sdcard") ? getString(R.string.workspace_storage)
                    : f.startsWith("/root/") ? "~/" + f.substring(6) : f;
            if (f.equals(Config.workspace(this))) current = i;
        }
        names[folders.size()] = getString(R.string.workspace_new);
        new AlertDialog.Builder(this)
                .setTitle(R.string.workspace_title)
                .setSingleChoiceItems(names, current, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        if (which == folders.size()) newWorkspace();
                        else useWorkspace(folders.get(which));
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void newWorkspace() {
        final EditText box = new EditText(this);
        box.setHint(R.string.workspace_new_hint);
        box.setSingleLine(true);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(box);
        new AlertDialog.Builder(this)
                .setTitle(R.string.workspace_title)
                .setView(wrap)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String name = box.getText().toString().trim();
                        if (!name.matches("[A-Za-z0-9_][A-Za-z0-9._-]{0,40}")) {
                            Toast.makeText(MainActivity.this, R.string.workspace_bad_name, Toast.LENGTH_LONG).show();
                            return;
                        }
                        new java.io.File(Ubuntu.rootfs(MainActivity.this), "root/" + name).mkdirs();
                        useWorkspace("/root/" + name);
                    }
                })
                .show();
    }

    private void useWorkspace(String path) {
        if (!path.equals(Config.workspace(this))) {
            Config.setWorkspace(this, path);
            // OpenCode keeps conversations per folder, so the next message starts a new one
            if (service != null) service.chat.startOver();
        }
        settingsChanged();
    }

    private void pickModel(boolean reloaded) {
        if (service == null) return;
        final List<String> models = Config.cachedModels(this);
        if (models.isEmpty() && !reloaded) {
            loadModels();
            return;
        }
        String[] names = new String[models.size() + 2];
        names[0] = getString(R.string.model_auto_long);
        int current = Config.model(this).isEmpty() ? 0 : -1;
        for (int i = 0; i < models.size(); i++) {
            names[i + 1] = models.get(i);
            if (models.get(i).equals(Config.model(this))) current = i + 1;
        }
        names[models.size() + 1] = getString(R.string.model_refresh);
        new AlertDialog.Builder(this)
                .setTitle(R.string.model_title)
                .setSingleChoiceItems(names, current, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        if (which == models.size() + 1) {
                            loadModels();
                            return;
                        }
                        Config.setModel(MainActivity.this, which == 0 ? "" : models.get(which - 1));
                        settingsChanged();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void loadModels() {
        Toast.makeText(this, R.string.model_loading, Toast.LENGTH_SHORT).show();
        service.chat.loadModels(new Chat.ModelsCallback() {
            @Override
            public void onModels(List<String> found) {
                if (isFinishing() || isDestroyed()) return;
                if (found == null) {
                    boolean downloaded = new java.io.File(Ubuntu.rootfs(MainActivity.this),
                            "root/.opencode/bin/opencode").exists();
                    Toast.makeText(MainActivity.this,
                            downloaded ? R.string.model_failed : R.string.model_not_ready, Toast.LENGTH_LONG).show();
                }
                pickModel(true);
            }
        });
    }

    private void showTerminal() {
        if (service == null) return;
        service.terminalShown = true;
        setupPanel.setVisibility(View.GONE);
        chatPanel.setVisibility(View.GONE);
        terminalPanel.setVisibility(View.VISIBLE);
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
        boolean agent = service != null && service.agentSessions.contains(session);
        askBar.setVisibility(agent ? View.VISIBLE : View.GONE);
        if (!agent) {
            keyRows.setVisibility(View.VISIBLE);
            terminal.requestFocus();
        }
    }

    private void newSession() {
        openSession(false);
    }

    private void openAgent() {
        openSession(true);
    }

    private void openSession(boolean agent) {
        if (service == null) return;
        TerminalSession s = agent
                ? service.createSession(Ubuntu.agentCommand(this))
                : service.createSession();
        if (s != null && agent) service.agentSessions.add(s);
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
            // the last tab is closed: back to the chat, the app keeps running
            current = null;
            showChat();
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
            boolean agent = service.agentSessions.contains(s);
            String title = s.getTitle();
            String name = agent ? "\u2726 Agent" : (title == null || title.isEmpty()) ? "Ubuntu" : title;
            String text = i + "  " + name;
            if (text.length() > 20) text = text.substring(0, 19) + "\u2026";
            TextView tab = label(text, text);
            tab.setTextSize(13);
            tab.setPadding(dp(14), 0, dp(14), 0);
            if (s == current) {
                tab.setTextColor(TEXT);
                tab.setTypeface(Typeface.DEFAULT_BOLD);
                tab.setBackground(pressable(SURFACE_HIGH, 18));
            } else {
                tab.setTextColor(MUTED);
                tab.setBackground(pressable(0x00000000, 18));
            }
            tab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    select(s);
                }
            });
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            size.rightMargin = dp(4);
            tabs.addView(tab, size);
            if (s == current) {
                // keep the open tab in view when there are more tabs than fit
                final View shown = tab;
                tabs.post(new Runnable() {
                    @Override
                    public void run() {
                        shown.requestRectangleOnScreen(
                                new android.graphics.Rect(0, 0, shown.getWidth(), shown.getHeight()), false);
                    }
                });
            }
        }
    }

    // ------------------------------------------------------------ menu

    private void showMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        Menu m = popup.getMenu();
        m.add(0, 1, 0, R.string.menu_new);
        m.add(0, 10, 0, R.string.menu_agent);
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
            case 10:
                openAgent();
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
        if (request == REQUEST_SMS) {
            settingsPanel.refresh();
            return;
        }
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
                        service.chat.stop();
                        service.terminalShown = false;
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
