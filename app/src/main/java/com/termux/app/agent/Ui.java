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
