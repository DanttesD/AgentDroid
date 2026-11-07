package com.termux.app.agent;

import android.os.StatFs;
import android.text.format.Formatter;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.AdbConsoleActivity;

import java.util.ArrayList;
import java.util.List;

/** Where commands run and the tools around it: terminal, sessions, ADB, servers, workspace. */
public final class EnvActivity extends TabActivity {
    @Override protected int tab() { return NavBar.ENV; }
    @Override protected int titleRes() { return R.string.nav_env; }

    private static String shortName(String id, String display) {
        if (id.startsWith("root")) return "Root";
        if (id.startsWith("adb")) return "ADB";
        if (id.startsWith("termux")) return "Termux";
        return display;
    }

    @Override protected void fill(LinearLayout c) {
        final AgentConfig cfg = new AgentConfig(this);
        final com.agentdroid.exec.BackendSelector sel = AgentBackends.create(this);
        String pin = cfg.backendPin();
        final List<String> ids = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        ids.add(""); names.add(getString(R.string.env_auto));
        for (com.agentdroid.exec.ExecBackend b : sel.all()) { ids.add(b.id()); names.add(shortName(b.id(), b.displayName())); }

        LinearLayout where = ui.card();
        LinearLayout head = new LinearLayout(this);
        head.addView(ui.text(getString(R.string.env_where), 15, ui.t.on, true), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        where.addView(head);
        where.addView(ui.text(getString(R.string.env_where_sub), 12.5f, ui.t.on2, false));
        LinearLayout chips = new LinearLayout(this);
        for (int i = 0; i < ids.size(); i++) {
            final String id = ids.get(i);
            TextView ch = ui.chip(names.get(i), id.equals(pin), v -> { cfg.setBackendPin(id); refresh(); });
            ch.setSingleLine(true);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            p.rightMargin = i < ids.size() - 1 ? ui.dp(6) : 0;
            chips.addView(ch, p);
        }
        where.addView(chips, ui.gap(12));
        c.addView(where);

        // Destinations only. Network check and proxy live on the Phone tab and in Settings, not here.
        LinearLayout[] tiles = {
            ui.tile(R.drawable.ic_nav_env, getString(R.string.env_terminal), getString(R.string.env_terminal_sub), true, v -> openWorkspace("terminal")),
            ui.tile(R.drawable.ic_folder, getString(R.string.env_sessions), getString(R.string.env_sessions_sub), false, v -> openWorkspace("sessions")),
            ui.tile(R.drawable.ic_box, getString(R.string.env_commands), getString(R.string.env_commands_sub), false, v -> openWorkspace("commands")),
            ui.tile(R.drawable.ic_server, getString(R.string.env_servers), getString(R.string.env_servers_sub), false, v -> openWorkspace("servers")),
            ui.tile(R.drawable.ic_code, getString(R.string.env_adb), getString(R.string.env_adb_sub), false, v -> open(AdbConsoleActivity.class)),
            ui.tile(R.drawable.ic_desktop, getString(R.string.env_desktop), getString(R.string.env_desktop_sub), true, v -> {
                Toast.makeText(this, R.string.agent_desktop_hint, Toast.LENGTH_LONG).show();
                openWorkspace("terminal");
            }),
            ui.tile(R.drawable.workspace_home, getString(R.string.env_workspace), getString(R.string.env_workspace_sub), false, v -> openWorkspace("home"))};
        LinearLayout grid = ui.column();
        for (int i = 0; i < tiles.length; i += 2) {
            LinearLayout line = new LinearLayout(this);
            boolean pair = i + 1 < tiles.length; // an odd last tile takes the whole row
            for (int j = 0; j < (pair ? 2 : 1); j++) {
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                if (pair) { if (j == 0) p.rightMargin = ui.dp(5); else p.leftMargin = ui.dp(5); }
                line.addView(tiles[i + j], p);
            }
            grid.addView(line, ui.gap(i == 0 ? 0 : 10));
        }
        c.addView(grid, ui.gap(14));
        c.addView(storageCard(), ui.gap(14));
    }

    /** Free space of the phone's storage and the way to the cleanup in the workspace. */
    private LinearLayout storageCard() {
        long free = 0, total = 0;
        try {
            StatFs fs = new StatFs(getFilesDir().getPath());
            free = fs.getAvailableBytes();
            total = fs.getTotalBytes();
        } catch (RuntimeException ignored) { }
        LinearLayout card = ui.card();
        String sub = total > 0
                ? getString(R.string.env_storage_sub, Formatter.formatFileSize(this, free), Formatter.formatFileSize(this, total))
                : getString(R.string.env_storage_clean);
        card.addView(ui.iconRow(R.drawable.workspace_server_disk, getString(R.string.env_storage), sub,
                ui.statusChip(getString(R.string.env_storage_clean), false), v -> openWorkspace("settings:storage")));
        return card;
    }
}
