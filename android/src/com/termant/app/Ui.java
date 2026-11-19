package com.termant.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * The look of the app in one place: the sizes of text, the corners, cards,
 * icons and icon buttons. Every screen builds its views with these, so they
 * all match.
 */
final class Ui {
    private Ui() {
    }

    // text sizes, in sp
    static final float DISPLAY = 24, TITLE = 18, BODY = 15, LABEL = 14, SMALL = 13, CAPTION = 12;
    // corners, in dp
    static final float R_CARD = 16, R_ROW = 14, R_CHIP = 10;

    private static Typeface medium;

    /** Between regular and bold: titles and buttons. */
    static Typeface medium() {
        if (medium == null) medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        return medium;
    }

    static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable rounded(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    /** A filled shape with a hairline around it. */
    static GradientDrawable outlined(Context c, int color, int line, float radiusDp) {
        GradientDrawable d = rounded(c, color, radiusDp);
        d.setStroke(Math.max(1, dp(c, 1)), line);
        return d;
    }

    /** The background of a card: the surface colour and a hairline. */
    static Drawable card(Context c, float radiusDp) {
        return outlined(c, Theme.surface(), Theme.outline(), radiusDp);
    }

    /** A card that can be tapped. */
    static Drawable cardButton(Context c, float radiusDp) {
        return ripple(c, card(c, radiusDp), radiusDp);
    }

    /** A small filled shape with a hairline that can be tapped: chips and the like. */
    static Drawable chip(Context c, int color, float radiusDp) {
        return ripple(c, outlined(c, color, Theme.outline(), radiusDp), radiusDp);
    }

    /** A plain shape that can be tapped; a colour of 0 shows only the touch. */
    static Drawable pressable(Context c, int color, float radiusDp) {
        return ripple(c, rounded(c, color, radiusDp), radiusDp);
    }

    private static Drawable ripple(Context c, Drawable content, float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(Theme.ripple()), content, rounded(c, 0xffffffff, radiusDp));
    }

    /** One of the drawn icons (res/drawable/ic_*.xml) in a colour. */
    static Drawable icon(Context c, int icon, int color) {
        Drawable d = c.getDrawable(icon).mutate();
        d.setTint(color);
        return d;
    }

    /** An icon as a view of its own; sizeDp is the side of the picture. */
    static ImageView image(Context c, int icon, int color) {
        ImageView v = new ImageView(c);
        v.setImageDrawable(icon(c, icon, color));
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return v;
    }

    /**
     * A round button that shows only an icon. boxDp is the side of the button,
     * iconDp the side of the picture in it. The description is what a screen
     * reader says (and what the tests look for).
     */
    static ImageView iconButton(Context c, int icon, String description, int color, float boxDp, float iconDp) {
        ImageView v = image(c, icon, color);
        int pad = dp(c, (boxDp - iconDp) / 2);
        v.setPadding(pad, pad, pad, pad);
        v.setContentDescription(description);
        v.setClickable(true);
        v.setFocusable(true);
        v.setBackground(pressable(c, 0x00000000, boxDp / 2));
        return v;
    }

    /** Puts an icon before the text of a label, in the label's own line. */
    static void leading(TextView v, int icon, int color, float sizeDp, float gapDp) {
        Context c = v.getContext();
        Drawable d = icon(c, icon, color);
        d.setBounds(0, 0, dp(c, sizeDp), dp(c, sizeDp));
        Drawable[] now = v.getCompoundDrawablesRelative();
        v.setCompoundDrawablesRelative(d, null, now[2], null);
        v.setCompoundDrawablePadding(dp(c, gapDp));
    }

    /** Puts an icon after the text of a label. */
    static void trailing(TextView v, int icon, int color, float sizeDp, float gapDp) {
        Context c = v.getContext();
        Drawable d = icon(c, icon, color);
        d.setBounds(0, 0, dp(c, sizeDp), dp(c, sizeDp));
        Drawable[] now = v.getCompoundDrawablesRelative();
        v.setCompoundDrawablesRelative(now[0], null, d, null);
        v.setCompoundDrawablePadding(dp(c, gapDp));
    }

    /** An icon on a soft square of the accent colour: the mark at the start of a row. */
    static ImageView badge(Context c, int icon, float boxDp, float iconDp) {
        ImageView v = image(c, icon, Theme.accent());
        int pad = dp(c, (boxDp - iconDp) / 2);
        v.setPadding(pad, pad, pad, pad);
        v.setBackground(rounded(c, Theme.accentSoft(), boxDp * 0.3f));
        return v;
    }

    /** A filled button with words: the one thing to do on a screen. */
    static TextView primaryButton(Context c, CharSequence text) {
        TextView v = new TextView(c);
        v.setText(text);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(Theme.onAccent());
        v.setTextSize(LABEL);
        v.setTypeface(medium());
        v.setSingleLine(true);
        v.setClickable(true);
        v.setPadding(dp(c, 22), 0, dp(c, 22), 0);
        v.setBackground(pressable(c, Theme.accent(), 22));
        return v;
    }

    /** A hairline across a card, between two rows. */
    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Theme.outline());
        return v;
    }
}
