package com.agentdroid.agent

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * A text file the user attaches to a chat message. Only readable text is accepted (UTF-8, no binary bytes, bounded size);
 * the content travels inside the message, wrapped in a tag that names the file.
 */
class Attachment private constructor(
    @JvmField val name: String,
    @JvmField val text: String,
) {
    /** Size of the text in bytes (what the message grows by). */
    fun size(): Int = text.toByteArray(StandardCharsets.UTF_8).size

    companion object {
        /** One file may not be bigger than this. */
        const val MAX_BYTES = 200 * 1024

        /** All files of one message together. */
        const val MAX_TOTAL_BYTES = 400 * 1024

        /** The file as an attachment, or null when it is too big, binary or not UTF-8 text. */
        @JvmStatic
        fun of(name: String?, data: ByteArray?): Attachment? {
            if (data == null || data.size > MAX_BYTES) return null
            for (b in data) if (b == 0.toByte()) return null
            var text: String
            try {
                text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString()
            } catch (e: CharacterCodingException) {
                return null
            }
            if (text.startsWith("﻿")) text = text.substring(1)
            return Attachment(cleanName(name), text)
        }

        /** True when `next` still fits next to the files already attached. */
        @JvmStatic
        fun fits(current: List<Attachment>, next: Attachment): Boolean {
            var total = next.size().toLong()
            for (a in current) total += a.size()
            return total <= MAX_TOTAL_BYTES
        }

        /** The message to send: the user's words, then every file in a `<file name="...">` block. */
        @JvmStatic
        fun compose(message: String?, files: List<Attachment>): String {
            val out = StringBuilder((message ?: "").jtrim())
            for (a in files) {
                if (out.isNotEmpty()) out.append("\n\n")
                out.append("<file name=\"").append(a.name).append("\">\n").append(a.text)
                if (!a.text.endsWith("\n")) out.append('\n')
                out.append("</file>")
            }
            return out.toString()
        }

        /** A bare file name: no folders, no control characters or quotes, at most 80 characters. */
        @JvmStatic
        fun cleanName(name: String?): String {
            var n = name ?: ""
            val slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'))
            if (slash >= 0) n = n.substring(slash + 1)
            val b = StringBuilder()
            for (c in n) {
                if (c.code < 0x20 || c.code == 0x7f || c == '"' || c == '<' || c == '>') continue
                b.append(c)
            }
            var s = b.toString().jtrim()
            if (s.length > 80) s = s.substring(s.length - 80)
            return if (s.isEmpty()) "file" else s
        }
    }
}
