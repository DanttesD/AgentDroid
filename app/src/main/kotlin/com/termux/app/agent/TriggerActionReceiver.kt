package com.termux.app.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.agentdroid.agent.Triggers

/** The "Run" button of a trigger notification.*/
class TriggerActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || !ACTION_RUN.equals(intent.getAction())) {
            return
        }
        val rule: Triggers.Rule? = TriggerStore(context).find(intent.getStringExtra("rule"))
        if (rule == null) {
            return
        }
        val e = Triggers.Event(intent.getStringExtra("pkg"), intent.getStringExtra("app"), intent.getStringExtra("title"), intent.getStringExtra("text"))
        TriggerRunner.run(context, rule, e)
    }

    companion object {
        const val ACTION_RUN: String = "com.agentdroid.trigger.RUN"
    }
}
