package com.termux.app.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.agentdroid.agent.Triggers;

/** The "Run" button of a trigger notification. */
public final class TriggerActionReceiver extends BroadcastReceiver {
    public static final String ACTION_RUN = "com.agentdroid.trigger.RUN";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_RUN.equals(intent.getAction())) return;
        Triggers.Rule rule = new TriggerStore(context).find(intent.getStringExtra("rule"));
        if (rule == null) return;
        Triggers.Event e = new Triggers.Event(intent.getStringExtra("pkg"), intent.getStringExtra("app"),
                intent.getStringExtra("title"), intent.getStringExtra("text"));
        TriggerRunner.run(context, rule, e);
    }
}
