package com.termux.app.agent;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.agentdroid.agent.ThemeColors;

/** Small view factory in the Obsidian look: cards, buttons, chips, switch rows. Everything takes its colours from {@link ThemeColors}. */
public final class Ui {
    public enum Kind { FILLED, TONAL, OUTLINE, DANGER }

    public final Context ctx;
    public final ThemeColors t;
    private final float density;

    public Ui(Context c) {
        ctx = c;
        t = AppTheme.colors(c);
        density = c.getResources().getDisplayMetrics().density;
    }

    public int dp(float v) { return Math.round(v * density); }

    public GradientDrawable shape(int fill, float radiusDp, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(Math.max(1, dp(strokeDp)), stroke);
        return g;
    }

    public android.widget.ImageView icon(int res, int sizeDp, int color) {
        android.widget.ImageView v = new android.widget.ImageView(ctx);
        v.setImageResource(res);
        v.setColorFilter(color);
        v.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        int p = dp((48 - sizeDp) / 2f);
        v.setPadding(0, 0, 0, 0);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    /** A tappable round icon button 48dp wide. */
    public android.widget.FrameLayout iconButton(int res, int color, CharSequence desc, View.OnClickListener l) {
        android.widget.FrameLayout f = new android.widget.FrameLayout(ctx);
        android.widget.ImageView i = new android.widget.ImageView(ctx);
        i.setImageResource(res);
        i.setColorFilter(color);
        f.addView(i, new android.widget.FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        f.setContentDescription(desc);
        f.setClickable(true);
        f.setOnClickListener(l);
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return f;
    }

    public GradientDrawable bubbleShape(int fill, float tl, float tr, float br, float bl) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadii(new float[]{dp(tl), dp(tl), dp(tr), dp(tr), dp(br), dp(br), dp(bl), dp(bl)});
        return g;
    }

    public TextView text(CharSequence s, float sp, int color, boolean bold) {
        TextView v = new TextView(ctx);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    public TextView title(CharSequence s) { return text(s, 28, t.on, true); }

    public TextView section(CharSequence s) {
        TextView v = text(s.toString().toUpperCase(), 12, t.on2, true);
        v.setLetterSpacing(0.08f);
        v.setPadding(dp(4), dp(8), 0, dp(4));
        return v;
    }

    public LinearLayout column() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public LinearLayout card() {
        LinearLayout l = column();
        l.setBackground(shape(t.s1, 22, t.line, 1));
        l.setPadding(dp(16), dp(14), dp(16), dp(14));
        return l;
    }

    public LinearLayout.LayoutParams gap(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        return p;
    }

    public TextView button(CharSequence label, Kind kind, View.OnClickListener l) {
        int fill, fg, stroke = 0;
        switch (kind) {
            case FILLED: fill = t.p; fg = t.onp; break;
            case DANGER: fill = t.err2; fg = t.err; break;
            case OUTLINE: fill = 0; fg = t.on; stroke = t.line; break;
            default: fill = t.pc; fg = t.onpc; break;
        }
        TextView b = text(label, 15, fg, true);
        b.setGravity(Gravity.CENTER);
        b.setAllCaps(false);
        b.setMinHeight(dp(48));
        b.setPadding(dp(22), 0, dp(22), 0);
        b.setBackground(shape(fill, 24, stroke, 1));
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    public TextView chip(CharSequence label, boolean selected, View.OnClickListener l) {
        TextView c = text(label, 13.5f, selected ? t.onpc : t.on, selected);
        c.setGravity(Gravity.CENTER);
        c.setMinHeight(dp(40));
        c.setPadding(dp(16), 0, dp(16), 0);
        c.setBackground(shape(selected ? t.pc : 0, 20, selected ? 0 : t.line, 1));
        c.setClickable(true);
        c.setOnClickListener(l);
        return c;
    }

    public TextView statusChip(CharSequence label, boolean on) {
        TextView c = text(label, 12.5f, on ? t.onpc : t.on2, on);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(dp(12), 0, dp(12), 0);
        c.setBackground(shape(on ? t.pc : t.s2, 15, 0, 0));
        return c;
    }

    public void styleChip(TextView c, CharSequence label, boolean on) {
        c.setText((on ? "\u25CF " : "\u25CB ") + label);
        c.setTextColor(on ? t.onpc : t.on2);
        c.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        c.setBackground(shape(on ? t.pc : t.s2, 15, 0, 0));
    }

    public Switch toggle(boolean checked, final OnChecked l) {
        Switch sw = new Switch(ctx);
        sw.setChecked(checked);
        int[][] st = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(st, new int[]{t.onp, t.on2}));
        sw.setTrackTintList(new ColorStateList(st, new int[]{t.p, t.s3}));
        if (l != null) sw.setOnCheckedChangeListener((b, c) -> l.on(c));
        return sw;
    }

    /** Row with a rounded icon box, title, subtitle and a trailing view (switch, chevron or nothing). */
    public LinearLayout iconRow(int icon, CharSequence name, CharSequence sub, View trailing, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(ctx);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(60));
        r.setPadding(0, dp(6), 0, dp(6));
        android.widget.FrameLayout box = new android.widget.FrameLayout(ctx);
        box.setBackground(shape(t.s3, 12, 0, 0));
        android.widget.ImageView iv = new android.widget.ImageView(ctx);
        iv.setImageResource(icon);
        iv.setColorFilter(t.on);
        box.addView(iv, new android.widget.FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(36), dp(36));
        bl.rightMargin = dp(12);
        r.addView(box, bl);
        LinearLayout texts = column();
        texts.addView(text(name, 15, t.on, true));
        if (sub != null && sub.length() > 0) texts.addView(text(sub, 12.5f, t.on2, false));
        r.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (trailing != null) r.addView(trailing);
        if (click != null) { r.setClickable(true); r.setOnClickListener(click); }
        return r;
    }

    public View chevron() { return icon(com.termux.R.drawable.ic_next, 20, t.on2); }

    /** Square-ish tile for the 2-column grids. */
    public LinearLayout tile(int icon, CharSequence name, CharSequence sub, boolean hi, View.OnClickListener click) {
        LinearLayout c = column();
        c.setBackground(shape(t.s1, 22, t.line, 1));
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        android.widget.FrameLayout box = new android.widget.FrameLayout(ctx);
        box.setBackground(shape(hi ? t.pc : t.s3, 14, 0, 0));
        android.widget.ImageView iv = new android.widget.ImageView(ctx);
        iv.setImageResource(icon);
        iv.setColorFilter(hi ? t.onpc : t.on);
        box.addView(iv, new android.widget.FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        c.addView(box, new LinearLayout.LayoutParams(dp(40), dp(40)));
        c.addView(text(name, 15, t.on, true), gap(10));
        c.addView(text(sub, 12, t.on2, false));
        c.setClickable(true);
        c.setOnClickListener(click);
        return c;
    }

    public interface OnChecked { void on(boolean checked); }

    /** Title, optional explanation and a switch on the right. */
    public LinearLayout switchRow(CharSequence title, CharSequence sub, boolean checked, final OnChecked l) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        LinearLayout texts = column();
        texts.addView(text(title, 15, t.on, true));
        if (sub != null && sub.length() > 0) texts.addView(text(sub, 12.5f, t.on2, false));
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(ctx);
        sw.setChecked(checked);
        int[][] st = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(st, new int[]{t.onp, t.on2}));
        sw.setTrackTintList(new ColorStateList(st, new int[]{t.p, t.s3}));
        sw.setOnCheckedChangeListener((b, c) -> l.on(c));
        row.addView(sw);
        return row;
    }

    public View divider() {
        View v = new View(ctx);
        v.setBackgroundColor(t.line);
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        return v;
    }
}
