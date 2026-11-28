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
    private static final int REQUEST_MIC = 4;
    private static final int REQUEST_LIVE = 5;
    private static final int REQUEST_ATTACH = 5;
    /** The biggest file the chat takes with a message. */
    private static final long ATTACH_LIMIT = 200L * 1024 * 1024;

    // Colours come from Theme (dark/light). These methods keep old call sites working.
    static int bg() {
        return Theme.bg();
    }

    static int surface() {
        return Theme.surface();
    }

    static int surfaceHigh() {
        return Theme.surfaceHigh();
    }

    static int text() {
        return Theme.text();
    }

    static int muted() {
        return Theme.muted();
    }

    static int accent() {
        return Theme.accent();
    }

    static int onAccent() {
        return Theme.onAccent();
    }

    static int error() {
        return Theme.error();
    }

    static int keyText() {
        return Theme.keyText();
    }

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
    private LiveCallView livePanel;
    private boolean liveWanted;
    private static MainActivity visible;
    private TerminalView terminal;
    private LinearLayout tabs;
    private View askBar, keyRows;
    private EditText askInput;
    private TextView ctrlKey, altKey;
    private android.widget.ImageView askMic;
    /** Dictation: the listener, where its words go, and what was in the box before. */
    private Voice voice;
    private boolean voiceInChat;
    private String voiceBefore = "";
    private boolean ctrl, alt;
    private int fontSize, minFont, maxFont;

    // ------------------------------------------------------------ lifecycle

    /** Set while the screens are rebuilt after a change of language or theme, to land in Settings again. */
    private static boolean backToSettings;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        Theme.apply(this);
        setTheme(Theme.isLight() ? R.style.AppTheme_Light : R.style.AppTheme);
        super.onCreate(state);
        if (Theme.isLight() && Build.VERSION.SDK_INT >= 26) {
            // dark icons in the navigation bar too; the status bar's come from the style
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
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

            @Override
            public void onAttach() {
                pickAttachment();
            }

            @Override
            public void onLive() {
                startLive();
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
            public void onPickPersona() {
                pickPersona();
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

            @Override
            public void onPickBackups() {
                pickBackups(false);
            }

            @Override
            public void onLookChanged() {
                backToSettings = true;
                if (service != null) {
                    service.refreshNotification();
                    service.recolorTerminals();
                }
                recreate();
            }

            @Override
            public void onOpenScreenSettings() {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, R.string.screen_no_settings, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onOpenOverlaySettings() {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, R.string.overlay_no_settings, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onToggleWake() {
                if (service != null) service.setWakeWord(!Config.wakeWord(MainActivity.this));
            }

            @Override
            public void onOpenLink(String url) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, url, Toast.LENGTH_LONG).show();
                }
            }
        });
        settingsPanel.setVisibility(View.GONE);
        livePanel = new LiveCallView(this, new LiveCallView.Host() {
            @Override
            public void onLiveEnded(String reason, long millis) {
                if (service != null) service.resumeWake();
                if (reason.equals("quota")) {
                    Toast.makeText(MainActivity.this, R.string.live_quota, Toast.LENGTH_LONG).show();
                } else if (reason.equals("no-key")) {
                    Toast.makeText(MainActivity.this, R.string.live_no_key, Toast.LENGTH_LONG).show();
                } else if (reason.startsWith("error:")) {
                    Toast.makeText(MainActivity.this, reason, Toast.LENGTH_LONG).show();
                } else {
                    long s = millis / 1000;
                    Toast.makeText(MainActivity.this,
                            getString(R.string.live_ended, (s / 60) + " min"), Toast.LENGTH_LONG).show();
                }
            }
        });
        rootView.addView(chatPanel, match());
        rootView.addView(settingsPanel, match());
        rootView.addView(terminalPanel, match());
        rootView.addView(setupPanel, match());
        rootView.addView(livePanel, match());
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
        // back from Android's settings: Screen control may have been turned on or off there
        if (settingsPanel != null) settingsPanel.refresh();
        TermService.pokeSurfaces(); // the card over other apps goes away, a waiting question comes here
    }

    @Override
    protected void onStop() {
        if (voice != null) voice.cancel(); // never listen from the background
        onScreen = false;
        if (visible == this) visible = null;
        TermService.pokeSurfaces(); // the agent's work and questions now show over the other app
        super.onStop();
    }

    // ------------------------------------------------------------ a question from the agent

    private android.app.AlertDialog questionDialog;
    private Ask.Question questionShown;

    /** Shows the question that is waiting (or takes an answered one away) if Termant is on screen. */
    static void showQuestion() {
        MainActivity here = visible;
        if (here != null) here.syncQuestion();
    }

    private void syncQuestion() {
        final Ask.Question q = Ask.pending();
        if (q == questionShown && (q == null || questionDialog != null)) return;
        if (questionDialog != null) {
            questionDialog.setOnCancelListener(null);
            questionDialog.dismiss();
            questionDialog = null;
        }
        questionShown = q;
        if (q == null || isFinishing()) return;

        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
        Context c = b.getContext();
        float density = getResources().getDisplayMetrics().density;
        int gap = Math.round(8 * density);
        android.widget.LinearLayout box = new android.widget.LinearLayout(c);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(3 * gap, gap, 3 * gap, 0);
        TextView text = new TextView(c);
        text.setText(q.text);
        text.setTextSize(16);
        text.setPadding(0, 0, 0, gap);
        box.addView(text);
        for (final String choice : q.choices) {
            android.widget.Button option = new android.widget.Button(c);
            option.setText(choice);
            option.setAllCaps(false);
            option.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Ask.answer(q, choice);
                }
            });
            box.addView(option);
        }
        final android.widget.EditText input = new android.widget.EditText(c);
        input.setHint(q.choices.isEmpty() ? R.string.answer_hint : R.string.ask_write);
        input.setMaxLines(3);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        box.addView(input);
        android.widget.ScrollView scroll = new android.widget.ScrollView(c);
        scroll.addView(box);
        b.setTitle(R.string.ask_from)
                .setView(scroll)
                .setPositiveButton(R.string.ask_send, null)
                .setNegativeButton(R.string.ask_close, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        Ask.close(q);
                    }
                })
                .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(android.content.DialogInterface dialog) {
                        Ask.close(q);
                    }
                });
        questionDialog = b.create();
        questionDialog.setCanceledOnTouchOutside(false);
        questionDialog.show();
        // "Send" keeps the dialog open while the box is empty
        questionDialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ask.answer(q, input.getText().toString());
            }
        });
        android.util.Log.i("Termant", "ask: shown in Termant");
    }

    @Override
    protected void onDestroy() {
        if (questionDialog != null) {
            questionDialog.setOnCancelListener(null);
            questionDialog.dismiss();
            questionDialog = null;
        }
        if (voice != null) voice.destroy();
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
        return new RippleDrawable(ColorStateList.valueOf(Theme.ripple()),
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
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(28), dp(40), dp(28), dp(32));

        box.addView(Ui.badge(this, R.drawable.ic_terminal, 60, 30), new LinearLayout.LayoutParams(dp(60), dp(60)));

        TextView title = new TextView(this);
        title.setText(R.string.setup_title);
        title.setTextSize(Ui.DISPLAY);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(-0.01f);
        title.setTextColor(text());
        title.setPadding(0, dp(22), 0, 0);
        box.addView(title);

        // what is happening now, and how far it is
        LinearLayout state = new LinearLayout(this);
        state.setOrientation(LinearLayout.VERTICAL);
        state.setPadding(dp(16), dp(14), dp(16), dp(14));
        state.setBackground(Ui.card(this, Ui.R_CARD));

        setupStatus = new TextView(this);
        setupStatus.setTextSize(Ui.BODY);
        setupStatus.setTypeface(Ui.medium());
        setupStatus.setTextColor(text());
        state.addView(setupStatus);

        setupBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        setupBar.setMax(100);
        setupBar.setProgressTintList(ColorStateList.valueOf(accent()));
        setupBar.setIndeterminateTintList(ColorStateList.valueOf(accent()));
        setupBar.setProgressBackgroundTintList(ColorStateList.valueOf(Theme.outline()));
        LinearLayout.LayoutParams barSize = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barSize.topMargin = dp(8);
        state.addView(setupBar, barSize);

        setupError = new TextView(this);
        setupError.setTextSize(Ui.SMALL);
        setupError.setTextColor(error());
        setupError.setLineSpacing(dp(2), 1);
        setupError.setPadding(0, dp(8), 0, 0);
        setupError.setTextIsSelectable(true);
        setupError.setVisibility(View.GONE);
        state.addView(setupError);

        LinearLayout.LayoutParams stateSize = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stateSize.topMargin = dp(22);
        box.addView(state, stateSize);

        retryButton = new Button(this);
        retryButton.setText(R.string.retry);
        retryButton.setAllCaps(false);
        retryButton.setTextSize(Ui.BODY);
        retryButton.setTypeface(Ui.medium());
        retryButton.setTextColor(onAccent());
        retryButton.setStateListAnimator(null);
        retryButton.setBackground(pressable(accent(), 25));
        retryButton.setVisibility(View.GONE);
        retryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startInstall();
            }
        });
        LinearLayout.LayoutParams retrySize = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        retrySize.topMargin = dp(14);
        box.addView(retryButton, retrySize);

        TextView note = new TextView(this);
        note.setText(R.string.setup_note);
        note.setTextSize(Ui.SMALL);
        note.setTextColor(muted());
        note.setLineSpacing(dp(2), 1);
        note.setPadding(dp(2), dp(18), dp(2), 0);
        box.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(bg());
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
        panel.setBackgroundColor(bg());

        // top bar: session tabs, AI, new session, menu
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(6), dp(4), dp(6));
        tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.addView(tabs);
        bar.addView(tabScroll, new LinearLayout.LayoutParams(0, dp(36), 1));

        TextView ai = label(getString(R.string.chat_back), getString(R.string.chat_back_description));
        ai.setTextSize(Ui.SMALL);
        ai.setTypeface(Ui.medium());
        ai.setTextColor(onAccent());
        ai.setBackground(pressable(accent(), 18));
        ai.setPadding(dp(12), 0, dp(14), 0);
        Ui.leading(ai, R.drawable.ic_sparkle, onAccent(), 14, 6);
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

        android.widget.ImageView plus = Ui.iconButton(this, R.drawable.ic_plus, getString(R.string.menu_new),
                keyText(), 38, 20);
        plus.setFocusable(false);
        plus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newSession();
            }
        });
        LinearLayout.LayoutParams plusSize = new LinearLayout.LayoutParams(dp(38), dp(38));
        plusSize.leftMargin = dp(2);
        bar.addView(plus, plusSize);

        final android.widget.ImageView more = Ui.iconButton(this, R.drawable.ic_more, "Menu", keyText(), 38, 20);
        more.setFocusable(false);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(more);
            }
        });
        bar.addView(more, new LinearLayout.LayoutParams(dp(38), dp(38)));
        panel.addView(bar);

        terminal = new TerminalView(this, null);
        terminal.setTerminalViewClient(this);
        terminal.setTextSize(fontSize);
        terminal.setTypeface(Typeface.MONOSPACE);
        terminal.setFocusable(true);
        terminal.setFocusableInTouchMode(true);
        terminal.setBackgroundColor(Theme.terminalBg());
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
        pill.setBackground(Ui.card(this, 24));
        pill.setPadding(dp(16), dp(3), dp(4), dp(3));

        askInput = new EditText(this);
        askInput.setHint(R.string.ask_hint);
        askInput.setHintTextColor(muted());
        askInput.setTextColor(text());
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

        askMic = Ui.iconButton(this, R.drawable.ic_mic, getString(R.string.ask_voice), muted(), 40, 21);
        askMic.setFocusable(false);
        askMic.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startVoice();
            }
        });
        pill.addView(askMic, new LinearLayout.LayoutParams(dp(40), dp(40)));

        android.widget.ImageView send = Ui.iconButton(this, R.drawable.ic_arrow_up, getString(R.string.ask_send),
                onAccent(), 40, 21);
        send.setFocusable(false);
        send.setBackground(pressable(accent(), 20));
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

    /**
     * The microphone button: listens inside the app and writes the words into
     * the message box as they are spoken; a second tap ends it. Phones whose
     * speech service cannot do that get the service's own window instead.
     */
    private void startVoice() {
        if (voice != null && voice.isListening()) {
            voice.stop();
            return;
        }
        if (!Voice.available(this)) {
            voiceWindow();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
            return;
        }
        listen();
    }

    private void listen() {
        if (service != null) service.pauseWake(); // the wake word must not hear this speech
        voiceInChat = chatPanel.getVisibility() == View.VISIBLE;
        voiceBefore = (voiceInChat ? chatPanel.draft() : askInput.getText().toString()).trim();
        if (voice == null) {
            voice = new Voice(this, new Voice.Listener() {
                @Override
                public void onListening(boolean listening) {
                    if (!listening && service != null) service.resumeWake();
                    chatPanel.setListening(listening && voiceInChat);
                    boolean here = listening && !voiceInChat;
                    askMic.setBackground(pressable(here ? accent() : 0x00000000, 20));
                    askMic.setImageDrawable(Ui.icon(MainActivity.this, R.drawable.ic_mic, here ? onAccent() : muted()));
                    askMic.setContentDescription(getString(here ? R.string.voice_stop : R.string.ask_voice));
                    askInput.setHint(here ? R.string.voice_listening : R.string.ask_hint);
                }

                @Override
                public void onHeard(String text, boolean last) {
                    heard(voiceBefore.isEmpty() ? text : voiceBefore + " " + text);
                }

                @Override
                public void onProblem(int message, String detail) {
                    android.util.Log.i("Termant", "voice: " + (message == 0 ? "using the speech window, " : "") + detail);
                    if (message == 0) voiceWindow();
                    else Toast.makeText(MainActivity.this, getString(message, detail), Toast.LENGTH_LONG).show();
                }
            });
        }
        voice.start();
    }

    /** The speech service's own window; its result arrives in onActivityResult. */
    private void voiceWindow() {
        try {
            startActivityForResult(Voice.request(this), REQUEST_VOICE);
        } catch (Exception e) {
            Toast.makeText(this, R.string.voice_missing, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * The live voice call: talk to the phone out loud and it talks back, and while
     * you talk it can run a command in the phone's terminal. The Gemini key comes
     * from Settings, and a used-up live quota keeps the button off until tomorrow.
     */
    private void startLive() {
        if (Config.liveBlocked(this) > System.currentTimeMillis()) {
            Toast.makeText(this, R.string.live_quota, Toast.LENGTH_LONG).show();
            return;
        }
        String key = Config.keys(this).get("GOOGLE_GENERATIVE_AI_API_KEY");
        if (key == null || key.trim().isEmpty()) {
            Toast.makeText(this, R.string.live_no_key, Toast.LENGTH_LONG).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            liveWanted = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_LIVE);
            return;
        }
        openLive();
    }

    private void openLive() {
        liveWanted = false;
        if (service != null) service.pauseWake(); // the wake word must not hear the call
        livePanel.setVisibility(View.VISIBLE);
        livePanel.begin();
    }

    /** Shown in the box first, so a misheard request can be fixed before sending. */
    private void heard(String text) {
        if (voiceInChat) {
            chatPanel.setDraft(text);
            return;
        }
        askInput.setText(text);
        askInput.setSelection(askInput.length());
        askInput.requestFocus();
    }

    /** Lets the user pick pictures or files (any kind) to send with the next message. */
    private void pickAttachment() {
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
        pick.setType("*/*");
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(Intent.createChooser(pick, getString(R.string.attach_file)), REQUEST_ATTACH);
        } catch (Exception e) {
            Toast.makeText(this, R.string.attach_failed, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Copies what the user picked into Ubuntu's /root/uploads, where the agent can read it,
     * and hangs each file on the message being written.
     */
    private void takeAttachments(Intent data) {
        final java.util.List<android.net.Uri> picked = new java.util.ArrayList<>();
        android.content.ClipData many = data.getClipData();
        if (many != null) {
            for (int i = 0; i < many.getItemCount() && picked.size() < 10; i++) {
                if (many.getItemAt(i).getUri() != null) picked.add(many.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            picked.add(data.getData());
        }
        if (picked.isEmpty()) return;
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (final android.net.Uri uri : picked) {
                    String path = null;
                    try {
                        path = copyIntoUbuntu(app, uri);
                    } catch (Exception e) {
                        android.util.Log.w("Termant", "attach: " + e);
                    }
                    final String made = path;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (made == null) {
                                Toast.makeText(MainActivity.this, R.string.attach_failed, Toast.LENGTH_LONG).show();
                            } else {
                                chatPanel.addAttachment(made);
                            }
                        }
                    });
                }
            }
        }, "attach").start();
    }

    /** The path inside Ubuntu of a copy of the picked file, or null when it is too big or cannot be read. */
    private static String copyIntoUbuntu(Context c, android.net.Uri uri) throws Exception {
        String name = null;
        try (android.database.Cursor row = c.getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (row != null && row.moveToFirst()) name = row.getString(0);
        } catch (Exception e) {
            // some pickers give no name: the last part of the address does
        }
        if (name == null || name.trim().isEmpty()) name = uri.getLastPathSegment();
        if (name == null) name = "file";
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]+", "_");
        while (name.startsWith(".") || name.startsWith("-")) name = name.substring(1);
        if (name.isEmpty()) name = "file";
        if (name.length() > 80) name = name.substring(name.length() - 80);
        java.io.File folder = new java.io.File(Ubuntu.rootfs(c), "root/uploads");
        if (!folder.isDirectory() && !folder.mkdirs()) return null;
        java.io.File target = new java.io.File(folder, name);
        for (int n = 2; target.exists() && n < 1000; n++) target = new java.io.File(folder, n + "-" + name);
        long total = 0;
        boolean whole = false;
        try (java.io.InputStream in = c.getContentResolver().openInputStream(uri);
             java.io.OutputStream out = new java.io.FileOutputStream(target)) {
            if (in == null) return null;
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > ATTACH_LIMIT) break;
                out.write(buffer, 0, n);
            }
            whole = total > 0 && total <= ATTACH_LIMIT;
        } finally {
            if (!whole) target.delete();
        }
        if (!whole) return null;
        android.util.Log.i("Termant", "attach: " + target.getName() + " (" + total + " bytes)");
        return "/root/uploads/" + target.getName();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_ATTACH) {
            if (result == RESULT_OK && data != null) takeAttachments(data);
            return;
        }
        if (request != REQUEST_VOICE || result != RESULT_OK || data == null) return;
        java.util.ArrayList<String> heard = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (heard == null || heard.isEmpty()) return;
        voiceInChat = chatPanel.getVisibility() == View.VISIBLE;
        heard(heard.get(0));
    }

    private TextView label(String text, String description) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setContentDescription(description);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(keyText());
        v.setClickable(true);
        v.setFocusable(false);
        v.setSingleLine(true);
        return v;
    }

    private View buildKeyRow(Object[][] keys) {
        LinearLayout row = new LinearLayout(this);
        for (final Object[] key : keys) {
            final TextView v = label((String) key[0], (String) key[0]);
            v.setTextSize(11.5f);
            v.setTypeface(Ui.medium());
            v.setLetterSpacing(0.03f);
            v.setBackground(Ui.chip(this, surface(), 9));
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
            size.setMargins(dp(2.5f), dp(2.5f), dp(2.5f), dp(2.5f));
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
        ctrlKey.setTextColor(ctrl ? onAccent() : keyText());
        ctrlKey.setBackground(ctrl ? pressable(accent(), 9) : Ui.chip(this, surface(), 9));
        altKey.setTextColor(alt ? onAccent() : keyText());
        altKey.setBackground(alt ? pressable(accent(), 9) : Ui.chip(this, surface(), 9));
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
        if (backToSettings) {
            backToSettings = false;
            showSettings();
        }
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
        settingsPanel.toTop();
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

    /** The chat persona: none, or one of the four roles. */
    private void pickPersona() {
        final String[] ids = {"", "gf", "employee", "secretary", "manager"};
        final String[] names = {
                getString(R.string.persona_off),
                getString(R.string.persona_gf),
                getString(R.string.persona_employee),
                getString(R.string.persona_secretary),
                getString(R.string.persona_manager),
        };
        int current = 0;
        for (int i = 0; i < ids.length; i++)
            if (ids[i].equals(Config.persona(this))) current = i;
        new AlertDialog.Builder(this)
                .setTitle(R.string.persona_title)
                .setSingleChoiceItems(names, current, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        Config.setPersona(MainActivity.this, ids[which]);
                        settingsPanel.refresh();
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

    /** Which models auto-switch may move to, in the order of the list. */
    private void pickBackups(boolean reloaded) {
        if (service == null) return;
        final List<String> models = Config.cachedModels(this);
        if (models.isEmpty()) {
            if (reloaded) return;
            Toast.makeText(this, R.string.model_loading, Toast.LENGTH_SHORT).show();
            service.chat.loadModels(new Chat.ModelsCallback() {
                @Override
                public void onModels(List<String> found) {
                    if (isFinishing() || isDestroyed()) return;
                    if (found == null) {
                        Toast.makeText(MainActivity.this, R.string.model_not_ready, Toast.LENGTH_LONG).show();
                    }
                    pickBackups(true);
                }
            });
            return;
        }
        List<String> chosen = Config.chosenBackups(this);
        final boolean[] ticked = new boolean[models.size()];
        for (int i = 0; i < ticked.length; i++) ticked[i] = chosen.contains(models.get(i));
        new AlertDialog.Builder(this)
                .setTitle(R.string.backups_title)
                .setMultiChoiceItems(models.toArray(new String[0]), ticked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                                ticked[which] = isChecked;
                            }
                        })
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        List<String> picked = new java.util.ArrayList<>();
                        for (int i = 0; i < ticked.length; i++) if (ticked[i]) picked.add(models.get(i));
                        Config.setBackups(MainActivity.this, picked);
                        settingsChanged();
                    }
                })
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
            String name = agent ? "Agent" : (title == null || title.isEmpty()) ? "Ubuntu" : title;
            String text = i + "  " + name;
            if (text.length() > 20) text = text.substring(0, 19) + "\u2026";
            TextView tab = label(text, text);
            tab.setTextSize(Ui.SMALL);
            tab.setPadding(dp(13), 0, dp(14), 0);
            if (agent) Ui.leading(tab, R.drawable.ic_sparkle, accent(), 13, 6);
            if (s == current) {
                tab.setTextColor(text());
                tab.setTypeface(Ui.medium());
                tab.setBackground(Ui.chip(this, surfaceHigh(), 18));
            } else {
                tab.setTextColor(muted());
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
        if (request == REQUEST_MIC) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) listen();
            else Toast.makeText(this, R.string.voice_denied, Toast.LENGTH_LONG).show();
            return;
        }
        if (request == REQUEST_LIVE) {
            liveWanted = false;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) openLive();
            else Toast.makeText(this, R.string.voice_denied, Toast.LENGTH_LONG).show();
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
