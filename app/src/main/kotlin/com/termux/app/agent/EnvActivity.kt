package com.termux.app.agent

import android.os.StatFs
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.agentdroid.exec.BackendSelector
import com.termux.R
import com.termux.app.AdbConsoleActivity

/** Where commands run and the tools around it: terminal, sessions, ADB, servers, workspace.*/
class EnvActivity : TabActivity() {
    protected override fun tab(): Int {
        return NavBar.ENV
    }

    protected override fun titleRes(): Int {
        return R.string.nav_env
    }

    protected override fun fill(c: LinearLayout?) {
        val cfg = AgentConfig(this)
        val sel: BackendSelector = AgentBackends.create(this)
        val pin: String? = cfg.backendPin()
        val ids: MutableList<String> = ArrayList<String>()
        val names: MutableList<String> = ArrayList<String>()
        ids.add("")
        names.add(getString(R.string.env_auto))
        for (b in sel.all()) {
            ids.add(b.id())
            names.add(shortName(b.id(), b.displayName()))
        }

        val where: LinearLayout = ui!!.card()
        val head = LinearLayout(this)
        head.addView(ui!!.text(getString(R.string.env_where), 15.0f, ui!!.t.on, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        where.addView(head)
        where.addView(ui!!.text(getString(R.string.env_where_sub), 12.5f, ui!!.t.on2, false))
        val chips = LinearLayout(this)
        var i = 0
        while (i < ids.size) {
            val id: String = ids.get(i)
            val ch: TextView = ui!!.chip(names.get(i), id.equals(pin), View.OnClickListener { v ->
                cfg.setBackendPin(id)
                refresh()
            })
            ch.setSingleLine(true)
            val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            p.rightMargin = if (i < ids.size - 1) ui!!.dp(6.0f) else 0
            chips.addView(ch, p)
            i++
        }
        where.addView(chips, ui!!.gap(12))
        c!!.addView(where)

        // Destinations only. Network check and proxy live on the Phone tab and in Settings, not here.
        val tiles: Array<LinearLayout> = arrayOf<LinearLayout>(ui!!.tile(R.drawable.ic_nav_env, getString(R.string.env_terminal), getString(R.string.env_terminal_sub), true, View.OnClickListener { v -> openWorkspace("terminal") }), ui!!.tile(R.drawable.ic_folder, getString(R.string.env_sessions), getString(R.string.env_sessions_sub), false, View.OnClickListener { v -> openWorkspace("sessions") }), ui!!.tile(R.drawable.ic_box, getString(R.string.env_commands), getString(R.string.env_commands_sub), false, View.OnClickListener { v -> openWorkspace("commands") }), ui!!.tile(R.drawable.ic_server, getString(R.string.env_servers), getString(R.string.env_servers_sub), false, View.OnClickListener { v -> openWorkspace("servers") }), ui!!.tile(R.drawable.ic_code, getString(R.string.env_adb), getString(R.string.env_adb_sub), false, View.OnClickListener { v -> open(AdbConsoleActivity::class.java) }), ui!!.tile(R.drawable.ic_desktop, getString(R.string.env_desktop), getString(R.string.env_desktop_sub), true, View.OnClickListener { v ->
            Toast.makeText(this, R.string.agent_desktop_hint, Toast.LENGTH_LONG).show()
            openWorkspace("terminal")
        }), ui!!.tile(R.drawable.workspace_home, getString(R.string.env_workspace), getString(R.string.env_workspace_sub), false, View.OnClickListener { v -> openWorkspace("home") }))
        val grid: LinearLayout = ui!!.column()
        for (i in (0 until (tiles.size)) step 2) {
            val line = LinearLayout(this)
            val pair: Boolean = i + 1 < tiles.size // an odd last tile takes the whole row
            var j = 0
            while (j < (if (pair) 2 else 1)) {
                val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                if (pair) {
                    if (j == 0) {
                        p.rightMargin = ui!!.dp(5.0f)
                    } else {
                        p.leftMargin = ui!!.dp(5.0f)
                    }
                }
                line.addView(tiles[i + j], p)
                j++
            }
            grid.addView(line, ui!!.gap(if (i == 0) 0 else 10))
        }
        c.addView(grid, ui!!.gap(14))
        c.addView(storageCard(), ui!!.gap(14))
    }

    /** Free space of the phone's storage and the way to the cleanup in the workspace.*/
    private fun storageCard(): LinearLayout {
        var free: Long = 0L
        var total: Long = 0L
        try {
            val fs = StatFs(getFilesDir().getPath())
            free = fs.getAvailableBytes()
            total = fs.getTotalBytes()
        } catch (ignored: RuntimeException) {
        }
        val card: LinearLayout = ui!!.card()
        val sub: String = if (total > 0) getString(R.string.env_storage_sub, Formatter.formatFileSize(this, free), Formatter.formatFileSize(this, total)) else getString(R.string.env_storage_clean)
        card.addView(ui!!.iconRow(R.drawable.workspace_server_disk, getString(R.string.env_storage), sub, ui!!.statusChip(getString(R.string.env_storage_clean), false), View.OnClickListener { v -> openWorkspace("settings:storage") }))
        return card
    }

    companion object {

        @JvmStatic
        private fun shortName(id: String, display: String): String {
            if (id.startsWith("root")) {
                return "Root"
            }
            if (id.startsWith("adb")) {
                return "ADB"
            }
            if (id.startsWith("termux")) {
                return "Termux"
            }
            return display
        }
    }
}
