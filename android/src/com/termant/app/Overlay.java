package com.termant.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The card Termant shows on top of other apps while the agent is at work
 * and Termant itself is not on screen: what the agent is doing right now,
 * with a stop button, and, when the agent has a question, the question with
 * its choices or a box to type the answer in.
 *
 * It needs one of two things the user turns on: Screen control (then the
 * card belongs to that service and needs nothing else), or Android's
 * "display over other apps" permission. Without either there is no card; a
 * question then arrives as a notification that opens Termant.
 *
 * Everything here runs on the main thread.
 */
final class Overlay {
    private static Overlay shown;

    private final Context host;
    private final TermService service;
    private final Context context;
    private final WindowManager windows;
    private final WindowManager.LayoutParams params;
    private final LinearLayout card;
    private final float dp;
    /** What the card shows: null for the agent's work, else that question. */
    private Ask.Question question;
    private boolean typing;
    private boolean built;

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

    /** Shows, changes or removes the card to match what is going on. */
    static void sync(TermService service) {
        Ask.Question q = Ask.pending();
        boolean wanted = !MainActivity.isOnScreen() && (q != null || service.chat.isBusy());
        Context host = !wanted ? null : ScreenService.get() != null ? ScreenService.get() : allowed(service) ? service : null;
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
        shown.show(q);
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

        card = new LinearLayout(context);
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

    private void show(Ask.Question q) {
        if (built && q == question) return;
        if (q != question) typing = false;
        question = q;
        built = true;
        card.removeAllViews();
        boolean keyboard = q != null && (typing || q.choices.isEmpty());
        if (keyboard) params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        else params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (q == null) addStatus();
        else addQuestion(q, keyboard);
        try {
            windows.updateViewLayout(card, params);
        } catch (Exception e) {
            Log.e("Termant", "overlay: cannot be changed", e);
        }
    }

    private void rebuild() {
        built = false;
        show(question);
    }

    // ------------------------------------------------------------ what the agent is doing

    private void addStatus() {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(12), px(6), px(8), px(6));
        ChatView.Working working = new ChatView.Working(context, service.chat, dp);
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

    // ------------------------------------------------------------ a question

    private void addQuestion(final Ask.Question q, boolean keyboard) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(14), px(10), px(14), px(12));

        LinearLayout head = new LinearLayout(context);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView from = new TextView(context);
        from.setText(context.getString(R.string.ask_from));
        from.setTextColor(Theme.accent());
        from.setTextSize(12);
        from.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(from, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView close = new TextView(context);
        close.setText("\u2715");
        close.setTextColor(Theme.muted());
        close.setTextSize(15);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription(context.getString(R.string.ask_close));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ask.close(q);
            }
        });
        head.addView(close, new LinearLayout.LayoutParams(px(36), px(32)));
        box.addView(head);
        tell(null, "close", close);

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
                    rebuild();
                }
            });
            box.addView(write, optionSize());
            tell(null, "write", write);
        } else {
            addTyping(box, q);
        }
        card.addView(box, new LinearLayout.LayoutParams(width(), ViewGroup.LayoutParams.WRAP_CONTENT));
        Log.i("Termant", "overlay: question shown");
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

    private void addTyping(LinearLayout box, final Ask.Question q) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.BOTTOM);
        final EditText input = new EditText(context);
        input.setHint(R.string.answer_hint);
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
                Ask.answer(q, input.getText().toString());
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
}
