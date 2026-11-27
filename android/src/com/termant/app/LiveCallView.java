package com.termant.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The screen of the live call: the breathing orb in the middle (it grows while
 * Termant speaks, and a comet goes round it while a command runs), the two
 * voices as lines, and the end button.
 */
final class LiveCallView extends LinearLayout {

    interface Host {
        /** The call is over: the reason and its length in milliseconds. */
        void onLiveEnded(String reason, long millis);
    }

    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float density;
    private final int BG = Theme.bg(), TEXT = Theme.text(), MUTED = Theme.muted(),
            ACCENT = Theme.accent(), ERROR = Theme.error();

    private LiveCall call;
    private TextView timer, userLine, termantLine, workLine;
    private OrbView orb;
    private long startedAt;
    private boolean finished;

    LiveCallView(Context context, Host host) {
        super(context);
        this.host = host;
        density = getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setBackgroundColor(BG);
        setVisibility(GONE);

        // the top: the title and the clock
        LinearLayout top = new LinearLayout(context);
        top.setOrientation(HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(14), dp(16), dp(4));
        TextView title = new TextView(context);
        title.setText(R.string.live_title);
        title.setTextColor(ACCENT);
        title.setTextSize(18);
        top.addView(title, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        timer = new TextView(context);
        timer.setText("0:00");
        timer.setTextColor(MUTED);
        timer.setTextSize(14);
        timer.setPadding(dp(12), 0, 0, 0);
        top.addView(timer, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        addView(top, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        // the middle: the orb
        FrameLayout mid = new FrameLayout(context);
        orb = new OrbView(context);
        mid.addView(orb, new FrameLayout.LayoutParams(dp(190), dp(190), Gravity.CENTER));
        addView(mid, new LayoutParams(MATCH_PARENT, 0, 1));

        // the two voices, and the command that runs
        LinearLayout lines = new LinearLayout(context);
        lines.setOrientation(VERTICAL);
        lines.setPadding(dp(24), 0, dp(24), dp(6));
        userLine = voiceLine();
        userLine.setTextColor(MUTED);
        termantLine = voiceLine();
        termantLine.setTextColor(TEXT);
        workLine = voiceLine();
        workLine.setTextColor(ACCENT);
        workLine.setTextSize(13);
        lines.addView(userLine);
        lines.addView(termantLine, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        lines.addView(workLine, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        addView(lines, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        // the end button
        Button end = new Button(context);
        end.setText(R.string.live_end);
        end.setTextColor(0xffffffff);
        end.setTextSize(16);
        end.setAllCaps(false);
        end.setPadding(dp(34), 0, dp(34), 0);
        end.setBackground(Ui.pressable(context, ERROR, 26));
        end.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                endCall("ended");
            }
        });
        LinearLayout endRow = new LinearLayout(context);
        endRow.setGravity(Gravity.CENTER_HORIZONTAL);
        endRow.setPadding(dp(16), dp(6), dp(16), dp(22));
        endRow.addView(end, new LayoutParams(WRAP_CONTENT, dp(52)));
        addView(endRow, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
    }

    private int dp(int v) {
        return (int) (v * density);
    }

    private TextView voiceLine() {
        TextView t = new TextView(getContext());
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setTextSize(15);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    /** The call starts and this screen runs until it is over. */
    void begin() {
        if (call != null) return;
        finished = false;
        startedAt = SystemClock.elapsedRealtime();
        userLine.setText("");
        termantLine.setText("");
        workLine.setText("");
        workLine.setTextColor(ACCENT);
        timer.setText("0:00");
        call = new LiveCall(getContext(), new LiveCall.Listener() {
            @Override
            public void onState(String state) {
                if ("connecting".equals(state)) orb.setLive(false);
                else orb.setLive(true);
                orb.setSpeaking("speaking".equals(state));
                orb.setWorking("working".equals(state));
            }

            @Override
            public void onUserLine(String text) {
                userLine.setText(shorten(text));
            }

            @Override
            public void onTermantLine(String text) {
                termantLine.setText(shorten(text));
            }

            @Override
            public void onWorking(String command) {
                workLine.setText(getContext().getString(R.string.live_working, shorten(command)));
            }

            @Override
            public void onDone(String reason) {
                endCall(reason);
            }
        });
        call.start();
        Runnable ticker = new Runnable() {
            @Override
            public void run() {
                if (finished) return;
                long s = (SystemClock.elapsedRealtime() - startedAt) / 1000;
                timer.setText((s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60));
                main.postDelayed(this, 1000);
            }
        };
        main.post(ticker);
    }

    private void endCall(final String reason) {
        if (finished) return;
        finished = true;
        final long millis = SystemClock.elapsedRealtime() - startedAt;
        if (call != null) {
            call.stop();
            call = null;
        }
        orb.setWorking(false);
        orb.setSpeaking(false);
        if (!reason.equals("ended")) {
            workLine.setTextColor(ERROR);
            workLine.setText(reason.equals("quota") ? getString(R.string.live_quota)
                    : reason.equals("no-key") ? getString(R.string.live_no_key)
                    : getString(R.string.live_error, reason));
        }
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                setVisibility(GONE);
                host.onLiveEnded(reason, millis);
            }
        }, reason.equals("ended") ? 400 : 2600);
    }

    private static String shorten(String s) {
        String t = (s == null ? "" : s).trim();
        return t.length() > 160 ? t.substring(0, 160) + "..." : t;
    }

    // -------------------------------------------------- the orb

    /** A disc that breathes, three rings that push out, a comet while it works. */
    private final class OrbView extends View {
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arcRect = new RectF();
        private boolean live;
        private boolean speaking;
        private boolean working;
        private boolean spinning;

        OrbView(Context context) {
            super(context);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(density * 2.4f);
            spinning = true;
        }

        void setLive(boolean v) {
            live = v;
        }

        void setSpeaking(boolean v) {
            speaking = v;
        }

        void setWorking(boolean v) {
            working = v;
        }

        void stop() {
            spinning = false;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!spinning) return;
            postInvalidateOnAnimation();
            long t = SystemClock.uptimeMillis();
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float base = Math.min(getWidth(), getHeight()) / 2f - dp(14);
            int color = live ? ACCENT : MUTED;
            // the glow: it swells while Termant speaks
            float amp = speaking ? 0.28f : (working ? 0.18f : 0.08f);
            float pulse = 1f + amp * (float) Math.sin(t / (speaking ? 170 : 430));
            glow.setShader(new RadialGradient(cx, cy, base * 1.7f * pulse, color, 0x00000000,
                    Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, base * 1.7f * pulse, glow);
            glow.setShader(null);
            // the core
            core.setColor(color);
            core.setAlpha((int) (180 + 75 * Math.sin(t / 260)));
            canvas.drawCircle(cx, cy, base * 0.62f * (1f + 0.05f * (float) Math.sin(t / 240)), core);
            // the rings: three, pushing out, faster while it speaks
            float[] frac = {0.78f, 1.0f, 1.22f};
            int[] alpha = {200, 120, 60};
            long[] period = {speaking ? 420 : 900, speaking ? 560 : 1200, speaking ? 720 : 1500};
            for (int i = 0; i < 3; i++) {
                float r = base * frac[i] * (1f + 0.09f * (float) Math.abs(Math.sin(t / period[i])));
                ring.setColor(color);
                ring.setAlpha(alpha[i]);
                canvas.drawCircle(cx, cy, r, ring);
            }
            // the comet: a command is running
            if (working) {
                arcRect.set(cx - base * 0.88f, cy - base * 0.88f, cx + base * 0.88f, cy + base * 0.88f);
                float start = (t / 6f) % 360;
                ring.setColor(ACCENT);
                ring.setAlpha(255);
                ring.setStrokeWidth(density * 3.4f);
                canvas.drawArc(arcRect, start, 96, false, ring);
            }
        }
    }
}
