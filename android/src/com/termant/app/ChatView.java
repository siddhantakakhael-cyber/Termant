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

import java.util.List;
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

        TextView past = button("\u2630", context.getString(R.string.history_title));
        past.setTextSize(18);
        past.setPadding(0, 0, 0, 0);
        past.setBackground(pressable(0x00000000, 18));
        past.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                showHistory();
            }
        });
        bar.addView(past, new LayoutParams(dp(40), dp(36)));

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

        TextView terminal = button(">_", context.getString(R.string.chat_terminal_description));
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

        // earlier chats stay within reach when a new one is started
        List<Chat.Summary> earlier = chat.history();
        if (earlier.isEmpty()) return;
        TextView heading = new TextView(c);
        heading.setText(R.string.history_recent);
        heading.setTextColor(MUTED);
        heading.setTextSize(13);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPadding(dp(8), dp(24), 0, 0);
        list.addView(heading);
        for (int i = 0; i < earlier.size() && i < 3; i++) list.addView(historyRow(earlier.get(i), null), rowSize());
        if (earlier.size() > 3) {
            TextView all = new TextView(c);
            all.setText(getResources().getString(R.string.history_all, earlier.size()));
            all.setTextColor(ACCENT);
            all.setTextSize(14);
            all.setPadding(dp(16), dp(12), dp(16), dp(12));
            all.setBackground(pressable(0x00000000, 14));
            all.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    showHistory();
                }
            });
            list.addView(all);
        }
    }

    // ------------------------------------------------------------ earlier chats

    private LayoutParams rowSize() {
        LayoutParams size = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        size.topMargin = dp(8);
        return size;
    }

    /** One earlier chat: tap to open it; with a dialog given, it also gets a delete button. */
    private View historyRow(final Chat.Summary s, final android.app.Dialog dialog) {
        Context c = getContext();
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(dialog == null ? 16 : 4), dp(10));
        row.setBackground(pressable(chat != null && s.id.equals(chat.id()) ? SURFACE_HIGH : SURFACE, 14));
        row.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (dialog != null) dialog.dismiss();
                if (chat != null) chat.open(s.id);
            }
        });
        LinearLayout text = new LinearLayout(c);
        text.setOrientation(VERTICAL);
        TextView title = new TextView(c);
        title.setText(s.title.isEmpty() ? c.getString(R.string.history_untitled) : s.title);
        title.setTextColor(TEXT);
        title.setTextSize(15);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(title);
        TextView when = new TextView(c);
        when.setText(android.text.format.DateUtils.getRelativeTimeSpanString(
                s.updated, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS));
        when.setTextColor(MUTED);
        when.setTextSize(12);
        text.addView(when);
        row.addView(text, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (dialog != null) {
            TextView delete = new TextView(c);
            delete.setText("\u2715");
            delete.setContentDescription(c.getString(R.string.history_delete));
            delete.setTextColor(MUTED);
            delete.setTextSize(15);
            delete.setGravity(Gravity.CENTER);
            delete.setBackground(pressable(0x00000000, 20));
            delete.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    if (chat != null) chat.delete(s.id);
                    showHistory();
                }
            });
            row.addView(delete, new LayoutParams(dp(40), dp(40)));
        }
        return row;
    }

    private void showHistory() {
        if (chat == null) return;
        Context c = getContext();
        List<Chat.Summary> all = chat.history();
        LinearLayout rows = new LinearLayout(c);
        rows.setOrientation(VERTICAL);
        rows.setPadding(dp(12), dp(4), dp(12), dp(12));
        ScrollView scroller = new ScrollView(c);
        scroller.addView(rows);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(c)
                .setTitle(R.string.history_title)
                .setView(scroller)
                .setNegativeButton(R.string.close, null)
                .create();
        if (all.isEmpty()) {
            TextView none = new TextView(c);
            none.setText(R.string.history_none);
            none.setTextColor(MUTED);
            none.setTextSize(15);
            none.setPadding(dp(12), dp(12), dp(12), dp(12));
            rows.addView(none);
        }
        for (Chat.Summary s : all) rows.addView(historyRow(s, dialog), rowSize());
        dialog.show();
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
            // Setup progress is shown as it is; otherwise the wait gets changing lines.
            String[] lines = !chat.status.isEmpty() ? new String[]{chat.status}
                    : getResources().getStringArray(m.steps.isEmpty() ? R.array.thinking : R.array.working);
            Thinking state = new Thinking(c, lines, chat.startedAt, density);
            LayoutParams stateSize = new LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (!m.text.isEmpty() || !m.note.isEmpty() || !m.steps.isEmpty()) stateSize.topMargin = dp(6);
            bubble.addView(state, stateSize);
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

    /**
     * The "working on it" indicator: three dots that ride a wave and change
     * colour, a line of text that changes every few seconds, and the seconds
     * waited. Everything is computed from the clock, so it keeps its place
     * when the chat is redrawn.
     */
    private static final class Thinking extends View {
        private static final int[] COLORS = {0xffff7a45, 0xffd2a8ff, 0xff79c0ff, 0xff56d364};
        private static final long LINE_MS = 2800, FADE_MS = 350;
        private final String[] lines;
        private final long startedAt;
        private final float density;
        private final android.graphics.Paint dot = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint text = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint time = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.animation.ArgbEvaluator blend = new android.animation.ArgbEvaluator();

        Thinking(Context c, String[] lines, long startedAt, float density) {
            super(c);
            this.lines = lines;
            this.startedAt = startedAt;
            this.density = density;
            text.setColor(TEXT);
            text.setTextSize(14 * getResources().getDisplayMetrics().scaledDensity);
            time.setColor(MUTED);
            time.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
        }

        private float dotsWidth() {
            return 46 * density;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            float widest = 0;
            for (String line : lines) widest = Math.max(widest, text.measureText(line));
            int wanted = (int) (dotsWidth() + widest + time.measureText("  \u00b7 999s") + 4 * density);
            setMeasuredDimension(resolveSize(wanted, widthSpec), resolveSize((int) (30 * density), heightSpec));
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            long now = android.os.SystemClock.elapsedRealtime();
            long waited = Math.max(0, now - startedAt);
            float middle = getHeight() / 2f;

            // dots: a wave runs through them while the colours drift
            for (int i = 0; i < 3; i++) {
                double phase = now / 260.0 - i * 0.9;
                float lift = (float) Math.sin(phase);
                float size = (3.4f + 1.2f * Math.max(0, lift)) * density;
                float drift = (now / 1400f + i * 0.33f) % COLORS.length;
                int from = (int) drift;
                dot.setColor((Integer) blend.evaluate(drift - from, COLORS[from], COLORS[(from + 1) % COLORS.length]));
                canvas.drawCircle((7 + i * 14) * density, middle - lift * 4.5f * density, size, dot);
            }

            // the line of text: the next one slides up as the old one fades
            int index = (int) (waited / LINE_MS % lines.length);
            long into = waited % LINE_MS;
            float appear = lines.length == 1 ? 1 : Math.min(1f, into / (float) FADE_MS);
            float leave = lines.length == 1 ? 0 : Math.max(0f, (into - (LINE_MS - FADE_MS)) / (float) FADE_MS);
            float alpha = Math.min(appear, 1 - leave);
            float baseline = middle - (text.descent() + text.ascent()) / 2 + (1 - appear) * 6 * density - leave * 6 * density;
            float room = getWidth() - dotsWidth();
            String line = lines[index];
            if (text.measureText(line) > room) {
                line = android.text.TextUtils.ellipsize(line, new android.text.TextPaint(text), room,
                        android.text.TextUtils.TruncateAt.END).toString();
            }
            text.setAlpha((int) (255 * alpha));
            canvas.drawText(line, dotsWidth(), baseline, text);
            if (waited >= 5000) {
                float x = dotsWidth() + text.measureText(line);
                if (x + time.measureText("  \u00b7 999s") <= getWidth() + 1) {
                    time.setAlpha((int) (255 * alpha));
                    canvas.drawText("  \u00b7 " + waited / 1000 + "s", x, baseline, time);
                }
            }
            postInvalidateOnAnimation();
        }
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
