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
import android.widget.ImageView;
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

        /** The user wants to send a picture or a file with the message. */
        void onAttach();

        /** The user wants a live voice call. */
        void onLive();
    }

    private final int BG = Theme.bg(), SURFACE = Theme.surface(),
            SURFACE_HIGH = Theme.surfaceHigh(), TEXT = Theme.text(), MUTED = Theme.muted(),
            ACCENT = Theme.accent(), ON_ACCENT = Theme.onAccent(), ERROR = Theme.error(),
            OUTLINE = Theme.outline();
    /** The pictures of the four ideas on the first screen, in the order of R.array.chat_ideas. */
    private static final int[] IDEA_ICONS = {
            R.drawable.ic_bell, R.drawable.ic_timer, R.drawable.ic_battery, R.drawable.ic_code};
    private static final Pattern INLINE = Pattern.compile("\\*\\*(.+?)\\*\\*|`([^`\n]+)`");

    private final Host host;
    private final float density;
    private final LinearLayout list;
    private final ScrollView scroll;
    private final EditText input;
    private final ImageView send;
    private final TextView workspaceChip, modelChip;
    private final ImageView mic;
    private Chat chat;
    /** Files waiting to go with the next message, as paths inside Ubuntu. */
    private final java.util.List<String> attached = new java.util.ArrayList<>();
    private LinearLayout attachRow;
    /** The text of the reply being written, and what the list looked like when it was built. */
    private TextView writing;
    private ImageView live;
    /** The last rendered chat and its message count: a row that arrived after that may come in. */
    private Chat seenChat;
    private int seenCount;
    private String shape = "";

    ChatView(Context context, Host host) {
        super(context);
        this.host = host;
        density = getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setBackgroundColor(BG);

        // top bar: earlier chats, the name, a new chat, settings, the terminal
        LinearLayout bar = new LinearLayout(context);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(6), dp(10), dp(6));

        ImageView past = Ui.iconButton(context, R.drawable.ic_menu,
                context.getString(R.string.history_title), TEXT, 40, 22);
        past.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                showHistory();
            }
        });
        pressScale(past);
        bar.addView(past, new LayoutParams(dp(40), dp(40)));

        TextView title = new TextView(context);
        title.setText("Termant");
        title.setTextColor(TEXT);
        title.setTextSize(Ui.TITLE);
        title.setTypeface(Ui.medium());
        title.setSingleLine(true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(6), 0, 0, 0);
        Ui.leading(title, R.drawable.ic_sparkle, ACCENT, 17, 7);
        bar.addView(title, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        ImageView fresh = Ui.iconButton(context, R.drawable.ic_compose,
                context.getString(R.string.chat_new_description), TEXT, 40, 21);
        fresh.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (chat != null) chat.clear();
            }
        });
        pressScale(fresh);
        bar.addView(fresh, new LayoutParams(dp(40), dp(40)));

        ImageView gear = Ui.iconButton(context, R.drawable.ic_settings,
                context.getString(R.string.settings), TEXT, 40, 21);
        gear.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ChatView.this.host.onSettings();
            }
        });
        pressScale(gear);
        bar.addView(gear, new LayoutParams(dp(40), dp(40)));

        ImageView terminal = Ui.iconButton(context, R.drawable.ic_terminal,
                context.getString(R.string.chat_terminal_description), TEXT, 36, 20);
        terminal.setPadding(dp(13), dp(8), dp(13), dp(8));
        terminal.setBackground(Ui.chip(context, SURFACE_HIGH, 18));
        terminal.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ChatView.this.host.onOpenTerminal();
            }
        });
        LayoutParams terminalSize = new LayoutParams(dp(48), dp(36));
        terminalSize.leftMargin = dp(4);
        pressScale(terminal);
        bar.addView(terminal, terminalSize);
        addView(bar);

        // messages
        list = new LinearLayout(context);
        list.setOrientation(VERTICAL);
        list.setPadding(dp(14), dp(8), dp(14), dp(10));
        scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.addView(list);
        addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // where the agent works and which model answers
        LinearLayout chips = new LinearLayout(context);
        chips.setPadding(dp(12), dp(2), dp(12), dp(4));
        workspaceChip = chip(context.getString(R.string.workspace_title), R.drawable.ic_folder, R.drawable.ic_chevron_down);
        workspaceChip.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ChatView.this.host.onPickWorkspace();
            }
        });
        pressScale(workspaceChip);
        chips.addView(workspaceChip, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)));
        modelChip = chip(context.getString(R.string.model_title), R.drawable.ic_sparkle, R.drawable.ic_chevron_down);
        modelChip.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ChatView.this.host.onPickModel();
            }
        });
        LayoutParams modelSize = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
        modelSize.leftMargin = dp(6);
        pressScale(modelChip);
        chips.addView(modelChip, modelSize);

        // Arena.ai has no way for other apps to use its agent, so this opens its website.
        TextView arena = chip(context.getString(R.string.arena_description), 0, R.drawable.ic_external);
        arena.setText("Arena");
        arena.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ChatView.this.host.onOpenArena();
            }
        });
        LayoutParams arenaSize = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
        arenaSize.leftMargin = dp(6);
        pressScale(arena);
        chips.addView(arena, arenaSize);

        android.widget.HorizontalScrollView chipScroll = new android.widget.HorizontalScrollView(context);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.addView(chips);
        addView(chipScroll);
        refreshChips();

        // message box
        LinearLayout pill = new LinearLayout(context);
        pill.setGravity(Gravity.BOTTOM);
        pill.setBackground(Ui.card(context, 26));
        pill.setPadding(dp(5), dp(5), dp(5), dp(5));
        ImageView plus = Ui.iconButton(context, R.drawable.ic_plus, context.getString(R.string.attach_file), MUTED, 42, 22);
        plus.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ChatView.this.host.onAttach();
            }
        });
        pressScale(plus);
        pill.addView(plus, new LayoutParams(dp(42), dp(42)));
        input = new EditText(context);
        input.setHint(R.string.ask_hint);
        input.setHintTextColor(MUTED);
        input.setTextColor(TEXT);
        input.setTextSize(16);
        input.setBackground(null);
        input.setPadding(dp(4), dp(10), 0, dp(10));
        input.setMaxLines(5);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        pill.addView(input, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        live = Ui.iconButton(context, R.drawable.ic_phone, context.getString(R.string.live_call),
                0xffffffff, 42, 21);
        live.setBackground(Ui.pressable(context, ERROR, 21));
        live.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ChatView.this.host.onLive();
            }
        });
        pressScale(live);
        pill.addView(live, new LayoutParams(dp(42), dp(42)));

        mic = Ui.iconButton(context, R.drawable.ic_mic, context.getString(R.string.ask_voice), MUTED, 42, 22);
        mic.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ChatView.this.host.onVoice();
            }
        });
        pressScale(mic);
        pill.addView(mic, new LayoutParams(dp(42), dp(42)));

        send = Ui.iconButton(context, R.drawable.ic_arrow_up, context.getString(R.string.ask_send), ON_ACCENT, 42, 22);
        send.setBackground(Ui.pressable(context, ACCENT, 21));
        send.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (chat == null) return;
                if (chat.isBusy()) chat.stop();
                else submit(input.getText().toString());
            }
        });
        LayoutParams sendSize = new LayoutParams(dp(42), dp(42));
        sendSize.leftMargin = dp(2);
        pressScale(send);
        pill.addView(send, sendSize);

        // the files that go with the next message: a name each, a tap takes one off again
        attachRow = new LinearLayout(context);
        attachRow.setPadding(dp(12), dp(4), dp(12), 0);
        android.widget.HorizontalScrollView attachScroll = new android.widget.HorizontalScrollView(context);
        attachScroll.setHorizontalScrollBarEnabled(false);
        attachScroll.addView(attachRow);
        addView(attachScroll);
        attachRow.setVisibility(GONE);

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
        workspaceChip.setText(Config.workspaceName(getContext()));
        modelChip.setText(Config.modelName(getContext()));
    }

    /** A small button under the messages: an icon (0 for none), a name, and a mark after it. */
    private TextView chip(String description, int icon, int after) {
        TextView v = button("", description);
        v.setTextSize(Ui.CAPTION);
        v.setTextColor(TEXT);
        v.setMaxWidth(dp(128));
        v.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        v.setPadding(dp(10), 0, dp(8), 0);
        v.setBackground(Ui.chip(getContext(), SURFACE, 15));
        if (icon != 0) Ui.leading(v, icon, ACCENT, 13, 5);
        Ui.trailing(v, after, MUTED, 12, 4);
        return v;
    }

    String draft() {
        return input.getText().toString();
    }

    /** Shows that the microphone is listening: the button lights up and the box says so. */
    void setListening(boolean listening) {
        Context c = getContext();
        mic.setBackground(Ui.pressable(c, listening ? ACCENT : 0x00000000, 21));
        mic.setImageDrawable(Ui.icon(c, R.drawable.ic_mic, listening ? ON_ACCENT : MUTED));
        mic.setContentDescription(c.getString(listening ? R.string.voice_stop : R.string.ask_voice));
        input.setHint(listening ? R.string.voice_listening : R.string.ask_hint);
    }

    /** Puts dictated text in the box, so it can be corrected before sending. */
    void setDraft(String text) {
        input.setText(text);
        input.setSelection(input.length());
        input.requestFocus();
    }

    /** A file the user picked, already copied to where Ubuntu reads it. It goes with the next message. */
    void addAttachment(String path) {
        if (path == null || path.isEmpty() || attached.contains(path) || attached.size() >= 10) return;
        attached.add(path);
        showAttached();
    }

    private void showAttached() {
        attachRow.removeAllViews();
        attachRow.setVisibility(attached.isEmpty() ? GONE : VISIBLE);
        for (final String path : attached) {
            String name = path.substring(path.lastIndexOf('/') + 1);
            TextView v = chip(getContext().getString(R.string.attach_remove, name), R.drawable.ic_file, R.drawable.ic_close);
            v.setText(name);
            v.setMaxWidth(dp(190));
            v.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View view) {
                    attached.remove(path);
                    showAttached();
                }
            });
            LayoutParams size = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
            size.rightMargin = dp(6);
            attachRow.addView(v, size);
        }
    }

    /** "[file: /root/uploads/a.jpg]" in a message is shown as the file's name. */
    static String shown(String text) {
        return text.replaceAll("\\[file: (?:[^\\]\\n]*/)?([^\\]\\n/]+)\\]", "\uD83D\uDCCE $1");
    }

    private void submit(String text) {
        if (text.trim().isEmpty() && !attached.isEmpty()) text = getContext().getString(R.string.attach_default);
        if (text.trim().isEmpty()) return;
        if (!attached.isEmpty()) {
            // the agent finds the files by these marks (see `termant-kit attach`)
            StringBuilder with = new StringBuilder(text.trim());
            for (String path : attached) with.append("\n[file: ").append(path).append("]");
            text = with.toString();
            attached.clear();
            showAttached();
        }
        input.setText("");
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(getWindowToken(), 0);
        chat.send(text);
    }

    // ------------------------------------------------------------ drawing

    /**
     * The button feels pressed: a small press on touch-down, a small rebound when it lifts.
     * The click itself is untouched (the listener reports nothing to the chain).
     */
    private static void pressScale(final View v) {
        v.setOnTouchListener(new android.view.View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        view.animate().scaleX(0.88f).scaleY(0.88f).setDuration(70).start();
                        return false;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                    case android.view.MotionEvent.ACTION_OUTSIDE:
                        view.animate().scaleX(1f).scaleY(1f).setDuration(200)
                                .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
                        return false;
                    default:
                        return false;
                }
            }
        });
    }

    @Override
    public void onChatChanged() {
        if (chat == null) return;
        refreshChips(); // auto-switch may have moved to another model
        boolean busy = chat.isBusy();
        send.setImageDrawable(Ui.icon(getContext(), busy ? R.drawable.ic_stop : R.drawable.ic_arrow_up, ON_ACCENT));
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
        // a row that arrived since the last render comes in: a big rise, a fade,
        // an overshoot, staggered
        int arrived = chat.messages.size() - seenCount;
        if (seenChat == chat && arrived > 0 && list.getChildCount() > 0) {
            int from = (int) Math.max(0, list.getChildCount() - Math.min(arrived, 4));
            for (int i = from; i < list.getChildCount(); i++) {
                View row = list.getChildAt(i);
                row.setAlpha(0f);
                row.setTranslationY(dp(26));
                row.animate().alpha(1f).translationY(0f).setDuration(340)
                        .setStartDelay((long) (i - from) * 90)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.25f)).start();
            }
        }
        // the live button is dimmed while the live quota is used up
        live.setAlpha(Config.liveBlocked(getContext()) > System.currentTimeMillis() ? 0.35f : 1f);
        seenChat = chat;
        seenCount = chat.messages.size();
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
                + "|" + chat.status + "|" + m.steps + "|" + m.pictures.size();
    }

    private void addWelcome() {
        Context c = getContext();
        List<Chat.Summary> earlier = chat.history();
        list.setGravity(earlier.isEmpty() ? Gravity.CENTER_VERTICAL : Gravity.TOP);
        if (earlier.isEmpty()) {
            // first time: the mark of the app, and what this is
            list.addView(Ui.badge(c, R.drawable.ic_sparkle, 52, 26), new LayoutParams(dp(52), dp(52)));
        }
        TextView hello = new TextView(c);
        hello.setText(R.string.chat_welcome);
        hello.setTextColor(TEXT);
        hello.setTextSize(earlier.isEmpty() ? Ui.DISPLAY : 21);
        hello.setTypeface(Typeface.DEFAULT_BOLD);
        hello.setLetterSpacing(-0.01f);
        hello.setPadding(dp(2), dp(earlier.isEmpty() ? 16 : 6), dp(2), 0);
        list.addView(hello);
        if (earlier.isEmpty()) {
            TextView about = new TextView(c);
            about.setText(R.string.chat_welcome_text);
            about.setTextColor(MUTED);
            about.setTextSize(Ui.LABEL);
            about.setLineSpacing(dp(2), 1);
            about.setPadding(dp(2), dp(8), dp(2), 0);
            list.addView(about);
        } else {
            // earlier chats stay within reach when a new one is started
            list.addView(sectionTitle(R.string.history_recent));
            LinearLayout recent = group();
            for (int i = 0; i < earlier.size() && i < 3; i++) {
                if (i > 0) recent.addView(Ui.divider(c), dividerSize(50));
                recent.addView(historyRow(earlier.get(i), null, null));
            }
            list.addView(recent, rowSize());
            if (earlier.size() > 3) {
                TextView all = new TextView(c);
                all.setText(getResources().getString(R.string.history_all, earlier.size()));
                all.setTextColor(ACCENT);
                all.setTextSize(Ui.LABEL);
                all.setTypeface(Ui.medium());
                all.setGravity(Gravity.CENTER_VERTICAL);
                all.setPadding(dp(12), dp(9), dp(8), dp(9));
                all.setBackground(Ui.pressable(c, 0x00000000, Ui.R_ROW));
                Ui.trailing(all, R.drawable.ic_chevron_right, ACCENT, 14, 2);
                all.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showHistory();
                    }
                });
                LayoutParams allSize = new LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                allSize.topMargin = dp(2);
                list.addView(all, allSize);
            }
        }
        list.addView(sectionTitle(R.string.chat_try));
        LinearLayout ideas = group();
        String[] texts = getResources().getStringArray(R.array.chat_ideas);
        for (int i = 0; i < texts.length; i++) {
            final String idea = texts[i];
            if (i > 0) ideas.addView(Ui.divider(c), dividerSize(50));
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(10), dp(12));
            row.setBackground(Ui.pressable(c, 0x00000000, 0));
            row.addView(Ui.image(c, IDEA_ICONS[i % IDEA_ICONS.length], ACCENT), new LayoutParams(dp(20), dp(20)));
            TextView words = new TextView(c);
            words.setText(idea);
            words.setTextColor(TEXT);
            words.setTextSize(Ui.LABEL);
            words.setPadding(dp(14), 0, dp(6), 0);
            row.addView(words, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(Ui.image(c, R.drawable.ic_chevron_right, MUTED), new LayoutParams(dp(16), dp(16)));
            row.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (chat != null && !chat.isBusy()) chat.send(idea);
                }
            });
            ideas.addView(row);
        }
        list.addView(ideas, rowSize());
    }

    private TextView sectionTitle(int text) {
        TextView heading = new TextView(getContext());
        heading.setText(getResources().getString(text).toUpperCase(java.util.Locale.ROOT));
        heading.setTextColor(MUTED);
        heading.setTextSize(11);
        heading.setTypeface(Ui.medium());
        heading.setLetterSpacing(0.08f);
        heading.setPadding(dp(4), dp(22), 0, 0);
        return heading;
    }

    /** A card that holds several rows, one under the other, with hairlines between them. */
    private LinearLayout group() {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(VERTICAL);
        box.setBackground(Ui.card(getContext(), Ui.R_CARD));
        box.setClipToOutline(true);
        return box;
    }

    private LayoutParams dividerSize(int leftDp) {
        LayoutParams size = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        size.leftMargin = dp(leftDp);
        return size;
    }

    // ------------------------------------------------------------ earlier chats

    private LayoutParams rowSize() {
        LayoutParams size = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        size.topMargin = dp(8);
        return size;
    }

    /**
     * One earlier chat: tap to open it. In the panel with all chats (a dialog
     * and its list given) the row stands alone, shows which chat is open, and
     * has a delete button.
     */
    private View historyRow(final Chat.Summary s, final android.app.Dialog dialog, final LinearLayout rows) {
        final Context c = getContext();
        boolean open = chat != null && s.id.equals(chat.id());
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (dialog == null) {
            row.setPadding(dp(14), dp(11), dp(12), dp(11));
            row.setBackground(Ui.pressable(c, 0x00000000, 0));
        } else {
            row.setPadding(dp(12), dp(9), dp(2), dp(9));
            row.setBackground(Ui.pressable(c, open ? Theme.accentSoft() : 0x00000000, Ui.R_ROW));
        }
        row.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (dialog != null) dialog.dismiss();
                if (chat != null) chat.open(s.id);
            }
        });
        row.addView(Ui.image(c, R.drawable.ic_chat, open && dialog != null ? ACCENT : MUTED),
                new LayoutParams(dp(20), dp(20)));
        LinearLayout text = new LinearLayout(c);
        text.setOrientation(VERTICAL);
        text.setPadding(dp(dialog == null ? 14 : 12), 0, dp(4), 0);
        TextView title = new TextView(c);
        title.setText(s.title.isEmpty() ? c.getString(R.string.history_untitled) : s.title);
        title.setTextColor(TEXT);
        title.setTextSize(Ui.LABEL);
        title.setMaxLines(dialog == null ? 1 : 2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(title);
        TextView when = new TextView(c);
        when.setText(ago(c, s.updated));
        when.setTextColor(MUTED);
        when.setTextSize(Ui.CAPTION);
        when.setPadding(0, dp(1), 0, 0);
        text.addView(when);
        row.addView(text, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (dialog != null) {
            ImageView delete = Ui.iconButton(c, R.drawable.ic_trash, c.getString(R.string.history_delete), MUTED, 40, 18);
            delete.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (chat != null) chat.delete(s.id);
                    fillHistory(rows, dialog);
                }
            });
            row.addView(delete, new LayoutParams(dp(40), dp(40)));
        } else {
            row.addView(Ui.image(c, R.drawable.ic_chevron_right, MUTED), new LayoutParams(dp(16), dp(16)));
        }
        return row;
    }

    /** All earlier chats, in a panel that slides in from the left edge. */
    private void showHistory() {
        if (chat == null) return;
        Context c = getContext();
        final android.app.Dialog dialog = new android.app.Dialog(c,
                Theme.isLight() ? R.style.Drawer_Light : R.style.Drawer);

        LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(VERTICAL);
        android.graphics.drawable.GradientDrawable back = new android.graphics.drawable.GradientDrawable();
        back.setColor(BG);
        float r = dp(22);
        back.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});
        panel.setBackground(back);

        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(20), dp(14), dp(8), dp(8));
        TextView title = new TextView(c);
        title.setText(R.string.history_title);
        title.setTextColor(TEXT);
        title.setTextSize(Ui.TITLE);
        title.setTypeface(Ui.medium());
        head.addView(title, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageView close = Ui.iconButton(c, R.drawable.ic_close, c.getString(R.string.close), MUTED, 40, 20);
        close.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        head.addView(close, new LayoutParams(dp(40), dp(40)));
        panel.addView(head);

        LinearLayout rows = new LinearLayout(c);
        rows.setOrientation(VERTICAL);
        rows.setPadding(dp(8), 0, dp(8), dp(12));
        ScrollView scroller = new ScrollView(c);
        scroller.addView(rows);
        panel.addView(scroller, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        fillHistory(rows, dialog);

        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(true);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            int screen = getResources().getDisplayMetrics().widthPixels;
            window.setGravity(Gravity.START | Gravity.TOP);
            window.setLayout(Math.min(dp(320), Math.round(screen * 0.86f)), ViewGroup.LayoutParams.MATCH_PARENT);
            window.setDimAmount(0.5f);
        }
        dialog.show();
    }

    private void fillHistory(LinearLayout rows, android.app.Dialog dialog) {
        Context c = getContext();
        rows.removeAllViews();
        if (chat == null) return;
        List<Chat.Summary> all = chat.history();
        if (all.isEmpty()) {
            TextView none = new TextView(c);
            none.setText(R.string.history_none);
            none.setTextColor(MUTED);
            none.setTextSize(Ui.LABEL);
            none.setPadding(dp(12), dp(12), dp(12), dp(12));
            rows.addView(none);
        }
        for (Chat.Summary s : all) {
            LayoutParams size = new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            size.topMargin = dp(2);
            rows.addView(historyRow(s, dialog, rows), size);
        }
    }

    private void addMine(Chat.Message m) {
        list.setGravity(Gravity.TOP);
        TextView bubble = new TextView(getContext());
        bubble.setText(shown(m.text));
        bubble.setTextColor(ON_ACCENT);
        bubble.setTextSize(Ui.BODY);
        bubble.setLineSpacing(dp(2), 1);
        bubble.setTextIsSelectable(true);
        bubble.setPadding(dp(14), dp(9), dp(14), dp(10));
        // round, with one sharper corner where the bubble points at its writer
        android.graphics.drawable.GradientDrawable back = new android.graphics.drawable.GradientDrawable();
        back.setColor(ACCENT);
        float r = dp(20), tip = dp(6);
        back.setCornerRadii(new float[]{r, r, r, r, tip, tip, r, r});
        bubble.setBackground(back);
        LayoutParams size = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        size.gravity = Gravity.END;
        size.topMargin = dp(18);
        size.leftMargin = dp(48);
        list.addView(bubble, size);
    }

    /** An answer: no bubble, the words stand on the page under the name of who wrote them. */
    private void addReply(Chat.Message m, boolean working) {
        Context c = getContext();
        LinearLayout bubble = new LinearLayout(c);
        bubble.setOrientation(VERTICAL);
        bubble.setPadding(dp(2), 0, dp(2), 0);

        // who speaks; when nobody asked, that the agent wrote this of its own accord
        TextView who = new TextView(c);
        who.setText(m.first ? c.getString(R.string.care_own) : "Termant");
        who.setTextColor(m.first ? ACCENT : MUTED);
        who.setTextSize(Ui.CAPTION);
        who.setTypeface(Ui.medium());
        who.setGravity(Gravity.CENTER_VERTICAL);
        who.setPadding(0, 0, 0, dp(6));
        Ui.leading(who, m.first ? R.drawable.ic_heart : R.drawable.ic_sparkle, ACCENT, 13, 6);
        bubble.addView(who);

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
            did.setTextSize(Ui.CAPTION);
            did.setTextColor(MUTED);
            did.setLineSpacing(dp(2), 1);
            did.setTextIsSelectable(true);
            did.setPadding(dp(12), dp(9), dp(12), dp(9));
            did.setBackground(Ui.card(c, 12));
            LayoutParams didSize = new LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            didSize.bottomMargin = m.text.isEmpty() && !working ? 0 : dp(10);
            bubble.addView(did, didSize);
        }
        if (!m.text.isEmpty()) {
            TextView text = new TextView(c);
            text.setText(format(m.text));
            text.setTextColor(m.failed ? ERROR : TEXT);
            text.setTextSize(Ui.BODY);
            text.setLineSpacing(dp(3), 1);
            text.setTextIsSelectable(true);
            bubble.addView(text);
            if (working) writing = text;
        }
        // pictures the agent drew for this reply (`draw`)
        for (String picture : m.pictures) addPicture(bubble, picture, !m.text.isEmpty() || !m.steps.isEmpty());
        if (!m.note.isEmpty()) {
            TextView note = new TextView(c);
            note.setText(m.note);
            note.setTextColor(MUTED);
            note.setTextSize(Ui.CAPTION);
            note.setGravity(Gravity.CENTER_VERTICAL);
            Ui.leading(note, R.drawable.ic_refresh, MUTED, 12, 6);
            if (m.note.startsWith(Chat.UNDO_SIGN)) {
                // the files this reply changed can be put back: a tap does it
                note.setTextColor(ACCENT);
                note.setContentDescription(c.getString(R.string.undo_changes));
                note.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (chat != null && !chat.isBusy()) chat.send("undo");
                    }
                });
            }
            if (!m.text.isEmpty() || !m.steps.isEmpty() || !m.pictures.isEmpty()) note.setPadding(0, dp(8), 0, 0);
            bubble.addView(note);
        }
        if (working) {
            Working state = new Working(c, chat, density);
            LayoutParams stateSize = new LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (!m.text.isEmpty() || !m.note.isEmpty() || !m.steps.isEmpty()) stateSize.topMargin = dp(10);
            bubble.addView(state, stateSize);
        }
        // as wide as the page: the words then wrap at the page's edge, whatever else the answer shows
        LayoutParams size = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        size.topMargin = dp(18);
        size.rightMargin = dp(8);
        list.addView(bubble, size);
    }

    /** Pictures already read from their files; the list is rebuilt often and must not read them again each time. */
    private static final android.util.LruCache<String, android.graphics.Bitmap> PICTURES =
            new android.util.LruCache<String, android.graphics.Bitmap>(24 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, android.graphics.Bitmap value) {
                    return value.getByteCount();
                }
            };

    /** The picture in this file, made smaller when it is far larger than the screen; null when it cannot be read. */
    static android.graphics.Bitmap picture(java.io.File file) {
        String key = file.getPath() + "|" + file.length() + "|" + file.lastModified();
        android.graphics.Bitmap have = PICTURES.get(key);
        if (have != null) return have;
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(file.getPath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > 1600) sample *= 2;
            o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = sample;
            android.graphics.Bitmap read = android.graphics.BitmapFactory.decodeFile(file.getPath(), o);
            if (read != null) PICTURES.put(key, read);
            return read;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private void addPicture(LinearLayout bubble, final String picture, boolean gapAbove) {
        final Context c = getContext();
        final java.io.File file = Chat.Message.pictureFile(c, picture);
        android.graphics.Bitmap bitmap = file.isFile() ? picture(file) : null;
        if (bitmap == null) {
            // the file was deleted since, or is not a picture after all
            TextView gone = new TextView(c);
            gone.setText(c.getString(R.string.picture_gone, file.getName()));
            gone.setTextColor(MUTED);
            gone.setTextSize(Ui.CAPTION);
            if (gapAbove) gone.setPadding(0, dp(8), 0, 0);
            bubble.addView(gone);
            return;
        }
        ImageView view = new ImageView(c);
        view.setImageBitmap(bitmap);
        view.setAdjustViewBounds(true);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setContentDescription(c.getString(R.string.picture_drawn, file.getName()));
        view.setBackground(rounded(SURFACE, 14));
        view.setClipToOutline(true);
        view.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                showPicture(file, Chat.Message.pictureAddress(picture));
            }
        });
        // as wide as the page allows, never wider than the picture itself looks sharp
        int page = getResources().getDisplayMetrics().widthPixels - dp(44);
        int width = Math.min(page, dp(340));
        int height = Math.round(width * (bitmap.getHeight() / (float) bitmap.getWidth()));
        int most = dp(420);
        if (height > most) {
            width = Math.round(width * (most / (float) height));
            height = most;
        }
        LayoutParams size = new LayoutParams(width, height);
        if (gapAbove) size.topMargin = dp(10);
        bubble.addView(view, size);
    }

    /** The picture over the whole screen, with "open in the gallery" and "share" when the gallery has a copy. */
    private void showPicture(final java.io.File file, final String address) {
        final Context c = getContext();
        android.graphics.Bitmap bitmap = picture(file);
        if (bitmap == null) return;
        final android.app.Dialog dialog = new android.app.Dialog(c, android.R.style.Theme_Black_NoTitleBar);
        LinearLayout page = new LinearLayout(c);
        page.setOrientation(VERTICAL);
        page.setBackgroundColor(0xff000000);
        ImageView view = new ImageView(c);
        view.setImageBitmap(bitmap);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setContentDescription(c.getString(R.string.picture_drawn, file.getName()));
        view.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        page.addView(view, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        TextView where = new TextView(c);
        where.setText(address.isEmpty() ? c.getString(R.string.picture_in_ubuntu, "~/pictures/" + file.getName())
                : c.getString(R.string.picture_in_gallery, "Pictures/" + Gallery.FOLDER));
        where.setTextColor(0xffb8b8b8);
        where.setTextSize(Ui.CAPTION);
        where.setGravity(Gravity.CENTER);
        where.setPadding(dp(16), dp(10), dp(16), dp(6));
        page.addView(where);

        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp(12), dp(4), dp(12), dp(16));
        if (!address.isEmpty()) {
            final android.net.Uri uri = android.net.Uri.parse(address);
            TextView open = pictureButton(c.getString(R.string.picture_open));
            open.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        c.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW)
                                .setDataAndType(uri, "image/*")
                                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION));
                    } catch (Exception e) {
                        android.util.Log.w("Termant", "picture: cannot open " + uri, e);
                        android.widget.Toast.makeText(c, R.string.picture_no_app, android.widget.Toast.LENGTH_LONG).show();
                    }
                }
            });
            row.addView(open, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
            TextView share = pictureButton(c.getString(R.string.picture_share));
            share.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND)
                                .setType("image/*").putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        send.setClipData(android.content.ClipData.newRawUri("", uri));
                        c.startActivity(android.content.Intent.createChooser(send, c.getString(R.string.picture_share)));
                    } catch (Exception e) {
                        android.util.Log.w("Termant", "picture: cannot share " + uri, e);
                        android.widget.Toast.makeText(c, R.string.picture_no_app, android.widget.Toast.LENGTH_LONG).show();
                    }
                }
            });
            LayoutParams shareSize = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
            shareSize.leftMargin = dp(10);
            row.addView(share, shareSize);
        }
        TextView close = pictureButton(c.getString(R.string.close));
        close.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        LayoutParams closeSize = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        if (!address.isEmpty()) closeSize.leftMargin = dp(10);
        row.addView(close, closeSize);
        page.addView(row);
        dialog.setContentView(page);
        dialog.show();
    }

    private TextView pictureButton(String text) {
        TextView v = new TextView(getContext());
        v.setText(text);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(0xffffffff);
        v.setTextSize(Ui.LABEL);
        v.setTypeface(Ui.medium());
        v.setSingleLine(true);
        v.setClickable(true);
        v.setPadding(dp(18), 0, dp(18), 0);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40ffffff), rounded(0xff2a2a2a, 22), rounded(0xffffffff, 22)));
        return v;
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
        /** One picture for each kind of work, in the order of R.array.activity_titles. */
        private static final int[] ICONS = {
                R.drawable.ic_bolt, R.drawable.ic_cloud, R.drawable.ic_bulb, R.drawable.ic_edit,
                R.drawable.ic_terminal, R.drawable.ic_box, R.drawable.ic_phone, R.drawable.ic_layers,
                R.drawable.ic_branch, R.drawable.ic_file, R.drawable.ic_edit, R.drawable.ic_search,
                R.drawable.ic_globe, R.drawable.ic_external, R.drawable.ic_list, R.drawable.ic_chat,
                R.drawable.ic_plug, R.drawable.ic_clock, R.drawable.ic_eye, R.drawable.ic_help};
        private static final long TIP_MS = 5200, FADE_MS = 380, TIPS_AFTER_MS = 3500;

        private final Chat chat;
        private final String[] titles, tips;
        private final String setupTitle;
        private final float dp, sp;
        private final int textColor, mutedColor, accentColor, trackColor;
        private final android.graphics.Paint ring = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final Drawable[] icons = new Drawable[ICONS.length];
        private final Drawable setupIcon;
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
            for (int i = 0; i < ICONS.length; i++) icons[i] = Ui.icon(c, ICONS[i], textColor);
            setupIcon = Ui.icon(c, R.drawable.ic_download, textColor);
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
            // a command that has printed something: its newest line says more than the command does
            // (how far a download is, which package is being unpacked)
            String printed = status.isEmpty() ? chat.activityOutput : "";
            if (!printed.isEmpty()) detail = printed;
            boolean writing = false;
            if (followWriting && status.isEmpty() && kind == Chat.ACT_WRITE && detail.isEmpty()) {
                // over another app the answer itself is not on screen: show its latest words
                String words = chat.writing();
                if (words.length() > 240) words = words.substring(words.length() - 240);
                detail = words.replaceAll("\\s+", " ").trim();
                writing = !detail.isEmpty();
            }
            Drawable glyph = status.isEmpty() ? icons[Math.min(kind, icons.length - 1)] : setupIcon;
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
            // the icon drops in when the work changes
            float half = 8 * dp, drop = (1 - arrive) * 9 * dp;
            glyph.setBounds(Math.round(cx - half), Math.round(cy - half - drop),
                    Math.round(cx + half), Math.round(cy + half - drop));
            glyph.setAlpha((int) (255 * arrive));
            canvas.save();
            canvas.clipRect(cx - r, cy - r, cx + r, cy + r);
            glyph.draw(canvas);
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
