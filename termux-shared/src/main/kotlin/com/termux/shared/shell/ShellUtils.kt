package com.termux.shared.shell

import com.termux.terminal.TerminalBuffer
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import java.lang.reflect.Field

object ShellUtils {

    @JvmStatic
    fun getPid(p: Process): Int {
        try {
            val f: Field = p.javaClass.getDeclaredField("pid")
            f.setAccessible(true)
            try {
                return f.getInt(p)
            } finally {
                f.setAccessible(false)
            }
        } catch (e: Throwable) {
            return -1
        }
    }

    @JvmStatic
    fun getExecutableBasename(executable: String?): String? {
        if (executable == null) {
            return null
        }
        val lastSlash: Int = executable.lastIndexOf('/')
        return if (lastSlash == -1) executable else executable.substring(lastSlash + 1)
    }

    @JvmStatic
    fun getTerminalSessionTranscriptText(terminalSession: TerminalSession?, linesJoined: Boolean, trim: Boolean): String? {
        if (terminalSession == null) {
            return null
        }

        val terminalEmulator: TerminalEmulator? = terminalSession.getEmulator()
        if (terminalEmulator == null) {
            return null
        }

        val terminalBuffer: TerminalBuffer? = terminalEmulator.getScreen()
        if (terminalBuffer == null) {
            return null
        }

        var transcriptText: String?

        if (linesJoined) {
            transcriptText = terminalBuffer.getTranscriptTextWithFullLinesJoined()
        } else {
            transcriptText = terminalBuffer.getTranscriptTextWithoutJoinedLines()
        }

        if (transcriptText == null) {
            return null
        }

        if (trim) {
            transcriptText = (transcriptText as java.lang.String).trim()
        }

        return transcriptText
    }
}
