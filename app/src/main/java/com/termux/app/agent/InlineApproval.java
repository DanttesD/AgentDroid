package com.termux.app.agent;

import android.app.Activity;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.R;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The approval question as a card inside the chat feed: the description and three buttons (Allow, 1 hour, Deny).
 * After the answer the card collapses to one line, so the feed keeps a record of what was allowed.
 * {@link #ask} blocks the calling (agent) thread, like the dialog it replaces.
 */
final class InlineApproval {
    private static final long TIMEOUT_MS = TimeUnit.MINUTES.toMillis(3);
    private static final int OPEN = 0, ALLOWED = 1, DENIED = 2, MOVED = 3;

    private static final class Pending {
        final AtomicInteger state = new AtomicInteger(OPEN);
        final CountDownLatch latch = new CountDownLatch(1);
        final String description;
        LinearLayout card;
        Pending(String description) { this.description = description; }
    }

    private final Activity activity;
    private final Ui ui;
    private final LinearLayout feed;
    private final Runnable scrollDown;
    /** Touched on the UI thread only. */
    private final List<Pending> open = new ArrayList<>();

    InlineApproval(Activity activity, Ui ui, LinearLayout feed, Runnable scrollDown) {
        this.activity = activity;
        this.ui = ui;
        this.feed = feed;
        this.scrollDown = scrollDown;
    }

    private boolean settle(Pending p, int to) {
        if (!p.state.compareAndSet(OPEN, to)) return false;
        p.latch.countDown();
        return true;
    }

    /** TRUE / FALSE once answered (a timeout is FALSE); null when the card could not stay (the screen was left). */
    Boolean ask(String description, final boolean force) {
        final Pending p = new Pending(description);
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) { settle(p, MOVED); return; }
            p.card = card(p, force);
            open.add(p);
            feed.addView(p.card);
            scrollDown.run();
        });
        try {
            if (!p.latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS) && settle(p, DENIED)) {
                activity.runOnUiThread(() -> collapse(p, false, activity.getString(R.string.chat_approval_timeout)));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (settle(p, DENIED)) activity.runOnUiThread(() -> collapse(p, false, activity.getString(R.string.chat_denied)));
            return false;
        }
        int s = p.state.get();
        return s == MOVED ? null : Boolean.valueOf(s == ALLOWED);
    }

    /** The screen is going away: open cards are removed, the questions are asked again where the user is now. UI thread. */
    void releasePending() {
        for (Pending p : new ArrayList<>(open)) {
            if (settle(p, MOVED) && p.card != null) feed.removeView(p.card);
        }
        open.clear();
    }

    /** Stop or new chat: every open question is answered "no". UI thread. */
    void denyAll() {
        for (Pending p : new ArrayList<>(open)) {
            if (settle(p, DENIED)) collapse(p, false, activity.getString(R.string.chat_denied));
        }
        open.clear();
    }

    private LinearLayout card(final Pending p, boolean force) {
        final LinearLayout c = ui.column();
        c.setBackground(ui.shape(ui.t.s1, 18, ui.t.p, 2));
        c.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ui.dp(12);
        c.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(activity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView shield = ui.icon(R.drawable.ic_shield, 20, ui.t.p);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20));
        sl.rightMargin = ui.dp(8);
        head.addView(shield, sl);
        head.addView(ui.text(activity.getString(R.string.agent_allow_title), 15, ui.t.on, true));
        c.addView(head);

        TextView body = ui.text(p.description, 13.5f, ui.t.on2, false);
        body.setTextIsSelectable(true);
        c.addView(body, ui.gap(8));

        LinearLayout buttons = new LinearLayout(activity);
        buttons.addView(button(R.string.agent_allow, Ui.Kind.FILLED, v -> answer(p, ALLOWED, true, activity.getString(R.string.chat_approved), false)));
        if (!force) {
            buttons.addView(button(R.string.chat_allow_hour_short, Ui.Kind.TONAL, v -> answer(p, ALLOWED, true, activity.getString(R.string.chat_approved), true)));
        }
        buttons.addView(button(R.string.agent_deny, Ui.Kind.DANGER, v -> answer(p, DENIED, false, activity.getString(R.string.chat_denied), false)));
        c.addView(buttons, ui.gap(12));
        return c;
    }

    private TextView button(int label, Ui.Kind kind, android.view.View.OnClickListener l) {
        TextView b = ui.button(activity.getString(label), kind, l);
        b.setTextSize(13.5f);
        b.setSingleLine(true);
        b.setMinHeight(ui.dp(44));
        b.setPadding(ui.dp(6), 0, ui.dp(6), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = ui.dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private void answer(Pending p, int state, boolean ok, String label, boolean forAnHour) {
        if (!settle(p, state)) return;
        if (forAnHour) ApprovalCenter.setMode(activity, 1);
        collapse(p, ok, label);
        open.remove(p);
    }

    private void collapse(Pending p, boolean ok, String label) {
        LinearLayout c = p.card;
        if (c == null) return;
        c.removeAllViews();
        c.setBackground(null);
        c.setPadding(0, 0, 0, 0);
        String first = p.description.split("\n", 2)[0];
        if (first.length() > 90) first = first.substring(0, 90) + "…";
        TextView line = ui.text((ok ? "✓ " : "✕ ") + label + " · " + first, 13, ok ? ui.t.ok : ui.t.err, false);
        c.addView(line);
    }
}
