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

    private final int BG = Theme.bg(), SURFACE = Theme.surface(),
            SURFACE_HIGH = Theme.surfaceHigh(), TEXT = Theme.text(), MUTED = Theme.muted(),
            ACCENT = Theme.accent(), ON_ACCENT = Theme.onAccent(), ERROR = Theme.error();
    private static final Pattern INLINE = Pattern.compile("\\*\\*(.+?)\\*\\*|`([^`\n]+)`");

    private final Host host;
    private final float density;
    private final LinearLayout list;
    private final ScrollView scroll;
    private final EditText input;
    private final TextView send;
    private final TextView workspaceChip, modelChip;
    private final TextView mic;
    private Chat chat;
    /** The text of the reply being written, and what the list looked like when it was built. */
    private TextView writing;
    private String shape = "";

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

        mic = button("\uD83C\uDFA4", context.getString(R.string.ask_voice));
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

    String draft() {
        return input.getText().toString();
    }

    /** Shows that the microphone is listening: the button lights up and the box says so. */
    void setListening(boolean listening) {
        Context c = getContext();
        mic.setBackground(pressable(listening ? ACCENT : 0x00000000, 21));
        mic.setContentDescription(c.getString(listening ? R.string.voice_stop : R.string.ask_voice));
        input.setHint(listening ? R.string.voice_listening : R.string.ask_hint);
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

        int last = chat.messages.size() - 1;
        // While a reply is being written only its words change, many times a
        // second: put them in the bubble that is there instead of rebuilding
        // the whole list, and follow them only if the reader is at the end.
        String now = shape(busy);
        if (busy && writing != null && now.equals(shape)) {
            boolean atEnd = scroll.getScrollY() + scroll.getHeight() >= list.getBottom() - dp(96);
            writing.setText(format(chat.messages.get(last).text));
            if (atEnd) {
                scroll.post(new Runnable() {
                    @Override
                    public void run() {
                        scroll.scrollTo(0, Math.max(0, list.getBottom() - scroll.getHeight()));
                    }
                });
            }
            return;
        }
        shape = now;
        writing = null;

        list.removeAllViews();
        if (chat.messages.isEmpty()) {
            addWelcome();
            return;
        }
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

    /** Everything about the list except the words of the last reply; the list is rebuilt when this changes. */
    private String shape(boolean busy) {
        int last = chat.messages.size() - 1;
        if (last < 0) return "";
        Chat.Message m = chat.messages.get(last);
        return last + "|" + busy + "|" + m.user + "|" + m.failed + "|" + m.text.isEmpty() + "|" + m.note
                + "|" + chat.status + "|" + m.steps;
    }

    private void addWelcome() {
        Context c = getContext();
        List<Chat.Summary> earlier = chat.history();
        list.setGravity(earlier.isEmpty() ? Gravity.CENTER_VERTICAL : Gravity.TOP);
        TextView hello = new TextView(c);
        hello.setText(R.string.chat_welcome);
        hello.setTextColor(TEXT);
        hello.setTextSize(earlier.isEmpty() ? 26 : 22);
        hello.setTypeface(Typeface.DEFAULT_BOLD);
        hello.setPadding(dp(8), dp(8), dp(8), 0);
        list.addView(hello);
        if (earlier.isEmpty()) {
            // first time: say what this is
            TextView about = new TextView(c);
            about.setText(R.string.chat_welcome_text);
            about.setTextColor(MUTED);
            about.setTextSize(15);
            about.setPadding(dp(8), dp(8), dp(8), dp(12));
            list.addView(about);
        } else {
            // earlier chats stay within reach when a new one is started
            list.addView(sectionTitle(R.string.history_recent));
            for (int i = 0; i < earlier.size() && i < 3; i++) {
                list.addView(historyRow(earlier.get(i), null), rowSize());
            }
            if (earlier.size() > 3) {
                TextView all = new TextView(c);
                all.setText(getResources().getString(R.string.history_all, earlier.size()));
                all.setTextColor(ACCENT);
                all.setTextSize(14);
                all.setPadding(dp(16), dp(10), dp(16), dp(4));
                all.setBackground(pressable(0x00000000, 14));
                all.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showHistory();
                    }
                });
                list.addView(all);
            }
            list.addView(sectionTitle(R.string.chat_try));
        }
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
            list.addView(chip, rowSize());
        }
    }

    private TextView sectionTitle(int text) {
        TextView heading = new TextView(getContext());
        heading.setText(text);
        heading.setTextColor(MUTED);
        heading.setTextSize(13);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPadding(dp(8), dp(18), 0, 0);
        return heading;
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
        when.setText(ago(c, s.updated));
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

        if (m.first) {
            // nobody asked: the agent wrote this of its own accord
            TextView own = new TextView(c);
            own.setText("\u2661 " + c.getString(R.string.care_own));
            own.setTextColor(ACCENT);
            own.setTextSize(12);
            own.setPadding(0, 0, 0, dp(4));
            bubble.addView(own);
        }
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
            if (working) writing = text;
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
            Working state = new Working(c, chat, density);
            LayoutParams stateSize = new LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (!m.text.isEmpty() || !m.note.isEmpty() || !m.steps.isEmpty()) stateSize.topMargin = dp(10);
            bubble.addView(state, stateSize);
        }
        LayoutParams size = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        size.gravity = Gravity.START;
        size.topMargin = dp(14);
        size.rightMargin = dp(32);
        list.addView(bubble, size);
    }

    /** "5 minutes ago", in the language chosen in the app (Android's own wording follows the phone's language). */
    static String ago(Context c, long then) {
        long minutes = Math.max(0, System.currentTimeMillis() - then) / 60000;
        if (minutes < 1) return c.getString(R.string.time_now);
        if (minutes < 60) return c.getString(R.string.time_minutes, (int) minutes);
        if (minutes < 60 * 24) return c.getString(R.string.time_hours, (int) (minutes / 60));
        if (minutes < 60 * 24 * 7) return c.getString(R.string.time_days, (int) (minutes / (60 * 24)));
        return android.text.format.DateFormat.getMediumDateFormat(c).format(new java.util.Date(then));
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
        out.setSpan(new ForegroundColorSpan(Theme.code()), from, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /**
     * The "working on it" indicator. It says what the agent is doing at this
     * moment (thinking, running which command, reading which file, searching
     * the web for what) and, when there is nothing to add, shows a tip.
     *
     * Drawn by hand: a ring that turns around an icon for the kind of work,
     * a title with a light that runs over it, the time waited, and a bar
     * with a comet at the bottom. It reads the chat's state on every frame,
     * so the list need not be rebuilt when the work changes.
     */
    static final class Working extends View {
        private static final int[] RING = {0xffff7a45, 0xffd2a8ff, 0xff79c0ff, 0xff56d364, 0xffff7a45};
        private static final String[] ICONS = {
                "\uD83D\uDE80", "\uD83D\uDCE1", "\uD83E\uDDE0", "\u270D\uFE0F", "\uD83D\uDCBB", "\uD83D\uDCE6",
                "\uD83D\uDCF1", "\uD83D\uDC46", "\uD83D\uDC19", "\uD83D\uDCC4", "\u270F\uFE0F", "\uD83D\uDD0E",
                "\uD83C\uDF10", "\uD83D\uDD17", "\uD83D\uDDD2\uFE0F", "\uD83E\uDD1D", "\uD83E\uDDE9", "\u23F3",
                "\uD83D\uDC40", "\u2753"};
        private static final String SETUP_ICON = "\u2B07\uFE0F";
        private static final long TIP_MS = 5200, FADE_MS = 380, TIPS_AFTER_MS = 3500;

        private final Chat chat;
        private final String[] titles, tips;
        private final String setupTitle;
        private final float dp, sp;
        private final int textColor, mutedColor, accentColor, trackColor;
        private final android.graphics.Paint ring = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint icon = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.text.TextPaint title = new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.text.TextPaint about = new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.text.TextPaint tip = new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint time = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint bar = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Matrix move = new android.graphics.Matrix();
        private final android.graphics.RectF box = new android.graphics.RectF();
        private android.graphics.Shader ringShader, shine, comet;
        private float shineWidth, cometWidth;
        /** The title on screen and when it came, for the change-over. */
        private String shownTitle = "", described = "";
        private long shownAt;

        /** Whether the line under the title follows the answer while it is being written. */
        boolean followWriting;

        Working(Context c, Chat chat, float density) {
            super(c);
            this.chat = chat;
            dp = density;
            sp = getResources().getDisplayMetrics().scaledDensity;
            titles = getResources().getStringArray(R.array.activity_titles);
            tips = getResources().getStringArray(R.array.tips);
            setupTitle = c.getString(R.string.activity_setup);
            textColor = Theme.text();
            mutedColor = Theme.muted();
            accentColor = Theme.accent();
            trackColor = Theme.surfaceHigh();
            ring.setStyle(android.graphics.Paint.Style.STROKE);
            ring.setStrokeWidth(2.6f * dp);
            ring.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            icon.setTextSize(15 * sp);
            icon.setTextAlign(android.graphics.Paint.Align.CENTER);
            title.setTextSize(15 * sp);
            title.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            about.setTextSize(12 * sp);
            about.setTypeface(Typeface.MONOSPACE);
            about.setColor(mutedColor);
            tip.setTextSize(12.5f * sp);
            tip.setColor(mutedColor);
            time.setTextSize(12 * sp);
            time.setColor(mutedColor);
            time.setTextAlign(android.graphics.Paint.Align.RIGHT);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            // Drawn into a small picture by the processor, which the graphics chip then only has to
            // show: gradients on text and on arcs are not safe on every phone's graphics driver.
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int height = (int) (10 * dp + 20 * sp + 18 * sp + 12 * dp);
            setMeasuredDimension(resolveSize((int) (290 * dp), widthSpec), resolveSize(height, heightSpec));
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            float r = 15 * dp;
            ringShader = new android.graphics.SweepGradient(r + 3 * dp, centerY(), RING, null);
            shineWidth = 90 * dp;
            // the title in its own colour, with a band of the accent colour to slide over it
            shine = new android.graphics.LinearGradient(0, 0, shineWidth, 0,
                    new int[]{textColor, accentColor, textColor}, null, android.graphics.Shader.TileMode.CLAMP);
            cometWidth = Math.max(60 * dp, w * 0.32f);
            comet = new android.graphics.LinearGradient(0, 0, cometWidth, 0,
                    new int[]{accentColor & 0x00ffffff, accentColor, RING[1], RING[1] & 0x00ffffff},
                    new float[]{0f, 0.45f, 0.7f, 1f}, android.graphics.Shader.TileMode.CLAMP);
        }

        private float centerY() {
            return 6 * dp + (20 * sp + 18 * sp) / 2;
        }

        private static String clock(long ms) {
            long s = ms / 1000;
            return s < 60 ? s + "s" : s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            long now = android.os.SystemClock.elapsedRealtime();
            long waited = Math.max(0, now - chat.startedAt);
            int w = getWidth();

            // what is going on: the agent being installed, or one of the kinds of work
            String status = chat.status;
            int kind = Math.max(0, Math.min(titles.length - 1, chat.activity));
            String name = status.isEmpty() ? titles[kind] : setupTitle;
            String detail = status.isEmpty() ? chat.activityDetail : status;
            boolean writing = false;
            if (followWriting && status.isEmpty() && kind == Chat.ACT_WRITE && detail.isEmpty()) {
                // over another app the answer itself is not on screen: show its latest words
                String words = chat.writing();
                if (words.length() > 240) words = words.substring(words.length() - 240);
                detail = words.replaceAll("\\s+", " ").trim();
                writing = !detail.isEmpty();
            }
            String glyph = status.isEmpty() ? ICONS[Math.min(kind, ICONS.length - 1)] : SETUP_ICON;
            if (!name.equals(shownTitle)) {
                shownTitle = name;
                shownAt = now;
            }
            String say = detail.isEmpty() || writing ? name : name + ": " + detail;
            if (!say.equals(described)) {
                described = say;
                setContentDescription(say);
            }
            float arrive = Math.min(1f, (now - shownAt) / 240f);

            // the ring: three quarters of a circle that turns, a dot going the other way, the icon inside
            float r = 15 * dp, cx = r + 3 * dp, cy = centerY();
            float pulse = (float) Math.sin(now / 420.0);
            fill.setColor(accentColor);
            fill.setAlpha((int) (26 + 16 * pulse));
            canvas.drawCircle(cx, cy, r + (1.5f + 1.5f * pulse) * dp, fill);
            fill.setColor(trackColor);
            fill.setAlpha(255);
            canvas.drawCircle(cx, cy, r - 1.5f * dp, fill);
            float turn = (now % 1600) / 1600f * 360f;
            canvas.save();
            canvas.rotate(turn, cx, cy);
            ring.setShader(ringShader);
            box.set(cx - r, cy - r, cx + r, cy + r);
            canvas.drawArc(box, 0, 270, false, ring);
            canvas.restore();
            double back = -(now % 2600) / 2600.0 * 2 * Math.PI;
            fill.setColor(RING[2]);
            canvas.drawCircle(cx + (float) Math.cos(back) * (r + 0.5f * dp), cy + (float) Math.sin(back) * (r + 0.5f * dp),
                    2.2f * dp, fill);
            // the icon drops in when the work changes (always at one size: every new size of an
            // emoji is a new picture for the phone to make)
            icon.setAlpha((int) (255 * arrive));
            canvas.save();
            canvas.clipRect(cx - r, cy - r, cx + r, cy + r);
            canvas.drawText(glyph, cx, cy - (icon.descent() + icon.ascent()) / 2 - (1 - arrive) * 9 * dp, icon);
            canvas.restore();

            // first line: the title, with a light running over it, and the time waited
            float left = 2 * r + 14 * dp;
            float line1 = 6 * dp + 20 * sp * 0.74f;
            String clock = waited >= 3000 ? clock(waited) : "";
            float clockRoom = clock.isEmpty() ? 0 : time.measureText(clock) + 8 * dp;
            if (!clock.isEmpty()) canvas.drawText(clock, w - 1 * dp, line1, time);
            CharSequence heading = android.text.TextUtils.ellipsize(name, title, w - left - clockRoom,
                    android.text.TextUtils.TruncateAt.END);
            float headingWidth = title.measureText(heading, 0, heading.length());
            float sweep = (now % 2000) / 2000f;
            move.setTranslate(left - shineWidth + sweep * (headingWidth + 2 * shineWidth), 0);
            shine.setLocalMatrix(move);
            title.setShader(shine);
            title.setAlpha((int) (255 * arrive));
            canvas.drawText(heading, 0, heading.length(), left, line1 + (1 - arrive) * 6 * dp, title);

            // second line: what exactly (the command, the file, the search), or a tip while there is nothing to say
            float line2 = 6 * dp + 20 * sp + 18 * sp * 0.7f;
            if (!detail.isEmpty()) {
                // thoughts are followed at their end, everything else is read from its start
                boolean thinking = writing || (status.isEmpty() && kind == Chat.ACT_THINK);
                CharSequence what = android.text.TextUtils.ellipsize((thinking ? "" : "\u203a ") + detail, about,
                        w - left, thinking ? android.text.TextUtils.TruncateAt.START : android.text.TextUtils.TruncateAt.END);
                about.setAlpha((int) (255 * arrive));
                canvas.drawText(what, 0, what.length(), left, line2, about);
            } else if (waited >= TIPS_AFTER_MS && tips.length > 0) {
                long since = waited - TIPS_AFTER_MS;
                long into = since % TIP_MS;
                float in = Math.min(1f, into / (float) FADE_MS);
                float out = Math.max(0f, (into - (TIP_MS - FADE_MS)) / (float) FADE_MS);
                CharSequence words = android.text.TextUtils.ellipsize(tips[(int) (since / TIP_MS % tips.length)], tip,
                        w - left, android.text.TextUtils.TruncateAt.END);
                tip.setAlpha((int) (255 * Math.min(in, 1 - out)));
                canvas.drawText(words, 0, words.length(), left, line2 + (1 - in) * 5 * dp - out * 5 * dp, tip);
            }

            // the bar: a comet that crosses it, slow at the ends and fast in the middle
            float top = getHeight() - 5 * dp, thick = 3 * dp;
            bar.setShader(null);
            bar.setColor(trackColor);
            box.set(0, top, w, top + thick);
            canvas.drawRoundRect(box, thick / 2, thick / 2, bar);
            float t = (now % 1500) / 1500f;
            float eased = t < 0.5f ? 2 * t * t : 1 - (float) Math.pow(-2 * t + 2, 2) / 2;
            float head = -cometWidth + eased * (w + cometWidth);
            move.setTranslate(head, 0);
            comet.setLocalMatrix(move);
            bar.setShader(comet);
            canvas.save();
            canvas.clipRect(0, top, w, top + thick);
            box.set(Math.max(0, head), top, Math.min(w, head + cometWidth), top + thick);
            canvas.drawRoundRect(box, thick / 2, thick / 2, bar);
            canvas.restore();

            postInvalidateDelayed(24); // about 40 pictures a second: smooth, and easy on the battery
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
        return new RippleDrawable(ColorStateList.valueOf(Theme.ripple()),
                rounded(color, radiusDp), rounded(0xffffffff, radiusDp));
    }

    private int dp(float value) {
        return Math.round(value * density);
    }
}
