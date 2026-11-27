package com.termux.app.agent

import android.content.Context
import com.agentdroid.agent.A11yTools
import com.agentdroid.agent.AgentLoop
import com.agentdroid.agent.AndroidTools
import com.agentdroid.agent.Approver
import com.agentdroid.agent.DevTools
import com.agentdroid.agent.PhoneHooks
import com.agentdroid.agent.ScreenFlows
import com.agentdroid.agent.Tool
import com.agentdroid.agent.Tools
import com.agentdroid.agent.UiTools
import com.agentdroid.exec.BackendSelector
import com.agentdroid.exec.ExecBackend

/** Builds the full phone toolset and the native agent loop; shared by the MCP server, the chat and the overlay assistant.*/
object PhoneAgent {
    const val MAX_STEPS: Int = 40

    @JvmStatic
    fun tools(app: Context, backend: ExecBackend, hooks: PhoneHooks): MutableList<Tool> {
        val all: MutableList<Tool> = ArrayList<Tool>(Tools.standard(backend, AgentBackends.TERMUX_HOME))
        all.addAll(DevTools.create(backend, AgentBackends.TERMUX_HOME))
        all.addAll(UiTools.create(backend, UiTools.ClipboardSink(hooks::setClipboard)))
        all.addAll(A11yTools.create())
        all.addAll(ScreenFlows.create())
        all.addAll(AndroidTools.create(backend, hooks))
        return all
    }

    /** Sensitive calls arrive with a leading "!" in the description: they are asked even when the user allowed everything.*/
    @JvmStatic
    fun approver(app: Context): Approver {
        val c: Context = app.getApplicationContext()
        return Approver { call, description ->
            val force: Boolean = description.startsWith("!")
            return@Approver ApprovalCenter.ask(c, if (force) description.substring(1) else description, force)
        }
    }

    /** The agent with screen control, the same guard as the MCP server and the same instructions.*/
    @JvmStatic
    fun create(context: Context, backend: ExecBackend, approver: Approver): AgentLoop {
        val app: Context = context.getApplicationContext()
        val all: MutableList<Tool> = tools(app, backend, McpHost.AppHooks(app))
        val system: String = McpHost.environmentText(all) + " You are talking to the user directly: be concise, check results, and say in a sentence what you did."
        val loop = AgentLoop(AgentConfig(app).build(), all, approver, system, MAX_STEPS)
        loop.setGuard(ActionGuard(app, backend))
        return loop
    }

    /** Agent on the backend the user selected (pin or first available), or null when no way to run commands exists.*/
    @JvmStatic
    fun createDefault(context: Context, approver: Approver): AgentLoop? {
        val app: Context = context.getApplicationContext()
        val sel: BackendSelector = AgentBackends.create(app)
        val pin: String? = AgentConfig(app).backendPin()
        if (!pin!!.isEmpty()) {
            try {
                sel.pin(pin)
            } catch (ignored: Exception) {
            }
        }
        if (sel.select() == null) {
            return null
        }
        return create(app, SelectedBackend(sel), approver)
    }
}
