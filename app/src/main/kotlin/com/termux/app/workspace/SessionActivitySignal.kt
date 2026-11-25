package com.termux.app.workspace

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Arrays

/** Best-effort local foreground-job signal, not remote SSH or AI semantic state.*/
object SessionActivitySignal {
    @JvmStatic
    fun foreground(stat: String): Int {
        try {
            val end: Int = stat.lastIndexOf(')')
            if (end < 0) {
                return -1
            }
            val fields: Array<String> = ((stat.substring(end + 1) as java.lang.String).trim() as java.lang.String).split("\\s+")
            return if (fields.size > 5) Integer.parseInt(fields[5]) else -1
        } catch (invalid: RuntimeException) {
            return -1
        }
    }

    @JvmStatic
    fun command(name: String?): Boolean {
        return name != null && !(name as java.lang.String).trim().isEmpty() && !Arrays.asList("bash", "sh", "dash", "zsh", "fish", "proot", "ssh", "sshd", "node", "codex", "claude", "gemini", "opencode", "tmux", "screen", "python", "python3", "nano", "vim", "nvim", "less", "more", "top", "htop").contains((name as java.lang.String).trim())
    }

    @JvmStatic
    fun busy(pid: Int): Boolean {
        if (pid <= 0) {
            return false
        }
        try {
            val value = String(Files.readAllBytes(Paths.get("/proc", Integer.toString(pid), "stat")), StandardCharsets.UTF_8)
            val group: Int = foreground(value)
            if (group <= 0) {
                return false
            }
            val name = String(Files.readAllBytes(Paths.get("/proc", Integer.toString(group), "comm")), StandardCharsets.UTF_8)
            return command(name)
        } catch (unavailable: Exception) {
            return false
        }
    }
}
