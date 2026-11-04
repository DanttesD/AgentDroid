package com.termux.app.agent;

import android.content.Context;

import com.agentdroid.agent.A11yTools;
import com.agentdroid.agent.AgentLoop;
import com.agentdroid.agent.AndroidTools;
import com.agentdroid.agent.Approver;
import com.agentdroid.agent.DevTools;
import com.agentdroid.agent.PhoneHooks;
import com.agentdroid.agent.ScreenFlows;
import com.agentdroid.agent.Tool;
import com.agentdroid.agent.Tools;
import com.agentdroid.agent.UiTools;
import com.agentdroid.exec.ExecBackend;

import java.util.ArrayList;
import java.util.List;

/** Builds the full phone toolset and the native agent loop; shared by the MCP server, the chat and the overlay assistant. */
public final class PhoneAgent {
    public static final int MAX_STEPS = 40;

    private PhoneAgent() {}

    public static List<Tool> tools(Context app, ExecBackend backend, PhoneHooks hooks) {
        List<Tool> all = new ArrayList<>(Tools.standard(backend, AgentBackends.TERMUX_HOME));
        all.addAll(DevTools.create(backend, AgentBackends.TERMUX_HOME));
        all.addAll(UiTools.create(backend, hooks::setClipboard));
        all.addAll(A11yTools.create());
        all.addAll(ScreenFlows.create());
        all.addAll(AndroidTools.create(backend, hooks));
        return all;
    }

    /** Sensitive calls arrive with a leading "!" in the description: they are asked even when the user allowed everything. */
    public static Approver approver(Context app) {
        final Context c = app.getApplicationContext();
        return (call, description) -> {
            boolean force = description.startsWith("!");
            return ApprovalCenter.ask(c, force ? description.substring(1) : description, force);
        };
    }

    /** The agent with screen control, the same guard as the MCP server and the same instructions. */
    public static AgentLoop create(Context context, ExecBackend backend, Approver approver) {
        Context app = context.getApplicationContext();
        List<Tool> all = tools(app, backend, new McpHost.AppHooks(app));
        String system = McpHost.environmentText(all) + " You are talking to the user directly: be concise, check results, "
                + "and say in a sentence what you did.";
        AgentLoop loop = new AgentLoop(new AgentConfig(app).build(), all, approver, system, MAX_STEPS);
        loop.setGuard(new ActionGuard(app, backend));
        return loop;
    }
}
