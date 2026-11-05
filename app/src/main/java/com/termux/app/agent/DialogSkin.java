package com.termux.app.agent;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.TextView;

import com.agentdroid.agent.ThemeColors;

/** Paints any AlertDialog in the app theme (surface, text, accent buttons, tinted inputs). Colours come from {@link AppTheme}. */
public final class DialogSkin {
    private DialogSkin() {}

    public static void attach(final AlertDialog d) {
        if (d.getWindow() == null) return;
        d.getWindow().getDecorView().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { apply(d); }
            @Override public void onViewDetachedFromWindow(View v) { }
        });
    }

    public static void apply(AlertDialog d) {
        final ThemeColors t = AppTheme.colors(d.getContext());
        final float dp = d.getContext().getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(t.s1);
        bg.setCornerRadius(28 * dp);
        bg.setStroke(Math.max(1, Math.round(dp)), t.line);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(bg);
            final View root = d.getWindow().getDecorView();
            paint(root, t);
            root.post(() -> paint(root, t));
        }
    }

    private static void paint(View v, ThemeColors t) {
        if (v instanceof EditText) {
            EditText e = (EditText) v;
            e.setTextColor(t.on);
            e.setHintTextColor(t.on2);
            e.setBackgroundTintList(ColorStateList.valueOf(t.line));
            e.setHighlightColor((t.p & 0xFFFFFF) | 0x55000000);
        } else if (v instanceof Button) {
            ((Button) v).setTextColor(t.p);
        } else if (v instanceof CheckBox || v instanceof RadioButton) {
            android.widget.CompoundButton c = (android.widget.CompoundButton) v;
            c.setTextColor(t.on);
            int[][] st = {{android.R.attr.state_checked}, {}};
            c.setButtonTintList(new ColorStateList(st, new int[]{t.p, t.on2}));
        } else if (v instanceof TextView) {
            TextView tv = (TextView) v;
            float sp = tv.getTextSize() / tv.getResources().getDisplayMetrics().scaledDensity;
            if (tv.getCurrentTextColor() != t.p && tv.getBackground() == null) tv.setTextColor(sp <= 13.5f ? t.on2 : t.on);
        } else if (v instanceof ListView) {
            ListView l = (ListView) v;
            l.setDivider(new android.graphics.drawable.ColorDrawable(t.line));
            l.setDividerHeight(1);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) paint(g.getChildAt(i), t);
        }
    }
}
