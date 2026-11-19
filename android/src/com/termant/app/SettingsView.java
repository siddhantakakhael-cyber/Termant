package com.termant.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Settings: model, workspace, the user's own API keys, and the automation rules. */
final class SettingsView extends LinearLayout implements Rules.Listener {
    private final ScrollView scroll;
    interface Host {
        void onCloseSettings();

        void onPickModel();

        void onPickWorkspace();

        void onAskSms();

        void onOpenArena();

        void onPickBackups();

        /** The language or the theme was changed: every screen has to be built again. */
        void onLookChanged();

        /** Opens Android's Accessibility settings, where Screen control is turned on. */
        void onOpenScreenSettings();

        void onOpenLink(String url);

        /** Opens Android's "display over other apps" page for Termant. */
        void onOpenOverlaySettings();
    }

    private final int BG = Theme.bg(), SURFACE = Theme.surface(),
            SURFACE_HIGH = Theme.surfaceHigh(), TEXT = Theme.text(), MUTED = Theme.muted(),
            ACCENT = Theme.accent(), ERROR = Theme.error();

    private final Host host;
    private final float density;
    private final LinearLayout content;
    private Rules rules;

    SettingsView(Context context, Host host) {
        super(context);
        this.host = host;
        density = getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setBackgroundColor(BG);

        LinearLayout bar = new LinearLayout(context);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(8), dp(8), dp(8));
        TextView back = new TextView(context);
        back.setText("\u2190");
        back.setContentDescription(context.getString(R.string.chat_back_description));
        back.setTextColor(TEXT);
        back.setTextSize(22);
        back.setGravity(Gravity.CENTER);
        back.setBackground(pressable(0x00000000, 20));
        back.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                SettingsView.this.host.onCloseSettings();
            }
        });
        bar.addView(back, new LayoutParams(dp(44), dp(40)));
        TextView title = new TextView(context);
        title.setText(R.string.settings);
        title.setTextColor(TEXT);
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(6), 0, 0, 0);
        bar.addView(title);
        addView(bar);

        content = new LinearLayout(context);
        content.setOrientation(VERTICAL);
        content.setPadding(dp(14), 0, dp(14), dp(24));
        scroll = new ScrollView(context);
        scroll.addView(content);
        addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    }

    void attach(Rules rules) {
        this.rules = rules;
        rules.setListener(this);
        refresh();
    }

    void detach() {
        if (rules != null) rules.setListener(null);
        rules = null;
    }

    @Override
    public void onRulesChanged() {
        refresh();
    }

    /** Settings open at their start, not where they were left the last time. */
    void toTop() {
        scroll.scrollTo(0, 0);
    }

    void refresh() {
        final Context c = getContext();
        content.removeAllViews();

        heading(R.string.settings_look, 0);
        final boolean hindi = Lang.HINDI.equals(Lang.current(c));
        row(c.getString(R.string.lang_title), c.getString(hindi ? R.string.lang_hindi : R.string.lang_english),
                c.getString(R.string.lang_note), new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Lang.set(c, hindi ? Lang.ENGLISH : Lang.HINDI);
                        host.onLookChanged();
                    }
                });
        final boolean light = Theme.currentName(c).equals("light");
        row(c.getString(R.string.theme_title), c.getString(light ? R.string.theme_light : R.string.theme_dark),
                c.getString(R.string.theme_note), new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Theme.set(c, light ? "dark" : "light");
                        host.onLookChanged();
                    }
                });

        heading(R.string.settings_agent, 0);
        row(c.getString(R.string.model_title), modelText(c), c.getString(R.string.settings_model_note),
                new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        host.onPickModel();
                    }
                });
        row(c.getString(R.string.workspace_title), Config.workspaceName(c),
                c.getString(R.string.settings_workspace_note), new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        host.onPickWorkspace();
                    }
                });

        row(c.getString(R.string.switch_title), c.getString(Config.autoSwitch(c) ? R.string.on : R.string.off),
                c.getString(R.string.switch_note), new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Config.setAutoSwitch(c, !Config.autoSwitch(c));
                        refresh();
                    }
                });
        if (Config.autoSwitch(c)) {
            int chosen = Config.chosenBackups(c).size();
            row(c.getString(R.string.backups_title),
                    chosen == 0 ? c.getString(R.string.backups_all_free) : String.valueOf(chosen),
                    c.getString(R.string.backups_note), new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            host.onPickBackups();
                        }
                    });
        }
        row(c.getString(R.string.arena_title), "\u2197", c.getString(R.string.arena_note), new OnClickListener() {
            @Override
            public void onClick(View v) {
                host.onOpenArena();
            }
        });

        heading(R.string.settings_tools, R.string.settings_tools_note);
        final Map<String, String> keys = Config.keys(c);
        final String account = Config.githubUser(c);
        row(c.getString(R.string.github_title),
                account.isEmpty() ? c.getString(R.string.github_off)
                        : account.equals("?") ? c.getString(R.string.github_unchecked) : "@" + account,
                c.getString(R.string.github_note), new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        editGithub(!account.isEmpty());
                    }
                });
        row(c.getString(R.string.search_title), c.getString(Config.webSearch(c) ? R.string.on : R.string.off),
                c.getString(R.string.search_note), new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Config.setWebSearch(c, !Config.webSearch(c));
                        Config.writeOpencodeConfig(c);
                        refresh();
                    }
                });
        if (Config.webSearch(c)) {
            for (final String[] service : Config.SEARCH_KEYS) {
                final String key = keys.get(service[1]);
                row(c.getString(R.string.search_key, service[0]),
                        key == null ? c.getString(R.string.search_key_none) : mask(key), null, new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                editKey(new String[]{c.getString(R.string.search_key, service[0]), service[1]},
                                        key != null);
                            }
                        });
            }
        }
        final boolean screenOn = ScreenService.isOn(c);
        row(c.getString(R.string.screen_title), c.getString(screenOn ? R.string.on : R.string.off),
                c.getString(R.string.screen_note), new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        askScreenControl(screenOn);
                    }
                });

        // The card over other apps: Screen control brings it along; without, Android has to allow it.
        final boolean overApps = Overlay.allowed(c);
        row(c.getString(R.string.overlay_title),
                c.getString(overApps ? R.string.on : screenOn ? R.string.overlay_with_screen : R.string.off),
                c.getString(R.string.overlay_note), new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        host.onOpenOverlaySettings();
                    }
                });

        heading(R.string.settings_keys, R.string.settings_keys_note);
        for (final String[] provider : Config.PROVIDERS) {
            final String key = keys.get(provider[1]);
            row(provider[0], key == null ? c.getString(R.string.key_not_set) : mask(key), null,
                    new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            editKey(provider, key != null);
                        }
                    });
        }

        heading(R.string.mcp_title, R.string.mcp_note);
        Map<String, String> servers = Config.mcpServers(c);
        if (servers.isEmpty()) {
            TextView none = new TextView(c);
            none.setText(R.string.mcp_none);
            none.setTextColor(MUTED);
            none.setTextSize(15);
            none.setPadding(dp(4), dp(4), 0, dp(4));
            content.addView(none);
        }
        for (final Map.Entry<String, String> e : servers.entrySet()) {
            content.addView(mcpCard(e.getKey(), e.getValue()), cardSize());
        }
        row(c.getString(R.string.mcp_add), "+", null, new OnClickListener() {
            @Override
            public void onClick(View v) {
                addMcpServer(null, null);
            }
        });

        heading(R.string.settings_rules, R.string.settings_rules_note);
        if (rules == null || rules.rules.isEmpty()) {
            TextView none = new TextView(c);
            none.setText(R.string.rules_none);
            none.setTextColor(MUTED);
            none.setTextSize(15);
            none.setPadding(dp(4), dp(4), 0, dp(4));
            content.addView(none);
        } else {
            for (Rules.Rule r : rules.rules) content.addView(ruleCard(r), cardSize());
        }
        if (c.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED
                && c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            row(c.getString(R.string.sms_allow), "", c.getString(R.string.sms_note), new OnClickListener() {
                @Override
                public void onClick(View v) {
                    host.onAskSms();
                }
            });
        }

        // The agent writes first: at these times of day, and when it planned to come back to something.
        heading(R.string.settings_care, R.string.settings_care_note);
        final TermService running = TermService.get();
        final Care care = running == null ? null : running.care;
        final boolean caring = Care.on(c);
        row(c.getString(R.string.care_title), c.getString(caring ? R.string.on : R.string.off), null,
                new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (care != null) care.setOn(!caring);
                        refresh();
                    }
                });
        if (caring && care != null) {
            String times = Care.timesText(c).trim().replace(" ", ", ");
            row(c.getString(R.string.care_times), times.isEmpty() ? c.getString(R.string.care_times_none) : times,
                    c.getString(R.string.care_times_note), new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            editCareTimes(care);
                        }
                    });
            row(c.getString(R.string.care_now), "", c.getString(R.string.care_now_note), new OnClickListener() {
                @Override
                public void onClick(View v) {
                    Toast.makeText(c, care.now() ? R.string.care_now_started : R.string.care_now_busy,
                            Toast.LENGTH_LONG).show();
                }
            });
            List<Care.FollowUp> planned = care.planned();
            if (!planned.isEmpty()) label(R.string.care_planned);
            for (final Care.FollowUp f : planned) {
                content.addView(learnedCard(f.topic, Care.when(f.when), c.getString(R.string.care_drop, f.id), new Runnable() {
                    @Override
                    public void run() {
                        if (care.followUps.contains(f)) care.remove(f);
                        refresh();
                    }
                }), cardSize());
            }
        }

        // What the agent keeps across chats: its notes and the tools it made.
        heading(R.string.settings_learn, R.string.settings_learn_note);
        final boolean learning = Learned.on(c);
        row(c.getString(R.string.learn_title), c.getString(learning ? R.string.on : R.string.off), null,
                new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            Learned.setOn(c, !learning);
                        } catch (Exception e) {
                            Toast.makeText(c, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
                        }
                        refresh();
                    }
                });
        label(R.string.learn_notes);
        final List<String> notes = Learned.notes(c);
        if (notes.isEmpty()) label(R.string.learn_notes_none);
        for (int i = 0; i < notes.size(); i++) {
            final int at = i;
            content.addView(learnedCard(notes.get(i), null, c.getString(R.string.learn_forget, i + 1), new Runnable() {
                @Override
                public void run() {
                    try {
                        // only if the list is still the one shown: the agent may have changed it meanwhile
                        if (Learned.notes(c).equals(notes)) Learned.forget(c, at);
                    } catch (Exception e) {
                        Toast.makeText(c, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
                    }
                    refresh();
                }
            }), cardSize());
        }
        label(R.string.learn_tools);
        List<String[]> tools = Learned.tools(c);
        if (tools.isEmpty()) label(R.string.learn_tools_none);
        for (final String[] tool : tools) {
            content.addView(learnedCard(tool[1], tool[0], c.getString(R.string.learn_drop, tool[0]), new Runnable() {
                @Override
                public void run() {
                    try {
                        Learned.drop(c, tool[0]);
                    } catch (Exception e) {
                        Toast.makeText(c, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
                    }
                    refresh();
                }
            }), cardSize());
        }
    }

    /** A small line of text inside a section. */
    private void label(int text) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextColor(MUTED);
        t.setTextSize(13);
        t.setPadding(dp(4), dp(10), dp(4), dp(2));
        content.addView(t);
    }

    /** One thing the agent learned: its text, the command name for a tool, and a button that removes it. */
    private View learnedCard(String text, String command, String removeLabel, final Runnable remove) {
        Context c = getContext();
        LinearLayout card = new LinearLayout(c);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(10), dp(6), dp(10));
        card.setBackground(rounded(SURFACE, 14));

        LinearLayout lines = new LinearLayout(c);
        lines.setOrientation(VERTICAL);
        if (command != null) {
            TextView name = new TextView(c);
            name.setText(command);
            name.setTypeface(Typeface.MONOSPACE);
            name.setTextColor(TEXT);
            name.setTextSize(15);
            lines.addView(name);
        }
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(command == null ? TEXT : MUTED);
        t.setTextSize(command == null ? 15 : 13);
        lines.addView(t);
        card.addView(lines, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView delete = new TextView(c);
        delete.setText("\u2715");
        delete.setContentDescription(removeLabel);
        delete.setTextColor(MUTED);
        delete.setTextSize(16);
        delete.setGravity(Gravity.CENTER);
        delete.setBackground(pressable(0x00000000, 20));
        delete.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                remove.run();
            }
        });
        card.addView(delete, new LayoutParams(dp(40), dp(40)));
        return card;
    }

    private void editCareTimes(final Care care) {
        final Context c = getContext();
        final EditText box = new EditText(c);
        box.setHint(R.string.care_times_hint);
        box.setSingleLine(true);
        box.setText(Care.timesText(c).trim().replace(" ", ", "));
        box.setSelection(box.length());
        FrameLayout wrap = new FrameLayout(c);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(box);
        new AlertDialog.Builder(c)
                .setTitle(R.string.care_times)
                .setMessage(R.string.care_times_help)
                .setView(wrap)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (!care.setTimes(box.getText().toString())) {
                            Toast.makeText(c, R.string.care_times_wrong, Toast.LENGTH_LONG).show();
                        }
                        refresh();
                    }
                })
                .show();
    }

    private static String modelText(Context c) {
        String m = Config.model(c);
        String active = Config.activeModel(c);
        if (!active.equals(m)) return c.getString(R.string.model_switched_now, active);
        return m.isEmpty() ? c.getString(R.string.model_auto_long) : m;
    }

    private static String mask(String key) {
        return "\u2022\u2022\u2022\u2022 " + (key.length() > 8 ? key.substring(key.length() - 4) : "");
    }

    private void editKey(final String[] provider, boolean exists) {
        final Context c = getContext();
        final EditText box = new EditText(c);
        box.setHint(R.string.key_hint);
        box.setSingleLine(true);
        box.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        FrameLayout wrap = new FrameLayout(c);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(box);
        AlertDialog.Builder b = new AlertDialog.Builder(c)
                .setTitle(provider[0])
                .setView(wrap)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String value = box.getText().toString().trim();
                        if (value.isEmpty()) return;
                        saveKey(provider[1], value);
                    }
                });
        if (exists) {
            b.setNeutralButton(R.string.key_remove, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    saveKey(provider[1], "");
                }
            });
        }
        b.show();
    }

    /** Explains screen control before sending the user to Android's settings, where it is turned on or off. */
    private void askScreenControl(boolean on) {
        final Context c = getContext();
        AlertDialog.Builder b = new AlertDialog.Builder(c)
                .setTitle(R.string.screen_title)
                .setMessage(on ? R.string.screen_on_text : R.string.screen_ask_text)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.screen_open, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        host.onOpenScreenSettings();
                    }
                });
        if (on && ScreenService.get() != null) {
            b.setNeutralButton(R.string.screen_stop, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    ScreenService running = ScreenService.get();
                    if (running != null) running.turnOff();
                    postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            refresh();
                        }
                    }, 400);
                }
            });
        }
        b.show();
    }

    private static final String GITHUB_TOKEN_PAGE =
            "https://github.com/settings/tokens/new?description=Termant&scopes=repo,workflow,gist,read:org";

    private void editGithub(boolean connected) {
        final Context c = getContext();
        final EditText box = new EditText(c);
        box.setHint(R.string.github_hint);
        box.setSingleLine(true);
        box.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(VERTICAL);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        TextView how = new TextView(c);
        how.setText(R.string.github_how);
        how.setTextColor(MUTED);
        how.setTextSize(14);
        wrap.addView(how);
        TextView link = new TextView(c);
        link.setText(R.string.github_make);
        link.setTextColor(ACCENT);
        link.setTextSize(15);
        link.setTypeface(Typeface.DEFAULT_BOLD);
        link.setPadding(0, dp(10), 0, dp(6));
        link.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                host.onOpenLink(GITHUB_TOKEN_PAGE);
            }
        });
        wrap.addView(link);
        wrap.addView(box);
        AlertDialog.Builder b = new AlertDialog.Builder(c)
                .setTitle(R.string.github_title)
                .setView(wrap)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String token = box.getText().toString().trim();
                        if (!token.isEmpty()) checkGithub(token);
                    }
                });
        if (connected) {
            b.setNeutralButton(R.string.github_remove, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    saveGithub("", "", "", R.string.github_removed);
                }
            });
        }
        b.show();
    }

    /** Asks GitHub whose token this is, so that a mistyped one is noticed now and not at the first push. */
    private void checkGithub(final String token) {
        final Context c = getContext();
        if (!token.matches("[\\x21-\\x7e]+") || token.contains("'")) {
            Toast.makeText(c, R.string.key_bad, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(c, R.string.github_checking, Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                int code = 0;
                String login = "", email = "";
                java.net.HttpURLConnection http = null;
                try {
                    http = (java.net.HttpURLConnection) new java.net.URL("https://api.github.com/user").openConnection();
                    http.setConnectTimeout(12000);
                    http.setReadTimeout(12000);
                    http.setRequestProperty("Authorization", "Bearer " + token);
                    http.setRequestProperty("Accept", "application/vnd.github+json");
                    http.setRequestProperty("User-Agent", "Termant");
                    code = http.getResponseCode();
                    if (code == 200) {
                        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                        try (java.io.InputStream in = http.getInputStream()) {
                            byte[] buf = new byte[4096];
                            int n;
                            while ((n = in.read(buf)) > 0 && out.size() < 200000) out.write(buf, 0, n);
                        }
                        org.json.JSONObject user = new org.json.JSONObject(out.toString("UTF-8"));
                        login = user.optString("login", "");
                        if (!login.matches("[A-Za-z0-9-]+")) login = "";
                        long id = user.optLong("id", 0);
                        if (!login.isEmpty()) email = (id > 0 ? id + "+" : "") + login + "@users.noreply.github.com";
                    }
                } catch (Exception e) {
                    code = 0; // no internet: keep the token, unchecked
                } finally {
                    if (http != null) http.disconnect();
                }
                final int result = code;
                final String who = login, mail = email;
                post(new Runnable() {
                    @Override
                    public void run() {
                        android.util.Log.i("Termant", "github: token check gave " + result);
                        if (result == 401) {
                            Toast.makeText(c, R.string.github_rejected, Toast.LENGTH_LONG).show();
                        } else if (who.isEmpty()) {
                            saveGithub(token, "", "", R.string.github_saved_unchecked);
                        } else {
                            saveGithub(token, who, mail, 0);
                            Toast.makeText(c, c.getString(R.string.github_saved, who), Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }, "github-check").start();
    }

    private void saveGithub(String token, String login, String email, int message) {
        Context c = getContext();
        try {
            if (!Config.setGithub(c, token, login, email)) {
                Toast.makeText(c, R.string.key_bad, Toast.LENGTH_LONG).show();
                return;
            }
            if (message != 0) Toast.makeText(c, message, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(c, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
        }
        refresh();
    }

    private void saveKey(String name, String value) {
        Context c = getContext();
        try {
            if (!Config.setKey(c, name, value)) {
                Toast.makeText(c, R.string.key_bad, Toast.LENGTH_LONG).show();
                return;
            }
            Config.writeOpencodeConfig(c); // a search key changes which search service is used
            if (!value.isEmpty()) Toast.makeText(c, R.string.key_saved, Toast.LENGTH_LONG).show();
            // A model key brings new models: fetch the list now, so that auto-switch can move to them.
            boolean modelKey = false;
            for (String[] provider : Config.PROVIDERS) modelKey |= provider[1].equals(name);
            TermService service = TermService.get();
            if (modelKey && !value.isEmpty() && service != null && service.chat != null) service.chat.refreshModels();
        } catch (Exception e) {
            Toast.makeText(c, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
        }
        refresh();
    }

    private void addMcpServer(final String existingName, final String existingCommand) {
        final Context c = getContext();
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(VERTICAL);
        wrap.setPadding(dp(12), dp(8), dp(12), 0);
        final EditText nameBox = new EditText(c);
        nameBox.setHint(R.string.mcp_name_hint);
        nameBox.setSingleLine(true);
        if (existingName != null) nameBox.setText(existingName);
        final EditText cmdBox = new EditText(c);
        cmdBox.setHint(R.string.mcp_command_hint);
        cmdBox.setSingleLine(true);
        if (existingCommand != null) cmdBox.setText(existingCommand);
        wrap.addView(nameBox);
        wrap.addView(cmdBox);
        new AlertDialog.Builder(c)
                .setTitle(existingName == null ? R.string.mcp_add : R.string.mcp_edit)
                .setView(wrap)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String name = nameBox.getText().toString().trim();
                        String command = cmdBox.getText().toString().trim();
                        if (name.isEmpty() || command.isEmpty()) {
                            Toast.makeText(c, R.string.mcp_bad, Toast.LENGTH_LONG).show();
                            return;
                        }
                        Map<String, String> servers = new LinkedHashMap<>(Config.mcpServers(c));
                        if (existingName != null && !existingName.equals(name)) servers.remove(existingName);
                        servers.put(name, command);
                        Config.setMcpServers(c, servers);
                        Config.writeOpencodeConfig(c);
                        refresh();
                        Toast.makeText(c, R.string.mcp_saved, Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private View mcpCard(final String name, final String command) {
        final Context c = getContext();
        LinearLayout card = new LinearLayout(c);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(12), dp(6), dp(12));
        card.setBackground(rounded(SURFACE, 14));

        LinearLayout text = new LinearLayout(c);
        text.setOrientation(VERTICAL);
        TextView n = new TextView(c);
        n.setText(name);
        n.setTextColor(TEXT);
        n.setTextSize(16);
        text.addView(n);
        TextView cmd = new TextView(c);
        cmd.setText(command);
        cmd.setTypeface(Typeface.MONOSPACE);
        cmd.setTextColor(MUTED);
        cmd.setTextSize(12);
        cmd.setMaxLines(2);
        cmd.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(cmd);
        card.addView(text, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView delete = new TextView(c);
        delete.setText("\u2715");
        delete.setContentDescription(c.getString(R.string.mcp_remove));
        delete.setTextColor(MUTED);
        delete.setTextSize(16);
        delete.setGravity(Gravity.CENTER);
        delete.setBackground(pressable(0x00000000, 20));
        delete.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Map<String, String> servers = new LinkedHashMap<>(Config.mcpServers(c));
                servers.remove(name);
                Config.setMcpServers(c, servers);
                Config.writeOpencodeConfig(c);
                refresh();
                Toast.makeText(c, R.string.mcp_saved, Toast.LENGTH_LONG).show();
            }
        });
        card.addView(delete, new LayoutParams(dp(40), dp(40)));
        return card;
    }

    // ------------------------------------------------------------ pieces

    private void heading(int title, int note) {
        Context c = getContext();
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(ACCENT);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(4), dp(22), 0, dp(4));
        content.addView(t);
        if (note != 0) {
            TextView n = new TextView(c);
            n.setText(note);
            n.setTextColor(MUTED);
            n.setTextSize(13);
            n.setPadding(dp(4), 0, dp(4), dp(6));
            content.addView(n);
        }
    }

    private LayoutParams cardSize() {
        LayoutParams size = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        size.topMargin = dp(6);
        return size;
    }

    private void row(String title, String value, String note, OnClickListener click) {
        Context c = getContext();
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.setBackground(pressable(SURFACE, 14));
        card.setContentDescription(title);
        card.setOnClickListener(click);

        LinearLayout line = new LinearLayout(c);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(TEXT);
        t.setTextSize(16);
        line.addView(t, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView v = new TextView(c);
        v.setText(value);
        v.setTextColor(MUTED);
        v.setTextSize(14);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.START);
        v.setGravity(Gravity.END);
        v.setMaxWidth(dp(170));
        v.setPadding(dp(10), 0, 0, 0);
        line.addView(v);
        card.addView(line);
        if (note != null) {
            TextView n = new TextView(c);
            n.setText(note);
            n.setTextColor(MUTED);
            n.setTextSize(13);
            n.setPadding(0, dp(2), 0, 0);
            card.addView(n);
        }
        content.addView(card, cardSize());
    }

    private View ruleCard(final Rules.Rule r) {
        Context c = getContext();
        LinearLayout card = new LinearLayout(c);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(12), dp(6), dp(12));
        card.setBackground(rounded(SURFACE, 14));

        LinearLayout text = new LinearLayout(c);
        text.setOrientation(VERTICAL);
        TextView when = new TextView(c);
        when.setText(Rules.describe(r.trigger));
        when.setTextColor(TEXT);
        when.setTextSize(16);
        text.addView(when);
        TextView what = new TextView(c);
        what.setText(r.command);
        what.setTypeface(Typeface.MONOSPACE);
        what.setTextColor(MUTED);
        what.setTextSize(12);
        what.setMaxLines(3);
        what.setPadding(0, dp(3), 0, dp(3));
        text.addView(what);
        TextView last = new TextView(c);
        if (r.lastRun == 0) {
            last.setText(R.string.rule_never);
        } else {
            String at = new SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(r.lastRun);
            last.setText(c.getString(r.lastExit == 0 ? R.string.rule_last_ok : R.string.rule_last_failed, at));
        }
        last.setTextColor(r.lastRun != 0 && r.lastExit != 0 ? ERROR : MUTED);
        last.setTextSize(12);
        text.addView(last);
        card.addView(text, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Switch on = new Switch(c);
        on.setChecked(r.enabled);
        on.setContentDescription(Rules.describe(r.trigger));
        on.setThumbTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}}, new int[]{ACCENT, MUTED}));
        on.setTrackTintList(ColorStateList.valueOf(SURFACE_HIGH));
        on.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                if (rules != null && checked != r.enabled) rules.setEnabled(r, checked);
            }
        });
        card.addView(on);

        TextView delete = new TextView(c);
        delete.setText("\u2715");
        delete.setContentDescription(c.getString(R.string.rule_delete));
        delete.setTextColor(MUTED);
        delete.setTextSize(16);
        delete.setGravity(Gravity.CENTER);
        delete.setBackground(pressable(0x00000000, 20));
        delete.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (rules != null) rules.remove(r);
            }
        });
        card.addView(delete, new LayoutParams(dp(40), dp(40)));
        return card;
    }

    private Drawable rounded(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private Drawable pressable(int color, float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(Theme.ripple()),
                rounded(color, radiusDp), rounded(0xffffffff, radiusDp));
    }

    private int dp(float value) {
        return Math.round(value * density);
    }
}
