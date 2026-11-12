package com.ozak.inventory;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.*;

/** Small framework-only UI kit (no extra dependencies): logo colours, cards, chips, buttons, progress bars. */
final class Ui {
    private Ui() {}
    static final int NAVY = 0xFF0B2D6B, NAVY2 = 0xFF163E8C, ORANGE = 0xFFF26B1D, BG = 0xFFF3F5F9, SURFACE = 0xFFFFFFFF,
        LINE = 0xFFE4E8F0, TEXT = 0xFF121826, MUTED = 0xFF667085, FAINT = 0xFF98A2B3,
        GREEN = 0xFF16A34A, GREEN_BG = 0xFFE8F7EE, RED = 0xFFDC2626, RED_BG = 0xFFFDECEC,
        AMBER = 0xFFB45309, AMBER_BG = 0xFFFFF4E0, BLUE_BG = 0xFFEAF0FB, GREY_BG = 0xFFF2F4F7, ORANGE_BG = 0xFFFFF1E8;
    static final int PRIMARY = 0, OUTLINE = 1, DANGER = 2, SUCCESS = 3, TONAL = 4, TEXT_BTN = 5;

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    /** Edge-to-edge with white status-bar icons (navy app bar) and dark navigation-bar icons. */
    static void systemBars(Activity a) {
        Window w = a.getWindow(); w.getDecorView(); w.setDecorFitsSystemWindows(false);
        w.setStatusBarColor(Color.TRANSPARENT); w.setNavigationBarColor(Color.TRANSPARENT);
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG));
        WindowInsetsController ic = w.getInsetsController();
        if (ic != null) ic.setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
    }

    static GradientDrawable shape(Context c, int fill, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable(); g.setColor(fill); g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) g.setStroke(dp(c, 1.5f), stroke); return g;
    }
    static Drawable pressable(Drawable d) { return new RippleDrawable(ColorStateList.valueOf(0x1F0B2D6B), d, null); }
    static Typeface font(int w) { return Typeface.create(Typeface.DEFAULT, w, false); }

    static TextView text(Context c, CharSequence s, float sp, int color, int weight) {
        TextView t = new TextView(c); t.setText(s); t.setTextSize(sp); t.setTextColor(color); t.setTypeface(font(weight));
        t.setLineSpacing(0, 1.12f); return t;
    }
    static TextView oneLine(TextView t) { t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END); return t; }

    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(shape(c, SURFACE, 18, 0)); l.setElevation(dp(c, 1.5f));
        l.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14)); return l;
    }
    static LinearLayout row(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    static LinearLayout.LayoutParams block(Context c, int topDp) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(c, topDp); return p; }
    static LinearLayout.LayoutParams weight(float w) { return new LinearLayout.LayoutParams(0, -2, w); }

    static TextView chip(Context c, String s, int fg, int bg) {
        TextView t = text(c, s, 11, fg, 700); t.setBackground(shape(c, bg, 8, 0));
        t.setPadding(dp(c, 9), dp(c, 4), dp(c, 9), dp(c, 4)); t.setLetterSpacing(0.02f); return t;
    }
    /** Rounded icon square used on tiles and list headers. */
    static TextView icon(Context c, String glyph, int bg, int sizeDp) {
        TextView t = text(c, glyph, sizeDp * 0.48f, TEXT, 600); t.setGravity(Gravity.CENTER);
        t.setBackground(shape(c, bg, sizeDp * 0.32f, 0)); t.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp))); return t;
    }
    static Button button(Context c, String s, int kind, boolean enabled, Runnable action) {
        Button b = new Button(c); b.setText(s); b.setAllCaps(false); b.setTextSize(14.5f); b.setTypeface(font(600));
        b.setStateListAnimator(null); b.setElevation(0); b.setMinHeight(dp(c, 48)); b.setMinimumHeight(dp(c, 48));
        b.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        int fill, fg, stroke = 0;
        switch (kind) {
            case PRIMARY: fill = NAVY; fg = Color.WHITE; break;
            case SUCCESS: fill = GREEN; fg = Color.WHITE; break;
            case DANGER: fill = SURFACE; fg = RED; stroke = 0xFFF5C2C2; break;
            case TONAL: fill = BLUE_BG; fg = NAVY; break;
            case TEXT_BTN: fill = Color.TRANSPARENT; fg = NAVY; break;
            default: fill = SURFACE; fg = NAVY; stroke = 0xFFC9D3E6;
        }
        b.setTextColor(fg); b.setBackground(pressable(shape(c, fill, 14, stroke)));
        b.setEnabled(enabled); b.setAlpha(enabled ? 1f : 0.4f);
        if (action != null) b.setOnClickListener(v -> action.run()); return b;
    }
    /** Thin rounded progress bar, fraction 0..1. */
    static View progress(Context c, float fraction, int color) {
        LinearLayout track = new LinearLayout(c); track.setBackground(shape(c, 0xFFEEF1F6, 99, 0));
        track.setClipToOutline(true);
        float f = Math.max(0f, Math.min(1f, fraction));
        View fill = new View(c); fill.setBackground(shape(c, color, 99, 0));
        track.addView(fill, new LinearLayout.LayoutParams(0, -1, f)); track.addView(new View(c), new LinearLayout.LayoutParams(0, -1, 1f - f));
        track.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(c, 6))); return track;
    }
    static EditText input(Context c, String hint) {
        EditText e = new EditText(c); e.setHint(hint); e.setTextSize(14.5f); e.setTextColor(TEXT); e.setHintTextColor(FAINT);
        e.setBackground(shape(c, SURFACE, 12, LINE)); e.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12)); return e;
    }
    static View divider(Context c) { View v = new View(c); v.setBackgroundColor(LINE); v.setLayoutParams(new LinearLayout.LayoutParams(-1, 1)); return v; }
    static View space(Context c, int dp) { View v = new View(c); v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, dp))); return v; }
    /** Coloured notice strip (amber / blue / red / green). */
    static TextView notice(Context c, String s, int fg, int bg) {
        TextView t = text(c, s, 12.5f, fg, 500); t.setBackground(shape(c, bg, 14, 0));
        t.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10)); return t;
    }
}
