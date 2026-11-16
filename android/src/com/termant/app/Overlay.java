package com.termant.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The card Termant shows on top of other apps while Termant itself is not
 * on screen. It is one of three things:
 *
 * - what the agent is doing right now, with a stop button;
 * - a question from the agent, with its choices or a box to type in;
 * - the answer, once the agent is done: it stays for ten seconds, and has a
 *   box to type the next message in without going back to Termant.
 *
 * Swiping the card up takes it away.
 *
 * It needs one of two things the user turns on: Screen control (then the
 * card belongs to that service and needs nothing else), or Android's
 * "display over other apps" permission. Without either there is no card; a
 * question then arrives as a notification that opens Termant.
 *
 * Everything here runs on the main thread.
 */
final class Overlay {
    private static final int NONE = 0, STATUS = 1, QUESTION = 2, RESULT = 3;
    /** How long the answer stays on screen after the agent is done. */
    static final long RESULT_STAYS_MS = 10_000;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Runnable look = new Runnable() {
        @Override
        public void run() {
            TermService.pokeSurfaces();
        }
    };
    private static Overlay shown;

    // What went on lately; kept here because the card itself comes and goes.
    private static boolean wasBusy;
    /** Counts the agent's turns, so that "swiped away" holds for one of them only. */
    private static int turn;
    private static int statusSwiped = -1;
    private static Ask.Question questionSwiped;
    /** Until when the answer is shown (on the clock that counts since the phone started). */
    private static long resultUntil;
    /** The user is typing the next message on the card: it stays until that is sent or closed. */
    private static boolean replying;
    /** The agent was stopped from the card: there is no answer to show then. */
    private static boolean stoppedHere;

    private final Context host;
    private final TermService service;
    private final Context context;
    private final WindowManager windows;
    private final WindowManager.LayoutParams params;
    private final Card card;
    private final float dp;
    private int mode = NONE;
    private Ask.Question question;
    /** In a question with choices: the user chose to type instead. */
    private boolean typing;
    private boolean typingShown;

    /** Whether Android lets Termant draw over other apps without Screen control. */
    static boolean allowed(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    /** Whether a card can be shown over other apps at all right now. */
    static boolean possible(Context c) {
        return ScreenService.get() != null || allowed(c);
    }

    static boolean isShown() {
        return shown != null;
    }

    private static boolean hasAnswer(Chat chat) {
        if (chat.messages.isEmpty()) return false;
        Chat.Message last = chat.messages.get(chat.messages.size() - 1);
        return !last.user && !last.text.trim().isEmpty();
    }

    /** Shows, changes or removes the card to match what is going on. */
    static void sync(TermService service) {
        Ask.Question q = Ask.pending();
        boolean onScreen = MainActivity.isOnScreen();
        boolean busy = service.chat.isBusy();
        long now = SystemClock.elapsedRealtime();
        if (busy && !wasBusy) {
            turn++;
            resultUntil = 0;
            replying = false;
            stoppedHere = false;
        }
        if (!busy && wasBusy && !onScreen && !stoppedHere && hasAnswer(service.chat)) {
            // the agent is done and nobody is looking at Termant: the answer stays up for a while
            resultUntil = now + RESULT_STAYS_MS;
            main.removeCallbacks(look);
            main.postDelayed(look, RESULT_STAYS_MS + 60);
        }
        wasBusy = busy;
        if (onScreen) {
            resultUntil = 0;
            replying = false;
        }
        if (q == null) questionSwiped = null;

        int mode = onScreen ? NONE
                : q != null ? (q == questionSwiped ? NONE : QUESTION)
                : busy ? (statusSwiped == turn ? NONE : STATUS)
                : replying || now < resultUntil ? RESULT : NONE;
        Context host = mode == NONE ? null
                : ScreenService.get() != null ? ScreenService.get() : allowed(service) ? service : null;
        if (host == null) {
            hide();
            return;
        }
        if (shown != null && shown.host != host) hide();
        if (shown == null) {
            try {
                shown = new Overlay(host, service);
            } catch (Exception e) {
                Log.e("Termant", "overlay: cannot be shown", e);
                shown = null;
                return;
            }
        }
        shown.show(mode, q);
    }

    static void hide() {
        if (shown == null) return;
        try {
            shown.windows.removeView(shown.card);
        } catch (Exception e) {
            // the window went away with the service that owned it
        }
        shown = null;
        Log.i("Termant", "overlay: hidden");
    }

    /**
     * While the agent itself taps or swipes, the card must not be in the way:
     * a tap on a button under it would land on the card.
     */
    static void letThrough(boolean through) {
        if (shown == null) return;
        try {
            if (through) shown.params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            else shown.params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            shown.windows.updateViewLayout(shown.card, shown.params);
        } catch (Exception e) {
            // gone meanwhile
        }
    }

    private Overlay(Context host, TermService service) {
        this.host = host;
        this.service = service;
        context = new ContextThemeWrapper(Lang.wrap(host), android.R.style.Theme_DeviceDefault);
        windows = (WindowManager) host.getSystemService(Context.WINDOW_SERVICE);
        dp = host.getResources().getDisplayMetrics().density;
        boolean ofScreenControl = host instanceof ScreenService;
        int type = ofScreenControl ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                : Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        int bar = host.getResources().getIdentifier("status_bar_height", "dimen", "android");
        params.y = (bar > 0 ? host.getResources().getDimensionPixelSize(bar) : px(24)) + px(6);
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
        params.setTitle("Termant");

        card = new Card(context);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable back = new GradientDrawable();
        back.setColor(Theme.surface());
        back.setCornerRadius(px(18));
        back.setStroke(Math.max(1, px(1)), Theme.accent());
        card.setBackground(back);
        card.setElevation(px(8));
        windows.addView(card, params);
        Log.i("Termant", "overlay: " + (ofScreenControl ? "with screen control" : "over other apps"));
    }

    private int px(float value) {
        return Math.round(value * dp);
    }

    private int width() {
        int screen = host.getResources().getDisplayMetrics().widthPixels;
        return Math.min(screen - px(16), px(380));
    }

    private GradientDrawable pill(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(px(radius));
        return d;
    }

    private void show(int mode, Ask.Question q) {
        boolean keyboard = (mode == QUESTION && (typing || q.choices.isEmpty())) || (mode == RESULT && replying);
        if (mode == this.mode && q == question && keyboard == typingShown) return;
        if (q != question) typing = false;
        keyboard = (mode == QUESTION && (typing || q.choices.isEmpty())) || (mode == RESULT && replying);
        this.mode = mode;
        question = q;
        typingShown = keyboard;
        card.removeAllViews();
        card.animate().cancel();
        card.setTranslationY(0);
        card.setAlpha(1);
        // Only a card with a box to type in may take the keyboard away from the app under it.
        if (keyboard) params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        else params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (mode == STATUS) addStatus();
        else if (mode == QUESTION) addQuestion(q, keyboard);
        else addResult();
        try {
            windows.updateViewLayout(card, params);
        } catch (Exception e) {
            Log.e("Termant", "overlay: cannot be changed", e);
        }
    }

    /** The card was swiped up (or closed): it stays away for what it was showing. */
    private void sentAway(String how) {
        Log.i("Termant", "overlay: " + how);
        if (mode == STATUS) {
            statusSwiped = turn;
        } else if (mode == QUESTION) {
            // the question is still open: it goes on as a notification
            questionSwiped = question;
        } else {
            resultUntil = 0;
            replying = false;
        }
        service.surfacesChanged();
    }

    // ------------------------------------------------------------ what the agent is doing

    private void addStatus() {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(12), px(6), px(8), px(6));
        ChatView.Working working = new ChatView.Working(context, service.chat, dp);
        working.followWriting = true;
        row.addView(working, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        final TextView stop = new TextView(context);
        stop.setText("\u25a0");
        stop.setTextSize(15);
        stop.setTextColor(Theme.onAccent());
        stop.setGravity(Gravity.CENTER);
        stop.setBackground(pill(Theme.accent(), 20));
        stop.setContentDescription(context.getString(R.string.chat_stop));
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.i("Termant", "overlay: stopped by the user");
                stoppedHere = true;
                Ask.close(Ask.pending());
                service.chat.stop();
            }
        });
        LinearLayout.LayoutParams stopSize = new LinearLayout.LayoutParams(px(38), px(38));
        stopSize.leftMargin = px(8);
        row.addView(stop, stopSize);
        // a tap on the card brings Termant back
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openTermant();
            }
        });
        card.addView(row, new LinearLayout.LayoutParams(width(), ViewGroup.LayoutParams.WRAP_CONTENT));
        tell("status shown", "stop", stop);
    }

    private void openTermant() {
        try {
            host.startActivity(new Intent(host, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (Exception e) {
            Log.e("Termant", "overlay: cannot open Termant", e);
        }
    }

    // ------------------------------------------------------------ pieces of a card

    private LinearLayout box() {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(14), px(10), px(14), px(12));
        return box;
    }

    /** The line at the top of a card: who is speaking, and a cross to close it. */
    private void addHead(LinearLayout box, String label, int color, View.OnClickListener onClose) {
        LinearLayout head = new LinearLayout(context);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView from = new TextView(context);
        from.setText(label);
        from.setTextColor(color);
        from.setTextSize(12);
        from.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(from, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView close = new TextView(context);
        close.setText("\u2715");
        close.setTextColor(Theme.muted());
        close.setTextSize(15);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription(context.getString(R.string.ask_close));
        close.setOnClickListener(onClose);
        head.addView(close, new LinearLayout.LayoutParams(px(36), px(32)));
        box.addView(head);
        tell(null, "close", close);
    }

    private LinearLayout.LayoutParams optionSize() {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        size.topMargin = px(6);
        return size;
    }

    private TextView option(String label, int color, int background) {
        TextView option = new TextView(context);
        option.setText(label);
        option.setTextColor(color);
        option.setTextSize(15);
        option.setGravity(Gravity.CENTER);
        option.setMinHeight(px(42));
        option.setPadding(px(12), px(8), px(12), px(8));
        option.setBackground(pill(background, 12));
        return option;
    }

    private interface Typed {
        void send(String text);
    }

    /** A box to type in with a send button; the keyboard comes up by itself. */
    private void addTyping(LinearLayout box, int hint, final Typed typed) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.BOTTOM);
        final EditText input = new EditText(context);
        input.setHint(hint);
        input.setHintTextColor(Theme.muted());
        input.setTextColor(Theme.text());
        input.setTextSize(15);
        input.setMaxLines(3);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setPadding(px(12), px(9), px(12), px(9));
        input.setBackground(pill(Theme.surfaceHigh(), 12));
        row.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView send = new TextView(context);
        send.setText("\u2191");
        send.setTextSize(19);
        send.setTextColor(Theme.onAccent());
        send.setGravity(Gravity.CENTER);
        send.setBackground(pill(Theme.accent(), 21));
        send.setContentDescription(context.getString(R.string.ask_send));
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = input.getText().toString().trim();
                if (!text.isEmpty()) typed.send(text);
            }
        });
        LinearLayout.LayoutParams sendSize = new LinearLayout.LayoutParams(px(42), px(42));
        sendSize.leftMargin = px(8);
        row.addView(send, sendSize);
        box.addView(row, optionSize());
        tell(null, "box", input);
        tell(null, "send", send);

        input.requestFocus();
        input.postDelayed(new Runnable() {
            @Override
            public void run() {
                InputMethodManager keys = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (keys != null && input.isAttachedToWindow()) keys.showSoftInput(input, 0);
            }
        }, 250);
    }

    // ------------------------------------------------------------ a question

    private void addQuestion(final Ask.Question q, boolean keyboard) {
        LinearLayout box = box();
        addHead(box, context.getString(R.string.ask_from), Theme.accent(), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ask.close(q);
            }
        });

        TextView text = new TextView(context);
        text.setText(q.text);
        text.setTextColor(Theme.text());
        text.setTextSize(15);
        text.setMaxLines(7);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setPadding(0, px(2), 0, px(8));
        box.addView(text);

        if (!keyboard) {
            for (int i = 0; i < q.choices.size(); i++) {
                final String choice = q.choices.get(i);
                TextView option = option(choice, Theme.text(), Theme.surfaceHigh());
                option.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Ask.answer(q, choice);
                    }
                });
                box.addView(option, optionSize());
                tell(null, "choice " + (i + 1), option);
            }
            TextView write = option("\u270e  " + context.getString(R.string.ask_write), Theme.accent(), 0x00000000);
            write.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    typing = true;
                    show(QUESTION, q);
                }
            });
            box.addView(write, optionSize());
            tell(null, "write", write);
        } else {
            addTyping(box, R.string.answer_hint, new Typed() {
                @Override
                public void send(String answer) {
                    Ask.answer(q, answer);
                }
            });
        }
        card.addView(box, new LinearLayout.LayoutParams(width(), ViewGroup.LayoutParams.WRAP_CONTENT));
        Log.i("Termant", "overlay: question shown");
    }

    // ------------------------------------------------------------ the answer

    private void addResult() {
        Chat.Message answer = service.chat.messages.get(service.chat.messages.size() - 1);
        LinearLayout box = box();
        addHead(box, context.getString(answer.failed ? R.string.result_failed : R.string.result_done),
                answer.failed ? Theme.error() : Theme.accent(), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sentAway("answer closed");
                    }
                });

        TextView text = new TextView(context);
        text.setText(ChatView.format(answer.text.trim()));
        text.setTextColor(answer.failed ? Theme.error() : Theme.text());
        text.setTextSize(15);
        text.setMaxLines(replying ? 3 : 6);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setPadding(0, px(2), 0, px(4));
        // a tap on the answer opens Termant, where all of it can be read
        text.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openTermant();
            }
        });
        box.addView(text);

        if (replying) {
            addTyping(box, R.string.ask_hint, new Typed() {
                @Override
                public void send(String message) {
                    Log.i("Termant", "overlay: sent from the card");
                    replying = false;
                    resultUntil = 0;
                    service.chat.send(message); // the card turns into "what the agent is doing"
                }
            });
        } else {
            // a line that runs out with the time the card has left
            long left = Math.max(0, resultUntil - SystemClock.elapsedRealtime());
            View time = new View(context);
            time.setBackground(pill(Theme.accent(), 1));
            time.setPivotX(0);
            time.setScaleX(left / (float) RESULT_STAYS_MS);
            time.animate().scaleX(0).setDuration(left).setInterpolator(new LinearInterpolator());
            LinearLayout.LayoutParams timeSize = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(2, px(2)));
            timeSize.topMargin = px(6);
            box.addView(time, timeSize);

            TextView write = option("\u270e  " + context.getString(R.string.result_reply), Theme.muted(), Theme.surfaceHigh());
            write.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            write.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    replying = true; // the card now stays until the message is sent or the card is closed
                    main.removeCallbacks(look);
                    TermService.pokeSurfaces();
                }
            });
            box.addView(write, optionSize());
            tell(null, "reply", write);
        }
        card.addView(box, new LinearLayout.LayoutParams(width(), ViewGroup.LayoutParams.WRAP_CONTENT));
        Log.i("Termant", "overlay: " + (replying ? "typing the next message" : "result shown"));
    }

    /** Notes in the log what was shown and where; the automated test taps there. */
    private void tell(final String what, final String name, final View view) {
        if (what != null) Log.i("Termant", "overlay: " + what);
        // once the card has been laid out in its new shape
        card.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!view.isAttachedToWindow() || view.getWidth() == 0) return;
                int[] at = new int[2];
                view.getLocationOnScreen(at);
                Log.i("Termant", "overlay: " + name + " at " + (at[0] + view.getWidth() / 2) + ","
                        + (at[1] + view.getHeight() / 2));
            }
        }, 450);
    }

    // ------------------------------------------------------------ swiping it away

    /** The card: follows a finger that drags it up, and goes away when it is let go far enough up. */
    private final class Card extends LinearLayout {
        private final int slop;
        private float downX, downY;
        private boolean dragging;

        Card(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = e.getRawX();
                downY = e.getRawY();
                dragging = false;
                return false;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_MOVE && !dragging) {
                float up = downY - e.getRawY();
                // an upward drag, not a tap and not a sideways move in the text box
                if (up > slop && up > Math.abs(e.getRawX() - downX)) dragging = true;
            }
            return dragging;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float up = downY - e.getRawY();
                    if (!dragging && up > slop && up > Math.abs(e.getRawX() - downX)) dragging = true;
                    if (dragging) {
                        setTranslationY(-Math.max(0, up));
                        setAlpha(Math.max(0.25f, 1 - Math.max(0, up) / Math.max(1, getHeight())));
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    float up = downY - e.getRawY();
                    if (dragging && up > px(36)) {
                        animate().translationY(-getHeight()).alpha(0).setDuration(140).withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                if (shown == Overlay.this) sentAway("swiped away");
                            }
                        });
                    } else {
                        animate().translationY(0).alpha(1).setDuration(140);
                        if (!dragging) performClick();
                    }
                    dragging = false;
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    animate().translationY(0).alpha(1).setDuration(140);
                    dragging = false;
                    return true;
                default:
                    return super.onTouchEvent(e);
            }
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }
    }
}
