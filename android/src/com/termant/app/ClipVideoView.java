package com.termant.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The video section: a CapCut-like timeline. The video sits on the track, the kept part is
 * marked in the accent colour, its two ends drag, words and music are added, the look is
 * turned, and "Make it" runs `clip` in Ubuntu, which builds the finished video and puts a
 * copy in the phone's gallery.
 */
final class ClipVideoView extends LinearLayout {
    interface Host {
        void onCloseClip();

        /** MainActivity opens the system picker for a video (video/*). */
        void onPickVideo();

        /** MainActivity opens the system picker for a song (audio/*). */
        void onPickMusic();

        /** MainActivity opens the system picker for a background (a photo or a video). */
        void onPickBackground();
    }

    /** A word on the video; the times are the finished video's own seconds. */
    private static final class Word {
        double from, to;
        String text, place;

        Word(double from, double to, String text, String place) {
            this.from = from;
            this.to = to;
            this.text = text;
            this.place = place;
        }
    }

    /** Runs one command in Ubuntu and says what it said. */
    private interface Job {
        void done(int exit, String out);
    }

    private final int BG = Theme.bg(), SURFACE = Theme.surface(),
            SURFACE_HIGH = Theme.surfaceHigh(), TEXT = Theme.text(), MUTED = Theme.muted(),
            ACCENT = Theme.accent(), ON_ACCENT = Theme.onAccent(), ERROR = Theme.error();

    private final Host host;
    private final float density;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final android.widget.VideoView player;

    // the plan, in the source video's seconds, as the timeline holds it
    private String source = null;       // the guest path, e.g. /root/uploads/a.mp4
    private double duration = 0;
    private double start = 0, end = 0;
    private double playhead = 0;        // where the preview is now
    private float speed = 1;
    private String place = "bottom";   // where the next word goes
    private final List<Word> words = new ArrayList<>();
    private String music = null;
    private float musicVolume = 1;
    private boolean keepOwn = true;
    private float brightness = 0, contrast = 1, saturation = 1, sepia = 0;
    private int rotate = 0;
    private String flip = null;         // "h" or "v"
    private String aspect = null;       // one of clip's aspect names
    // the screen, the speed curve
    private boolean screenOn;
    private String keyColor = "#00ff00";
    private String bg = null;           // the guest path of the background
    private final List<double[]> curvePoints = new ArrayList<>();  // {the kept part's second, the rate}
    private boolean ffmpegMissing;
    private boolean busy;

    private TextView status, keptLabel, sourceName;
    private LinearLayout browse, wordsList, musicBlock, ffmpegBlock;
    private TextView musicName, wordsButton, placeLabel, lightValue, contrastValue, saturationValue;
    private EditText wordsEdit;
    private SeekBar musicBar;
    private Switch sepiaSwitch, ownSwitch;
    private View aspectChips, speedChips, flipButton, exportButton, keyChips, screenButton,
            bgButton, bgRemove, curveButton;
    private LinearLayout bgRow, curveList;
    private TextView bgName;
    private CurveStrip curveStrip;
    private TimelineView timeline;

    ClipVideoView(Context context, Host host) {
        super(context);
        this.host = host;
        density = getResources().getDisplayMetrics().density;
        player = new android.widget.VideoView(context);
        setOrientation(VERTICAL);
        setBackgroundColor(BG);
        build(context);
        setExport(false);
        ui.post(sync); // the playhead follows the preview
    }

    private int dp(float v) {
        return (int) (v * density);
    }

    private Drawable rounded(int color, float radiusDp) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusDp * density);
        return d;
    }

    private void build(Context context) {
        // the top bar: back, the name, the Make-it button
        LinearLayout bar = new LinearLayout(context);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(6), dp(10), dp(6));
        ImageView back = Ui.iconButton(context, R.drawable.ic_back,
                context.getString(R.string.chat_back_description), TEXT, 40, 22);
        back.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                host.onCloseClip();
            }
        });
        bar.addView(back, new LayoutParams(dp(40), dp(40)));
        TextView title = new TextView(context);
        title.setText(R.string.video_title);
        title.setTextColor(TEXT);
        title.setTextSize(Ui.TITLE);
        title.setTypeface(Ui.medium());
        title.setPadding(dp(8), 0, dp(8), 0);
        bar.addView(title, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        exportButton = new TextView(context);
        ((TextView) exportButton).setText(R.string.video_export);
        ((TextView) exportButton).setTextColor(ON_ACCENT);
        ((TextView) exportButton).setTextSize(Ui.LABEL);
        ((TextView) exportButton).setTypeface(Ui.medium());
        ((TextView) exportButton).setGravity(Gravity.CENTER);
        ((TextView) exportButton).setBackground(Ui.chip(context, ACCENT, 14));
        ((TextView) exportButton).setPadding(dp(16), dp(9), dp(16), dp(9));
        exportButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                export();
            }
        });
        bar.addView(exportButton, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(bar);

        ScrollView scroll = new ScrollView(context);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(VERTICAL);
        content.setPadding(dp(14), 0, dp(14), dp(24));

        // where the video comes from
        content.addView(chip(context, context.getString(R.string.video_choose), true, new OnClickListener() {
            @Override
            public void onClick(View v) {
                host.onPickVideo();
            }
        }));
        browse = new LinearLayout(context);
        browse.setOrientation(HORIZONTAL);
        browse.setPadding(0, dp(8), 0, 0);
        content.addView(browse);

        sourceName = new TextView(context);
        sourceName.setTextColor(MUTED);
        sourceName.setTextSize(Ui.CAPTION);
        sourceName.setPadding(0, dp(10), 0, dp(2));
        content.addView(sourceName);

        // the preview
        FrameLayout box = new FrameLayout(context);
        box.setBackground(rounded(SURFACE, 14));
        box.setClipToOutline(true);
        player.setBackgroundColor(0xff000000);
        player.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(android.media.MediaPlayer mp) {
                timeline.seekTo(0);
            }
        });
        box.addView(player, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(box, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));

        // the timeline
        timeline = new TimelineView(context);
        content.addView(timeline, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(118)));
        keptLabel = new TextView(context);
        keptLabel.setTextColor(MUTED);
        keptLabel.setTextSize(Ui.CAPTION);
        keptLabel.setPadding(0, dp(6), 0, dp(4));
        content.addView(keptLabel);

        // the speed of the kept part
        final float[] speeds = {0.5f, 1f, 2f, 4f};
        speedChips = chipRow(context, new String[]{"0.5\u00d7", "1\u00d7", "2\u00d7", "4\u00d7"}, 1, new OnClickListener() {
            @Override
            public void onClick(View v) {
                speed = speeds[((ViewGroup) v.getParent()).indexOfChild(v)];
                refreshKept();
            }
        });
        sectionHead(context, content, context.getString(R.string.video_speed), speedChips);

        // words on the video
        wordsEdit = new EditText(context);
        wordsEdit.setHint(R.string.video_words_hint);
        wordsEdit.setTextColor(TEXT);
        wordsEdit.setHintTextColor(MUTED);
        wordsEdit.setTextSize(Ui.LABEL);
        wordsEdit.setSingleLine(true);
        wordsEdit.setBackground(rounded(SURFACE, 10));
        wordsEdit.setPadding(dp(12), dp(8), dp(12), dp(8));
        wordsEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        content.addView(wordsEdit, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout wrow = new LinearLayout(context);
        wrow.setGravity(Gravity.CENTER_VERTICAL);
        wrow.setPadding(0, dp(8), 0, 0);
        placeLabel = new TextView(context);
        placeLabel.setText("\u2304 bottom");
        placeLabel.setTextColor(TEXT);
        placeLabel.setTextSize(Ui.LABEL);
        placeLabel.setTypeface(Ui.medium());
        placeLabel.setPadding(dp(12), dp(8), dp(12), dp(8));
        placeLabel.setBackground(Ui.chip(context, SURFACE_HIGH, 12));
        placeLabel.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                String p = "bottom".equals(place) ? "center" : "center".equals(place) ? "top" : "bottom";
                place = p;
                placeLabel.setText("\u2304 " + p);
            }
        });
        wrow.addView(placeLabel);
        wordsButton = new TextView(context);
        wordsButton.setText(getContext().getString(R.string.video_add_word, fmt(0)));
        wordsButton.setTextColor(ON_ACCENT);
        wordsButton.setTextSize(Ui.LABEL);
        wordsButton.setTypeface(Ui.medium());
        wordsButton.setGravity(Gravity.CENTER);
        wordsButton.setBackground(Ui.chip(context, ACCENT, 12));
        wordsButton.setPadding(dp(12), dp(8), dp(12), dp(8));
        wordsButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                addWord();
            }
        });
        wrow.addView(wordsButton, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        content.addView(wrow);
        wordsList = new LinearLayout(context);
        wordsList.setOrientation(VERTICAL);
        wordsList.setPadding(0, dp(6), 0, 0);
        content.addView(wordsList);

        // music under the video
        sectionHead(context, content, context.getString(R.string.video_music), null);
        musicBlock = new LinearLayout(context);
        musicBlock.setOrientation(VERTICAL);
        musicBlock.addView(chip(context, context.getString(R.string.video_music_choose), false, new OnClickListener() {
            @Override
            public void onClick(View v) {
                host.onPickMusic();
            }
        }));
        musicName = new TextView(context);
        musicName.setTextColor(MUTED);
        musicName.setTextSize(Ui.CAPTION);
        musicName.setPadding(0, dp(6), 0, 0);
        musicBlock.addView(musicName);
        musicBar = new SeekBar(context);
        musicBar.setMax(200);
        musicBar.setProgress(100);
        musicBar.setOnSeekBarChangeListener(seekChange(new Runnable() {
            @Override
            public void run() {
                musicVolume = musicBar.getProgress() / 100f;
            }
        }));
        musicBlock.addView(musicBar, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ownSwitch = new Switch(context);
        ownSwitch.setText(R.string.video_own_sound);
        ownSwitch.setTextColor(MUTED);
        ownSwitch.setTextSize(Ui.CAPTION);
        ownSwitch.setChecked(true);
        ownSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                keepOwn = isChecked;
            }
        });
        ownSwitch.setPadding(0, dp(6), 0, 0);
        musicBlock.addView(ownSwitch);
        content.addView(musicBlock);

        // the look
        sectionHead(context, content, context.getString(R.string.video_look), null);
        lightValue = new TextView(context);
        contrastValue = new TextView(context);
        saturationValue = new TextView(context);
        lookBar(context, content, R.string.video_brightness, -100, 100, 0, lightValue, new LookMove() {
            @Override
            public void moved(int progress) {
                brightness = progress / 100f;
            }
        });
        lookBar(context, content, R.string.video_contrast, 0, 300, 100, contrastValue, new LookMove() {
            @Override
            public void moved(int progress) {
                contrast = progress / 100f;
            }
        });
        lookBar(context, content, R.string.video_saturation, 0, 300, 100, saturationValue, new LookMove() {
            @Override
            public void moved(int progress) {
                saturation = progress / 100f;
            }
        });
        LinearLayout rrow = new LinearLayout(context);
        rrow.setGravity(Gravity.CENTER_VERTICAL);
        rrow.setPadding(0, dp(8), 0, 0);
        sepiaSwitch = new Switch(context);
        sepiaSwitch.setText(R.string.video_sepia);
        sepiaSwitch.setTextColor(MUTED);
        sepiaSwitch.setTextSize(Ui.CAPTION);
        sepiaSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                sepia = isChecked ? 0.6f : 0;
            }
        });
        rrow.addView(sepiaSwitch, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        rrow.addView(chip(context, context.getString(R.string.video_rotate), false, new OnClickListener() {
            @Override
            public void onClick(View v) {
                rotate = (rotate + 90) % 360;
                refreshKept();
            }
        }));
        flipButton = chip(context, context.getString(R.string.video_flip), false, new OnClickListener() {
            @Override
            public void onClick(View v) {
                flip = flip == null ? "h" : "h".equals(flip) ? "v" : null;
                ((TextView) flipButton).setText(flip == null
                        ? context.getString(R.string.video_flip)
                        : context.getString(R.string.video_flip) + " " + flip);
                refreshKept();
            }
        });
        rrow.addView(flipButton);
        content.addView(rrow);
        final String[] aspects = {null, "16:9", "9:16", "1:1", "4:3", "3:4"};
        final String[] aspectNames = {context.getString(R.string.video_original), "16:9", "9:16", "1:1", "4:3", "3:4"};
        aspectChips = chipRow(context, aspectNames, 0, new OnClickListener() {
            @Override
            public void onClick(View v) {
                aspect = aspects[((ViewGroup) v.getParent()).indexOfChild(v)];
                refreshKept();
            }
        });
        content.addView(aspectChips);

        // the screen: a colour is taken away, over a background (or over the black)
        sectionHead(context, content, context.getString(R.string.video_screen), null);
        final String[] keyColors = {"#00ff00", "#0000ff", "#ff00ff"};
        final String[] keyNames = {context.getString(R.string.video_key_green),
                context.getString(R.string.video_key_blue), context.getString(R.string.video_key_magenta)};
        keyChips = chipRow(context, keyNames, 0, new OnClickListener() {
            @Override
            public void onClick(View v) {
                keyColor = keyColors[((ViewGroup) v.getParent()).indexOfChild(v)];
            }
        });
        content.addView(keyChips);
        screenButton = chip(context, context.getString(R.string.video_screen_off), false, new OnClickListener() {
            @Override
            public void onClick(View v) {
                screenOn = !screenOn;
                refreshScreenButton();
            }
        });
        content.addView(screenButton, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bgRow = new LinearLayout(context);
        bgRow.setOrientation(HORIZONTAL);
        bgRow.setGravity(Gravity.CENTER_VERTICAL);
        bgRow.setPadding(0, dp(8), 0, 0);
        bgButton = chip(context, context.getString(R.string.video_bg_choose), false, new OnClickListener() {
            @Override
            public void onClick(View v) {
                host.onPickBackground();
            }
        });
        bgRow.addView(bgButton);
        bgName = new TextView(context);
        bgName.setTextColor(MUTED);
        bgName.setTextSize(Ui.CAPTION);
        bgName.setSingleLine(true);
        bgName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        bgName.setPadding(dp(10), 0, 0, 0);
        bgRow.addView(bgName, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bgRemove = chip(context, context.getString(R.string.video_bg_remove), false, new OnClickListener() {
            @Override
            public void onClick(View v) {
                bg = null;
                bgName.setText("");
                bgRemove.setVisibility(View.GONE);
            }
        });
        bgRemove.setVisibility(View.GONE);
        bgRow.addView(bgRemove);
        content.addView(bgRow);

        // the speed curve: tap the graph, and the seconds there go at the speed above
        sectionHead(context, content, context.getString(R.string.video_curve), null);
        curveStrip = new CurveStrip(context, 0.25, 4.0, curvePoints, new CurveStrip.OnTap() {
            @Override
            public void tappedAt(double fraction) {
                addCurvePoint(fraction);
            }
        });
        content.addView(curveStrip, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        TextView cnote = new TextView(context);
        cnote.setText(R.string.video_curve_note);
        cnote.setTextColor(MUTED);
        cnote.setTextSize(Ui.CAPTION);
        cnote.setPadding(0, dp(4), 0, dp(2));
        content.addView(cnote);
        curveButton = chip(context, context.getString(R.string.video_curve_clear), false, new OnClickListener() {
            @Override
            public void onClick(View v) {
                curvePoints.clear();
                refreshCurve();
            }
        });
        content.addView(curveButton, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        curveList = new LinearLayout(context);
        curveList.setOrientation(VERTICAL);
        curveList.setPadding(0, dp(6), 0, 0);
        content.addView(curveList);

        // what the export says
        status = new TextView(context);
        status.setTextSize(Ui.CAPTION);
        status.setLineSpacing(dp(2), 1);
        status.setPadding(0, dp(12), 0, 0);
        content.addView(status);

        // ffmpeg, when it is not in Ubuntu yet
        ffmpegBlock = new LinearLayout(context);
        ffmpegBlock.setOrientation(VERTICAL);
        TextView fnote = new TextView(context);
        fnote.setText(R.string.video_ffmpeg_missing);
        fnote.setTextColor(MUTED);
        fnote.setTextSize(Ui.CAPTION);
        ffmpegBlock.addView(fnote);
        ffmpegBlock.addView(chip(context, context.getString(R.string.video_install_ffmpeg), true, new OnClickListener() {
            @Override
            public void onClick(View v) {
                installFfmpeg();
            }
        }));
        ffmpegBlock.setVisibility(View.GONE);
        content.addView(ffmpegBlock);

        scroll.addView(content);
        addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    }

    private void sectionHead(Context context, LinearLayout content, String label, View right) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(14), 0, dp(6));
        TextView l = new TextView(context);
        l.setText(label);
        l.setTextColor(MUTED);
        l.setTextSize(Ui.CAPTION);
        l.setTypeface(Ui.medium());
        row.addView(l, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (right != null) row.addView(right);
        content.addView(row);
    }

    /** Told when a look slider moves; the progress is the bar's, already in its own scale. */
    private interface LookMove {
        void moved(int progress);
    }

    /** One look slider: a label, the bar, and the value it holds. */
    private void lookBar(Context context, LinearLayout content, int label, int min, int max, int init,
                         final TextView value, final LookMove onMove) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(VERTICAL);
        LinearLayout head = new LinearLayout(context);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = new TextView(context);
        l.setText(context.getString(label));
        l.setTextColor(MUTED);
        l.setTextSize(Ui.CAPTION);
        head.addView(l, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        value.setTextColor(MUTED);
        value.setTextSize(Ui.CAPTION);
        head.addView(value);
        row.addView(head);
        final SeekBar b = new SeekBar(context);
        b.setMax(max - min);
        b.setProgress(init - min);
        b.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText(String.format(java.util.Locale.US, "%.1f", (min + progress) / 100f));
                if (fromUser) onMove.moved(min + progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        row.addView(b, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        value.setText(String.format(java.util.Locale.US, "%.1f", (min + init) / 100f));
        content.addView(row);
    }

    private SeekBar.OnSeekBarChangeListener seekChange(final Runnable onMove) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) onMove.run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };
    }

    /** A chip the user presses; the tap does the job in the listener. */
    private View chip(Context context, String text, boolean accent, OnClickListener job) {
        TextView v = new TextView(context);
        v.setText(text);
        v.setTextColor(accent ? ON_ACCENT : TEXT);
        v.setTextSize(Ui.LABEL);
        v.setTypeface(Ui.medium());
        v.setGravity(Gravity.CENTER);
        v.setBackground(Ui.chip(context, accent ? ACCENT : SURFACE_HIGH, 12));
        v.setPadding(dp(12), dp(8), dp(12), dp(8));
        v.setOnClickListener(job);
        return v;
    }

    /** A row of chips; the one chosen is in the accent. */
    private View chipRow(Context context, final String[] names, int initial, final OnClickListener onPick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        final int[] chosen = {initial};
        for (int i = 0; i < names.length; i++) {
            final TextView v = new TextView(context);
            v.setText(names[i]);
            v.setTextSize(Ui.SMALL);
            v.setTypeface(Ui.medium());
            v.setGravity(Gravity.CENTER);
            v.setPadding(dp(10), dp(7), dp(10), dp(7));
            v.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View vv) {
                    chosen[0] = ((ViewGroup) vv.getParent()).indexOfChild(vv);
                    for (int k = 0; k < row.getChildCount(); k++) {
                        TextView c = (TextView) row.getChildAt(k);
                        c.setBackground(Ui.chip(context, k == chosen[0] ? ACCENT : SURFACE_HIGH, 12));
                        c.setTextColor(k == chosen[0] ? ON_ACCENT : TEXT);
                    }
                    onPick.onClick(vv);
                }
            });
            row.addView(v);
        }
        for (int k = 0; k < row.getChildCount(); k++) {
            TextView c = (TextView) row.getChildAt(k);
            c.setBackground(Ui.chip(context, k == chosen[0] ? ACCENT : SURFACE_HIGH, 12));
            c.setTextColor(k == chosen[0] ? ON_ACCENT : TEXT);
            if (k > 0) c.setPadding(dp(14), dp(7), dp(10), dp(7));
        }
        return row;
    }

    // ------------------------------------------------------------ the plan and the timeline

    /** Called each time the section opens: fresh looks at what videos there are. */
    void onOpen() {
        if (!Ubuntu.isInstalled(getContext())) {
            Toast.makeText(getContext(), R.string.video_not_ready, Toast.LENGTH_LONG).show();
            return;
        }
        guest("find /root -maxdepth 3 -type f \\( -iname '*.mp4' -o -iname '*.webm' -o -iname '*.mov' \\) "
                + "-printf '%T@ %p\\n' 2>/dev/null | sort -rn | head -8 | cut -d' ' -f2-", new Job() {
            @Override
            public void done(int exit, String out) {
                final String[] found = out.split("\n");
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        browse.removeAllViews();
                        int have = 0;
                        for (String path : found) {
                            if (path.trim().isEmpty()) continue;
                            have++;
                            final String p = path.trim();
                            TextView v = new TextView(getContext());
                            v.setText(p.substring(p.lastIndexOf('/') + 1));
                            v.setTextColor(TEXT);
                            v.setTextSize(Ui.SMALL);
                            v.setGravity(Gravity.CENTER);
                            v.setBackground(Ui.chip(getContext(), SURFACE_HIGH, 12));
                            v.setPadding(dp(8), dp(6), dp(8), dp(6));
                            if (have > 1) v.setPadding(dp(12), dp(6), dp(8), dp(6));
                            v.setOnClickListener(new OnClickListener() {
                                @Override
                                public void onClick(View vv) {
                                    loadSource(p);
                                }
                            });
                            browse.addView(v);
                        }
                    }
                });
            }
        });
    }

    /** The picked video becomes the one on the track. */
    void videoPicked(final String path) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                loadSource(path);
            }
        });
    }

    /** The picked song goes under the video. */
    void musicPicked(final String path) {
        music = path;
        ui.post(new Runnable() {
            @Override
            public void run() {
                musicName.setText(path.substring(path.lastIndexOf('/') + 1));
            }
        });
    }

    private void loadSource(final String path) {
        if (busy) return;
        source = path;
        timeline.setThumbs(null);
        timeline.setRange(0, 0, 0, 0);
        sourceName.setText(path + "  \u00b7  " + getContext().getString(R.string.video_thumbs));
        keptLabel.setText("");
        setStatus("", false);
        ffmpegBlock.setVisibility(View.GONE);
        setExport(false);
        guest("clip info '" + shell(path) + "'", new Job() {
            @Override
            public void done(int exit, String out) {
                if (exit != 0) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (out.contains("ffmpeg is not installed")) ffmpegMissing = true;
                            setStatus(getContext().getString(R.string.video_error, oneLine(out)), true);
                            ffmpegBlock.setVisibility(ffmpegMissing ? View.VISIBLE : View.GONE);
                            sourceName.setText(path);
                        }
                    });
                    return;
                }
                final double d = parseSeconds(out);
                if (d <= 0) return;
                guest("rm -rf /tmp/clipthumbs && clip thumbs '" + shell(path) + "' /tmp/clipthumbs 1", new Job() {
                    @Override
                    public void done(int exit, String out) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                duration = d;
                                start = 0;
                                end = d;
                                playhead = 0;
                                loadThumbs();
                                playPreview(path);
                                sourceName.setText(path + "  \u00b7  " + fmt(d));
                                refreshKept();
                                setExport(true);
                            }
                        });
                    }
                });
            }
        });
    }

    /** The little pictures of the video, one per second, straight from Ubuntu. */
    private void loadThumbs() {
        File folder = new File(Ubuntu.rootfs(getContext()), "tmp/clipthumbs");
        File[] files = folder.listFiles();
        final List<Bitmap> got = new ArrayList<>();
        if (files != null) {
            Arrays.sort(files);
            for (File f : files) {
                if (!f.getName().endsWith(".jpg")) continue;
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 2;
                Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                if (b != null) got.add(b);
            }
        }
        timeline.setThumbs(got);
    }

    private void playPreview(String guestPath) {
        File f = new File(Ubuntu.rootfs(getContext()), guestPath.substring(1));
        if (!f.isFile()) return;
        player.setVideoPath(f.getAbsolutePath());
        player.seekTo(0);
    }

    private void refreshKept() {
        keptLabel.setText(getContext().getString(R.string.video_kept) + " " + fmt(start) + " \u2013 " + fmt(end)
                + "  \u00b7  " + String.format(java.util.Locale.US, "%.1f", (end - start) / speed) + " s  \u00b7  "
                + String.format(java.util.Locale.US, "%g", (double) speed) + "\u00d7"
                + (rotate != 0 ? "  \u00b7  " + rotate + "\u00b0" : "")
                + (flip != null ? "  \u00b7  flip " + flip : "")
                + (aspect != null ? "  \u00b7  " + aspect : ""));
        timeline.setRange(start, end, playhead, duration);
        if (curveStrip != null) curveStrip.invalidate();
    }

    /** The preview's position, drawn on the track about five times a second. */
    private final Runnable sync = new Runnable() {
        @Override
        public void run() {
            if (duration > 0 && player.isPlaying()) {
                playhead = player.getCurrentPosition() / 1000.0;
                timeline.setRange(start, end, playhead, duration);
                wordsButton.setText(getContext().getString(R.string.video_add_word, fmt(playhead)));
            }
            ui.postDelayed(this, 200);
        }
    };

    private static double parseSeconds(String out) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\((\\d+(?:\\.\\d+)?) s\\)").matcher(out);
        return m.find() ? Double.parseDouble(m.group(1)) : 0;
    }

    static String fmt(double t) {
        long s = (long) t;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
    }

    private static String oneLine(String s) {
        return (s == null ? "" : s.replace('\n', ' ')).trim();
    }

    private static String shell(String s) {
        return s.replace("'", "'\\''");
    }

    private void setStatus(String text, boolean error) {
        status.setText(text);
        status.setTextColor(error ? ERROR : ACCENT);
    }

    private void setExport(boolean ok) {
        exportButton.setEnabled(ok);
        exportButton.setAlpha(ok ? 1 : 0.4f);
    }

    private void addWord() {
        String what = wordsEdit.getText().toString().trim();
        if (what.isEmpty()) {
            Toast.makeText(getContext(), R.string.video_words_hint, Toast.LENGTH_SHORT).show();
            return;
        }
        // the finished video's own time: from the start of the kept part, at the speed
        double p = Math.max(start, Math.min(end - 0.1, playhead));
        double from = (p - start) / speed;
        double to = from + 2 / speed;
        words.add(new Word(from, to, what, place));
        wordsEdit.setText("");
        refreshWords();
    }

    private void refreshWords() {
        wordsList.removeAllViews();
        for (Word w : words) {
            TextView v = new TextView(getContext());
            v.setText(fmt(w.from) + "  " + w.text + "  (" + w.place + ")");
            v.setTextColor(TEXT);
            v.setTextSize(Ui.SMALL);
            v.setPadding(dp(4), dp(3), 0, dp(3));
            TextView x = new TextView(getContext());
            x.setText("\u2715");
            x.setTextColor(MUTED);
            x.setTextSize(Ui.LABEL);
            x.setPadding(dp(8), dp(3), dp(4), dp(3));
            x.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View vv) {
                    words.remove(w);
                    refreshWords();
                }
            });
            LinearLayout row = new LinearLayout(getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(v, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(x);
            wordsList.addView(row);
        }
    }

    // ------------------------------------------------------------ making it

    private void export() {
        if (busy || source == null) return;
        busy = true;
        setExport(false);
        setStatus(getContext().getString(R.string.video_exporting, ""), false);
        String base = source.substring(source.lastIndexOf('/') + 1);
        base = base.replaceAll("\\.[A-Za-z0-9]{1,5}$", "");
        if (base.isEmpty()) base = "video";
        final String outPath = "/root/videos/" + base + "-edited.mp4";
        try {
            JSONObject edit = new JSONObject();
            JSONArray seg = new JSONArray().put(start).put(end).put(speed);
            edit.put("segments", new JSONArray().put(seg));
            if (!words.isEmpty()) {
                JSONArray w = new JSONArray();
                for (Word word : words) {
                    w.put(new JSONObject().put("from", word.from).put("to", word.to)
                            .put("text", word.text).put("place", word.place));
                }
                edit.put("texts", w);
            }
            if (music != null) {
                edit.put("music", music);
                edit.put("music_volume", musicVolume);
                if (!keepOwn) edit.put("mute", true);
            }
            if (brightness != 0) edit.put("brightness", brightness);
            if (contrast != 1) edit.put("contrast", contrast);
            if (saturation != 1) edit.put("saturation", saturation);
            if (sepia != 0) edit.put("sepia", sepia);
            if (rotate != 0) edit.put("rotate", rotate);
            if (flip != null) edit.put("flip", flip);
            if (aspect != null) edit.put("aspect", aspect);
            if (screenOn) edit.put("key_color", keyColor);
            if (bg != null) edit.put("background", bg);
            if (!curvePoints.isEmpty()) {
                JSONArray c = new JSONArray();
                for (double[] pt : curvePoints) c.put(new JSONArray().put(pt[0]).put(pt[1]));
                edit.put("speed_curve", c);
            }
            File jf = new File(Ubuntu.rootfs(getContext()), "tmp/clip-edit.json");
            FileOutputStream fo = new FileOutputStream(jf);
            try {
                fo.write(edit.toString().getBytes("UTF-8"));
            } finally {
                fo.close();
            }
        } catch (Exception e) {
            android.util.Log.w("Termant", "video: the plan could not be written", e);
            busy = false;
            setExport(true);
            setStatus(getContext().getString(R.string.video_error, String.valueOf(e)), true);
            return;
        }
        guest("mkdir -p /root/videos && clip edit '" + shell(source)
                + "' --json-file /tmp/clip-edit.json --out '" + shell(outPath) + "' ; phone gallery '" + shell(outPath) + "'", new Job() {
            @Override
            public void done(int exit, String out) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        // the video is made when clip says so; the gallery trip is a second thing
                        // (it has a 32 MB cap), said of its own
                        String saved = null, where = "", galleryNote = "";
                        for (String line : out.split("\n")) {
                            if (line.startsWith("Video saved:")) {
                                saved = line.substring("Video saved:".length()).trim().split(" \\(")[0].trim();
                            } else if (line.startsWith("A copy is in the phone's gallery:")) {
                                int at = line.indexOf(": ");
                                where = at >= 0 ? line.substring(at + 2).trim() : "";
                            } else if (line.startsWith("phone gallery:")) {
                                galleryNote = line.substring("phone gallery:".length()).trim();
                            }
                        }
                        busy = false;
                        if (saved == null) {
                            if (out.contains("ffmpeg is not installed")) {
                                ffmpegMissing = true;
                                ffmpegBlock.setVisibility(View.VISIBLE);
                            }
                            setStatus(getContext().getString(R.string.video_error, oneLine(out)), true);
                            setExport(true);
                            return;
                        }
                        playPreview(saved);
                        String place = where.isEmpty() ? "Movies/" + Gallery.FOLDER : where;
                        setStatus(getContext().getString(R.string.video_done, place)
                                + (galleryNote.isEmpty() ? "" : "\n" + galleryNote), false);
                        setExport(true);
                    }
                });
            }
        });
    }

    private void installFfmpeg() {
        if (busy) return;
        busy = true;
        setExport(false);
        setStatus(getContext().getString(R.string.video_install), false);
        guest("export DEBIAN_FRONTEND=noninteractive && apt-get update -qq && apt-get install -y -qq ffmpeg", new Job() {
            @Override
            public void done(int exit, String out) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        if (exit == 0) {
                            ffmpegMissing = false;
                            ffmpegBlock.setVisibility(View.GONE);
                            setStatus("", false);
                            if (source != null) loadSource(source);
                        } else {
                            setStatus(getContext().getString(R.string.video_error, oneLine(out)), true);
                            setExport(true);
                        }
                    }
                });
            }
        });
    }

    private void refreshScreenButton() {
        ((TextView) screenButton).setBackground(Ui.chip(getContext(), screenOn ? ACCENT : SURFACE_HIGH, 12));
        ((TextView) screenButton).setTextColor(screenOn ? ON_ACCENT : TEXT);
        ((TextView) screenButton).setText(getContext().getString(screenOn ? R.string.video_screen_on : R.string.video_screen_off));
    }

    /** The picked background goes under the video, and the screen is taken away. */
    void backgroundPicked(final String path) {
        bg = path;
        screenOn = true;
        ui.post(new Runnable() {
            @Override
            public void run() {
                bgName.setText(path.substring(path.lastIndexOf('/') + 1));
                bgRemove.setVisibility(View.VISIBLE);
                refreshScreenButton();
            }
        });
    }

    private void addCurvePoint(double fraction) {
        if (duration <= 0 || curvePoints.size() >= 12) return;
        double kept = Math.max(0.1, end - start);
        double t = fraction * kept;
        curvePoints.add(new double[]{t, speed});
        curvePoints.sort(new java.util.Comparator<double[]>() {
            @Override
            public int compare(double[] a, double[] b) {
                return Double.compare(a[0], b[0]);
            }
        });
        refreshCurve();
    }

    private void refreshCurve() {
        curveStrip.invalidate();
        curveList.removeAllViews();
        for (final double[] pt : curvePoints) {
            curveList.addView(pointRow(fmt(pt[0]) + "  ×"
                    + String.format(java.util.Locale.US, "%.2g", pt[1]), new OnClickListener() {
                @Override
                public void onClick(View v) {
                    curvePoints.remove(pt);
                    refreshCurve();
                }
            }));
        }
    }

    /** One point of a curve: its words, and the cross that takes it away. */
    private LinearLayout pointRow(String words, OnClickListener job) {
        TextView v = new TextView(getContext());
        v.setText(words);
        v.setTextColor(TEXT);
        v.setTextSize(Ui.SMALL);
        v.setPadding(dp(4), dp(3), 0, dp(3));
        TextView x = new TextView(getContext());
        x.setText("\u2715");
        x.setTextColor(MUTED);
        x.setTextSize(Ui.LABEL);
        x.setPadding(dp(8), dp(3), dp(4), dp(3));
        x.setOnClickListener(job);
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(v, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(x);
        return row;
    }

    // ------------------------------------------------------------ the curve's graph

    /**
     * The small graph of a curve: the seconds on the width, the value on the height (a log
     * scale, like the rates). A tap gives a fraction of the kept part.
     */
    private class CurveStrip extends View {
        interface OnTap {
            void tappedAt(double fraction);
        }

        private final double min, max;
        private final List<double[]> points;
        private final OnTap onTap;
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint curve = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);

        CurveStrip(Context c, double min, double max, List<double[]> points, OnTap onTap) {
            super(c);
            this.min = min;
            this.max = max;
            this.points = points;
            this.onTap = onTap;
            grid.setColor(SURFACE_HIGH);
            grid.setStrokeWidth(1.5f * density);
            curve.setColor(MUTED);
            curve.setStrokeWidth(2 * density);
            dot.setColor(ACCENT);
            setBackgroundColor(SURFACE);
        }

        private float pad() {
            return dp(6);
        }

        private float xOf(double t) {
            if (duration <= 0) return pad();
            return pad() + (float) (t / duration) * (getWidth() - 2 * pad());
        }

        private float yOf(double v) {
            float f = (float) ((Math.log(v) - Math.log(min)) / (Math.log(max) - Math.log(min)));
            f = Math.max(0, Math.min(1, f));
            return (getHeight() - pad()) - f * (getHeight() - 2 * pad());
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            canvas.drawRect(pad(), pad(), w - pad(), h - pad(), grid);
            if (duration <= 0) return;
            for (int i = 1; i < points.size(); i++) {
                double[] a = points.get(i - 1);
                double[] b = points.get(i);
                canvas.drawLine(xOf(a[0]), yOf(a[1]), xOf(b[0]), yOf(b[1]), curve);
            }
            for (double[] p : points) {
                canvas.drawCircle(xOf(p[0]), yOf(p[1]), 3.5f * density, dot);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN && duration > 0) {
                float f = (e.getX() - pad()) / (getWidth() - 2 * pad());
                onTap.tappedAt(Math.max(0, Math.min(1, f)));
                return true;
            }
            return super.onTouchEvent(e);
        }
    }

    // ------------------------------------------------------------ Ubuntu calls

    /** Runs one command in Ubuntu and reports what it said. */
    private void guest(final String script, final Job job) {
        final Context c = getContext();
        new Thread("termant-clip") {
            @Override
            public void run() {
                int exit = -1;
                StringBuilder out = new StringBuilder();
                try {
                    Ubuntu.prepareHost(c);
                    ProcessBuilder pb = new ProcessBuilder(Ubuntu.command(c, "/bin/bash", "-l", "-c", script));
                    Map<String, String> env = pb.environment();
                    env.clear();
                    for (String e : Ubuntu.hostEnvironment(c)) {
                        int eq = e.indexOf('=');
                        env.put(e.substring(0, eq), e.substring(eq + 1));
                    }
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    p.getOutputStream().close();
                    java.io.BufferedReader in = new java.io.BufferedReader(
                            new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                    String line;
                    while ((line = in.readLine()) != null) {
                        out.append(line).append('\n');
                        if (line.startsWith("progress ")) {
                            try {
                                final double value = Double.parseDouble(line.substring("progress ".length()));
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (busy) setStatus(getContext().getString(R.string.video_exporting,
                                                (int) (value * 100) + "%"), false);
                                    }
                                });
                            } catch (NumberFormatException e) {
                                // not a progress line after all
                            }
                        }
                    }
                    exit = p.waitFor();
                } catch (Exception e) {
                    out.append(e);
                }
                job.done(exit, out.toString());
            }
        }.start();
    }

    // ------------------------------------------------------------ the timeline itself

    /**
     * The track: the little pictures over it, the kept part in the accent, the two ends to
     * drag, and the playhead where the video is now. A touch on the track moves the playhead;
     * a touch on an end drags that end.
     */
    private class TimelineView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF clipRect = new RectF();
        private final Rect dest = new Rect(), src = new Rect();
        private List<Bitmap> shots = null;
        private double rangeStart = 0, rangeEnd = 0, head = 0, total = 0;
        private int mode = 0; // 0 nothing, 1 the start end, 2 the end end, 3 the playhead

        TimelineView(Context c) {
            super(c);
            dim.setColor(0x99000000);
            line.setColor(ACCENT);
            line.setStrokeWidth(2 * density);
        }

        void setThumbs(List<Bitmap> b) {
            shots = b;
            invalidate();
        }

        void setRange(double s, double e, double h, double d) {
            rangeStart = s;
            rangeEnd = e;
            head = h;
            total = d;
            invalidate();
        }

        void seekTo(double t) {
            head = t;
            invalidate();
            if (ClipVideoView.this.source != null) player.seekTo((int) (t * 1000));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            float top = dp(4), thumbH = dp(76), trackY = top + thumbH + dp(12), trackH = dp(20);
            clipRect.set(dp(2), top, w - dp(2), h - dp(2));
            paint.setColor(SURFACE);
            canvas.drawRoundRect(clipRect, 14 * density, 14 * density, paint);
            if (total <= 0) return;
            float pxPerSec = (float) (w / total);
            // the pictures, dark where they are not kept
            if (shots != null && !shots.isEmpty()) {
                float each = w / shots.size();
                for (int i = 0; i < shots.size(); i++) {
                    double from = i * (double) total / shots.size();
                    double to = (i + 1) * (double) total / shots.size();
                    dest.set((int) (i * each), (int) top, (int) ((i + 1) * each + 1), (int) (top + thumbH));
                    Bitmap shot = shots.get(i);
                    src.set(0, 0, shot.getWidth(), shot.getHeight());
                    canvas.drawBitmap(shot, src, dest, paint);
                    if (to <= rangeStart || from >= rangeEnd) canvas.drawRect(dest, dim);
                }
            }
            // the track and the kept part
            RectF track = new RectF(dp(2), trackY, w - dp(2), trackY + trackH);
            paint.setColor(SURFACE_HIGH);
            canvas.drawRoundRect(track, trackH / 2, trackH / 2, paint);
            paint.setColor(ACCENT);
            RectF kept = new RectF((float) (dp(2) + rangeStart * pxPerSec), (float) (trackY + dp(3)),
                    (float) (dp(2) + rangeEnd * pxPerSec), (float) (trackY + trackH - dp(3)));
            if (kept.width() < dp(6)) kept.set(kept.left - dp(3), kept.top, kept.left + dp(6), kept.bottom);
            canvas.drawRoundRect(kept, (trackH - dp(6)) / 2, (trackH - dp(6)) / 2, paint);
            // the two ends
            for (double at : new double[]{rangeStart, rangeEnd}) {
                float x = dp(2) + (float) (at * pxPerSec);
                canvas.drawRoundRect(new RectF(x - dp(5), trackY - dp(3), x + dp(5), trackY + trackH + dp(3)),
                        dp(4), dp(4), paint);
            }
            // the playhead
            float x = dp(2) + (float) (Math.max(0, Math.min(total, head)) * pxPerSec);
            canvas.drawLine(x, top, x, trackY + trackH, line);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (total <= 0) return false;
            float pxPerSec = (float) (getWidth() / total);
            double sx = rangeStart * pxPerSec + dp(2), ex = rangeEnd * pxPerSec + dp(2);
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (Math.abs(e.getX() - sx) < dp(16)) mode = 1;
                    else if (Math.abs(e.getX() - ex) < dp(16)) mode = 2;
                    else mode = 3;
                    move(e);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (mode != 0) {
                        move(e);
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (mode == 3) player.seekTo((int) (head * 1000));
                    mode = 0;
                    refreshKept();
                    return true;
            }
            return true;
        }

        private void move(MotionEvent e) {
            float pxPerSec = (float) (getWidth() / total);
            double t = Math.max(0, Math.min(total, (e.getX() - dp(2)) / pxPerSec));
            if (mode == 1) {
                start = Math.min(t, end - 0.1);
                refreshKept();
            } else if (mode == 2) {
                end = Math.max(t, start + 0.1);
                refreshKept();
            } else if (mode == 3) {
                head = Math.max(start, Math.min(end, t));
                playhead = head;
                setRange(start, end, head, total);
            }
        }
    }
}
