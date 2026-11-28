package com.termant.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
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
        top.addView(title, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        timer = new TextView(context);
        timer.setText("0:00");
        timer.setTextColor(MUTED);
        timer.setTextSize(14);
        timer.setPadding(dp(12), 0, 0, 0);
        top.addView(timer, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        addView(top, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // the middle: the orb
        FrameLayout mid = new FrameLayout(context);
        orb = new OrbView(context);
        mid.addView(orb, new FrameLayout.LayoutParams(dp(190), dp(190), Gravity.CENTER));
        addView(mid, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));

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
        lines.addView(termantLine, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        lines.addView(workLine, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        addView(lines, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

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
        endRow.addView(end, new LayoutParams(LayoutParams.WRAP_CONTENT, dp(52)));
        addView(endRow, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
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
            workLine.setText(reason.equals("quota") ? getResources().getString(R.string.live_quota)
                    : reason.equals("no-key") ? getResources().getString(R.string.live_no_key)
                    : getResources().getString(R.string.live_error, reason));
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

    /**
     * The heart of the call: a white-hot core, a halo of voice that speaks in
     * bars, a ring that turns, and a comet when the work is running.
     */
    private final class OrbView extends View {
        private static final int BARS = 28;
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint comet = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arcRect = new RectF();
        private boolean live;
        private boolean speaking;
        private boolean working;
        private boolean spinning;

        OrbView(Context context) {
            super(context);
            bar.setStrokeCap(Paint.Cap.ROUND);
            bar.setStrokeWidth(density * 3f);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(density * 1.6f);
            comet.setStrokeCap(Paint.Cap.ROUND);
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

        /** 0 while it sleeps, 1 while it speaks, in between while it listens. */
        private float energy() {
            return speaking ? 1f : (working ? 0.7f : (live ? 0.35f : 0.15f));
        }

        private int withAlpha(int color, int alpha) {
            return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!spinning) return;
            postInvalidateOnAnimation();
            long t = SystemClock.uptimeMillis();
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float base = Math.min(getWidth(), getHeight()) * 0.28f;
            float e = energy();
            int color = live ? ACCENT : MUTED;
            float breath = (float) Math.sin(t / (speaking ? 150 : 420));

            // the halo: it swells with the voice
            float halo = base * 2.05f * (1f + 0.1f * e * breath);
            glow.setShader(new RadialGradient(cx, cy, halo,
                    new int[]{withAlpha(color, (int) (70 + 60 * e)),
                            withAlpha(color, (int) (30 + 22 * e)), 0x00000000},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, halo, glow);
            glow.setShader(null);

            // the bars: the voice, drawn round the orb, each with its own phase
            float arm = base * 1.28f;
            for (int i = 0; i < BARS; i++) {
                float w = (t / (speaking ? 90 : 260)) + i * 2.39996f;
                float lift = 0.5f + 0.5f * (float) Math.sin(w);
                float len = dp(2) + lift * dp(19) * e;
                float a = (float) Math.toRadians(i * 360f / BARS);
                float x = cx + (float) Math.cos(a) * arm;
                float y = cy + (float) Math.sin(a) * arm;
                bar.setColor(color);
                bar.setAlpha((int) (70 + 185 * lift * e));
                canvas.drawLine(x, y, x + (float) Math.cos(a) * len, y + (float) Math.sin(a) * len, bar);
            }

            // the core: white-hot at the middle, the theme colour at the rim
            float r = base * (1f + 0.05f * breath + (speaking ? 0.07f * Math.abs(breath) : 0f));
            core.setShader(new RadialGradient(cx, cy, r,
                    new int[]{0xFFFBF6EC, withAlpha(color, 235)},
                    new float[]{0f, 0.62f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r, core);
            core.setShader(null);

            // the ring: it turns, faster when there is work
            ring.setColor(color);
            ring.setAlpha(live ? 150 : 80);
            ring.setPathEffect(new DashPathEffect(new float[]{dp(2), dp(8)}, t / (working ? 5f : 14f)));
            canvas.drawCircle(cx, cy, base * 1.12f, ring);
            ring.setPathEffect(null);

            // the comet: a command is running
            if (working) {
                float orbit = base * 1.55f;
                float ang = t / 9f;
                comet.setColor(ACCENT);
                comet.setAlpha(200);
                comet.setStrokeWidth(density * 3.6f);
                arcRect.set(cx - orbit, cy - orbit, cx + orbit, cy + orbit);
                canvas.drawArc(arcRect, ang - 120f, 120f, false, comet);
                float hx = cx + (float) Math.cos((float) Math.toRadians(ang)) * orbit;
                float hy = cy + (float) Math.sin((float) Math.toRadians(ang)) * orbit;
                canvas.drawCircle(hx, hy, density * 4.6f, comet);
            }
        }
    }
}
