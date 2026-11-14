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
import java.util.Locale;
import java.util.Map;

/** Settings: model, workspace, the user's own API keys, and the automation rules. */
final class SettingsView extends LinearLayout implements Rules.Listener {
    interface Host {
        void onCloseSettings();

        void onPickModel();

        void onPickWorkspace();

        void onAskSms();
    }

    private static final int BG = MainActivity.BG, SURFACE = MainActivity.SURFACE,
            SURFACE_HIGH = MainActivity.SURFACE_HIGH, TEXT = MainActivity.TEXT, MUTED = MainActivity.MUTED,
            ACCENT = MainActivity.ACCENT, ERROR = MainActivity.ERROR;

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
        ScrollView scroll = new ScrollView(context);
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

    void refresh() {
        final Context c = getContext();
        content.removeAllViews();

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

        heading(R.string.settings_keys, R.string.settings_keys_note);
        Map<String, String> keys = Config.keys(c);
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
    }

    private static String modelText(Context c) {
        String m = Config.model(c);
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

    private void saveKey(String name, String value) {
        Context c = getContext();
        try {
            if (!Config.setKey(c, name, value)) {
                Toast.makeText(c, R.string.key_bad, Toast.LENGTH_LONG).show();
                return;
            }
            if (!value.isEmpty()) Toast.makeText(c, R.string.key_saved, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(c, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
        }
        refresh();
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
        return new RippleDrawable(ColorStateList.valueOf(0x40ffffff),
                rounded(color, radiusDp), rounded(0xffffffff, radiusDp));
    }

    private int dp(float value) {
        return Math.round(value * density);
    }
}
