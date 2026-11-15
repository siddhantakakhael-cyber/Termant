package com.termant.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The chat screen: messages, suggestions when empty, and the message box. */
final class ChatView extends LinearLayout implements Chat.Listener {
    interface Host {
        void onOpenTerminal();

        void onVoice();

        void onSettings();

        void onPickWorkspace();

        void onPickModel();

        void onOpenArena();
    }

    private static final int BG = MainActivity.BG, SURFACE = MainActivity.SURFACE,
            SURFACE_HIGH = MainActivity.SURFACE_HIGH, TEXT = MainActivity.TEXT, MUTED = MainActivity.MUTED,
            ACCENT = MainActivity.ACCENT, ON_ACCENT = MainActivity.ON_ACCENT, ERROR = MainActivity.ERROR;
    private static final int CODE = 0xff79c0ff;
    private static final Pattern INLINE = Pattern.compile("\\*\\*(.+?)\\*\\*|`([^`\n]+)`");

    private final Host host;
    private final float density;
    private final LinearLayout list;
    private final ScrollView scroll;
    private final EditText input;
    private final TextView send;
    private final TextView workspaceChip, modelChip;
    private Chat chat;

    ChatView(Context context, Host host) {
        super(context);
        this.host = host;
        density = getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setBackgroundColor(BG);

        // top bar
        LinearLayout bar = new LinearLayout(context);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(8), dp(8), dp(8));
        TextView title = new TextView(context);
        title.setText("\u2726 Termant");
        title.setTextColor(TEXT);
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView fresh = button("\uff0b", context.getString(R.string.chat_new_description));
        fresh.setTextSize(20);
        fresh.setPadding(0, 0, 0, 0);
        fresh.setBackground(pressable(0x00000000, 18));
        fresh.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (chat != null) chat.clear();
            }
        });
        bar.addView(fresh, new LayoutParams(dp(40), dp(36)));

        TextView gear = button("\u2699", context.getString(R.string.settings));
        gear.setTextSize(20);
        gear.setPadding(0, 0, 0, 0);
        gear.setBackground(pressable(0x00000000, 18));
        gear.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ChatView.this.host.onSettings();
            }
        });
        bar.addView(gear, new LayoutParams(dp(40), dp(36)));

        TextView terminal = button(">_ " + context.getString(R.string.chat_terminal),
                context.getString(R.string.chat_terminal_description));
        terminal.setTypeface(Typeface.DEFAULT_BOLD);
        terminal.setBackground(pressable(SURFACE_HIGH, 18));
        terminal.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ChatView.this.host.onOpenTerminal();
            }
        });
        LayoutParams terminalSize = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        terminalSize.leftMargin = dp(4);
        bar.addView(terminal, terminalSize);
        addView(bar);

        // messages
        list = new LinearLayout(context);
        list.setOrientation(VERTICAL);
        list.setPadding(dp(12), dp(8), dp(12), dp(8));
        scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.addView(list);
        addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // where the agent works and which model answers
        LinearLayout chips = new LinearLayout(context);
        chips.setPadding(dp(12), dp(2), dp(12), dp(2));
        workspaceChip = chip(context.getString(R.string.workspace_title));
        workspaceChip.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ChatView.this.host.onPickWorkspace();
            }
        });
        chips.addView(workspaceChip, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)));
        modelChip = chip(context.getString(R.string.model_title));
        modelChip.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ChatView.this.host.onPickModel();
            }
        });
        LayoutParams modelSize = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
        modelSize.leftMargin = dp(6);
        chips.addView(modelChip, modelSize);

        // Arena.ai has no way for other apps to use its agent, so this opens its website.
        TextView arena = chip(context.getString(R.string.arena_description));
        arena.setText("Arena \u2197");
        arena.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ChatView.this.host.onOpenArena();
            }
        });
        LayoutParams arenaSize = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
        arenaSize.leftMargin = dp(6);
        chips.addView(arena, arenaSize);

        android.widget.HorizontalScrollView chipScroll = new android.widget.HorizontalScrollView(context);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.addView(chips);
        addView(chipScroll);
        refreshChips();

        // message box
        LinearLayout pill = new LinearLayout(context);
        pill.setGravity(Gravity.BOTTOM);
        pill.setBackground(rounded(SURFACE, 26));
        pill.setPadding(dp(18), dp(4), dp(6), dp(4));
        input = new EditText(context);
        input.setHint(R.string.ask_hint);
        input.setHintTextColor(MUTED);
        input.setTextColor(TEXT);
        input.setTextSize(16);
        input.setBackground(null);
        input.setPadding(0, dp(10), 0, dp(10));
        input.setMaxLines(5);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        pill.addView(input, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView mic = button("\uD83C\uDFA4", context.getString(R.string.ask_voice));
        mic.setTextSize(17);
        mic.setPadding(0, 0, 0, 0);
        mic.setBackground(pressable(0x00000000, 21));
        mic.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ChatView.this.host.onVoice();
            }
        });
        pill.addView(mic, new LayoutParams(dp(42), dp(42)));

        send = button("\u2191", context.getString(R.string.ask_send));
        send.setTextSize(20);
        send.setPadding(0, 0, 0, 0);
        send.setTypeface(Typeface.DEFAULT_BOLD);
        send.setTextColor(ON_ACCENT);
        send.setBackground(pressable(ACCENT, 21));
        send.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (chat == null) return;
                if (chat.isBusy()) chat.stop();
                else submit(input.getText().toString());
            }
        });
        pill.addView(send, new LayoutParams(dp(42), dp(42)));

        FrameLayout wrap = new FrameLayout(context);
        wrap.setPadding(dp(10), dp(4), dp(10), dp(10));
        wrap.addView(pill, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(wrap);
    }

    void attach(Chat chat) {
        this.chat = chat;
        chat.setListener(this);
        onChatChanged();
    }

    void detach() {
        if (chat != null) chat.setListener(null);
        chat = null;
    }

    /** Shows the chosen folder and model under the messages. */
    void refreshChips() {
        workspaceChip.setText("\uD83D\uDCC1 " + Config.workspaceName(getContext()) + " \u25be");
        modelChip.setText("\u2726 " + Config.modelName(getContext()) + " \u25be");
    }

    private TextView chip(String description) {
        TextView v = button("", description);
        v.setTextSize(12);
        v.setTextColor(MUTED);
        v.setMaxWidth(dp(120));
        v.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        v.setPadding(dp(10), 0, dp(10), 0);
        v.setBackground(pressable(SURFACE, 15));
        return v;
    }

    /** Puts dictated text in the box, so it can be corrected before sending. */
    void setDraft(String text) {
        input.setText(text);
        input.setSelection(input.length());
        input.requestFocus();
    }

    private void submit(String text) {
        if (text.trim().isEmpty()) return;
        input.setText("");
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(getWindowToken(), 0);
        chat.send(text);
    }

    // ------------------------------------------------------------ drawing

    @Override
    public void onChatChanged() {
        if (chat == null) return;
        refreshChips(); // auto-switch may have moved to another model
        boolean busy = chat.isBusy();
        send.setText(busy ? "\u25a0" : "\u2191");
        send.setTextSize(busy ? 15 : 20);
        send.setContentDescription(getContext().getString(busy ? R.string.chat_stop : R.string.ask_send));

        list.removeAllViews();
        if (chat.messages.isEmpty()) {
            addWelcome();
            return;
        }
        int last = chat.messages.size() - 1;
        for (int i = 0; i <= last; i++) {
            Chat.Message m = chat.messages.get(i);
            if (m.user) addMine(m);
            else addReply(m, busy && i == last);
        }
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void addWelcome() {
        Context c = getContext();
        list.setGravity(Gravity.CENTER_VERTICAL);
        TextView hello = new TextView(c);
        hello.setText(R.string.chat_welcome);
        hello.setTextColor(TEXT);
        hello.setTextSize(26);
        hello.setTypeface(Typeface.DEFAULT_BOLD);
        hello.setPadding(dp(8), 0, dp(8), 0);
        list.addView(hello);
        TextView about = new TextView(c);
        about.setText(R.string.chat_welcome_text);
        about.setTextColor(MUTED);
        about.setTextSize(15);
        about.setPadding(dp(8), dp(8), dp(8), dp(20));
        list.addView(about);
        for (final String idea : getResources().getStringArray(R.array.chat_ideas)) {
            TextView chip = new TextView(c);
            chip.setText(idea);
            chip.setTextColor(TEXT);
            chip.setTextSize(15);
            chip.setPadding(dp(16), dp(13), dp(16), dp(13));
            chip.setBackground(pressable(SURFACE, 14));
            chip.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (chat != null && !chat.isBusy()) chat.send(idea);
                }
            });
            LayoutParams size = new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            size.topMargin = dp(8);
            list.addView(chip, size);
        }
    }

    private void addMine(Chat.Message m) {
        list.setGravity(Gravity.TOP);
        TextView bubble = new TextView(getContext());
        bubble.setText(m.text);
        bubble.setTextColor(ON_ACCENT);
        bubble.setTextSize(16);
        bubble.setTextIsSelectable(true);
        bubble.setPadding(dp(14), dp(10), dp(14), dp(10));
        bubble.setBackground(rounded(ACCENT, 18));
        LayoutParams size = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        size.gravity = Gravity.END;
        size.topMargin = dp(14);
        size.leftMargin = dp(48);
        list.addView(bubble, size);
    }

    private void addReply(Chat.Message m, boolean working) {
        Context c = getContext();
        LinearLayout bubble = new LinearLayout(c);
        bubble.setOrientation(VERTICAL);
        bubble.setPadding(dp(14), dp(10), dp(14), dp(10));
        bubble.setBackground(rounded(SURFACE, 18));

        if (!m.steps.isEmpty()) {
            // what the agent did, so the answer can be checked
            SpannableStringBuilder steps = new SpannableStringBuilder();
            for (String step : m.steps) {
                if (steps.length() > 0) steps.append('\n');
                steps.append("\u203a ").append(step.length() > 160 ? step.substring(0, 159) + "\u2026" : step);
            }
            TextView did = new TextView(c);
            did.setText(steps);
            did.setTypeface(Typeface.MONOSPACE);
            did.setTextSize(12);
            did.setTextColor(MUTED);
            did.setTextIsSelectable(true);
            did.setPadding(0, 0, 0, m.text.isEmpty() && !working ? 0 : dp(8));
            bubble.addView(did);
        }
        if (!m.text.isEmpty()) {
            TextView text = new TextView(c);
            text.setText(format(m.text));
            text.setTextColor(m.failed ? ERROR : TEXT);
            text.setTextSize(16);
            text.setLineSpacing(dp(2), 1);
            text.setTextIsSelectable(true);
            bubble.addView(text);
        }
        if (!m.note.isEmpty()) {
            TextView note = new TextView(c);
            note.setText("\u21bb " + m.note);
            note.setTextColor(MUTED);
            note.setTextSize(12);
            if (!m.text.isEmpty() || !m.steps.isEmpty()) note.setPadding(0, dp(8), 0, 0);
            bubble.addView(note);
        }
        if (working) {
            TextView state = new TextView(c);
            String status = chat.status;
            state.setText(!status.isEmpty() ? status
                    : c.getString(m.text.isEmpty() ? R.string.chat_thinking : R.string.chat_working));
            state.setTextColor(MUTED);
            state.setTextSize(14);
            state.setMaxLines(2);
            if (!m.text.isEmpty() || !m.note.isEmpty()) state.setPadding(0, dp(6), 0, 0);
            bubble.addView(state);
        }
        LayoutParams size = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        size.gravity = Gravity.START;
        size.topMargin = dp(14);
        size.rightMargin = dp(32);
        list.addView(bubble, size);
    }

    /** The little Markdown that models use in short answers: **bold**, `code` and ``` blocks. */
    static CharSequence format(String text) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        boolean code = false;
        for (String line : text.split("\n", -1)) {
            if (line.trim().startsWith("```")) {
                code = !code;
                continue;
            }
            if (out.length() > 0) out.append('\n');
            int start = out.length();
            if (code) {
                out.append(line);
                mono(out, start);
                continue;
            }
            Matcher m = INLINE.matcher(line);
            int at = 0;
            while (m.find()) {
                out.append(line, at, m.start());
                int from = out.length();
                if (m.group(1) != null) {
                    out.append(m.group(1));
                    out.setSpan(new StyleSpan(Typeface.BOLD), from, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else {
                    out.append(m.group(2));
                    mono(out, from);
                }
                at = m.end();
            }
            out.append(line, at, line.length());
        }
        return out;
    }

    private static void mono(SpannableStringBuilder out, int from) {
        out.setSpan(new TypefaceSpan("monospace"), from, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(CODE), from, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    // ------------------------------------------------------------ helpers

    private TextView button(String text, String description) {
        TextView v = new TextView(getContext());
        v.setText(text);
        v.setContentDescription(description);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(TEXT);
        v.setTextSize(14);
        v.setSingleLine(true);
        v.setClickable(true);
        v.setPadding(dp(14), 0, dp(14), 0);
        return v;
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
