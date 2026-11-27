package com.termux.app.workspace

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Arrays
import java.util.Locale

/** Bounded UTF-8 snapshots and same-directory atomic saves; never treats a read failure as empty text.*/
object TextFileEdits {
    class Snapshot {
        @JvmField val path: String
        @JvmField val revision: String
        @JvmField val text: String

        constructor(path: String, revision: String, text: String) {
            this.path = path
            this.revision = revision
            this.text = text
        }
    }

    const val MAX_BYTES: Int = 256 * 1024

    @JvmStatic
    private fun revisionFunction(): String {
        return "revision() { local hash metadata; hash=\$(sha256sum < \"\$file\") || return; hash=\${hash%% *}; metadata=\$(stat -c '%d:%i:%u:%g:%a:%s:%y:%z:%h' -- \"\$file\") || return; printf '%s|%s' \"\$hash\" \"\$metadata\"; }; "
    }

    @JvmStatic
    fun readScript(path: String): String {
        return "set -e; export LC_ALL=C; file=" + ProjectFiles.target(path) + "; test -f \"\$file\"; test ! -L \"\$file\"; test -r \"\$file\"; test \"\$(stat -c %h -- \"\$file\")\" = 1; test \"\$(stat -c %s -- \"\$file\")\" -le " + MAX_BYTES + "; " + revisionFunction() + "before=\$(revision); printf '%s\\0' \"\$before\"; cat -- \"\$file\"; test \"\$before\" = \"\$(revision)\";"
    }

    @Throws(IOException::class)
    @JvmStatic
    fun parse(path: String, bytes: ByteArray): Snapshot {
        var split = -1
        for (i in 0 until (bytes.size)) {
            if (bytes[i].toInt() == 0) {
                split = i
                break
            }
        }
        if (split < 65 || split > 512 || bytes.size - split - 1 > MAX_BYTES) {
            throw error()
        }
        val revision = String(bytes, 0, split, StandardCharsets.UTF_8)
        if (!(revision.substring(0, 64) as java.lang.String).matches("[0-9a-f]{64}") || revision[64] != '|') {
            throw error()
        }
        val content: ByteArray = Arrays.copyOfRange(bytes, split + 1, bytes.size)
        for (b in content) {
            if (b.toInt() == 0) {
                throw error()
            }
        }
        try {
            if (!digest(content).equals(revision.substring(0, 64))) {
                throw error()
            }
            val text: String = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString()
            return Snapshot(path, revision, text)
        } catch (exception: CharacterCodingException) {
            throw error()
        }
    }

    @Throws(IOException::class)
    @JvmStatic
    fun encode(text: String): ByteArray {
        if (text.indexOf('\u0000') >= 0) {
            throw error()
        }
        try {
            val encoded: ByteBuffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
            val bytes = ByteArray(encoded.remaining())
            encoded.get(bytes)
            if (bytes.size > MAX_BYTES) {
                throw error()
            }
            return bytes
        } catch (e: CharacterCodingException) {
            throw error()
        }
    }

    @JvmStatic
    private fun error(): IOException {
        return IOException(WorkspaceText.tr("Редактор поддерживает текст UTF-8 до 256 КБ. Бинарные файлы и повреждённый текст не редактируются."))
    }

    @JvmStatic
    private fun digest(bytes: ByteArray): String {
        try {
            val hash: ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
            val result = StringBuilder()
            for (b in hash) {
                result.append(java.lang.String.format(Locale.ROOT, "%02x", b.toInt() and 255))
            }
            return result.toString()
        } catch (e: NoSuchAlgorithmException) {
            throw AssertionError(e)
        }
    }

    @JvmStatic
    fun saveScript(original: Snapshot): String {
        // Copy metadata without exposing the new content as shell text; preserve mode/uid/gid.
        // (the metadata=/meta= steps of the script below; in the Java source the comment stood inside the concatenation)
        return "set -e; export LC_ALL=C; file=" + ProjectFiles.target(original.path) + "; directory=\$(dirname -- \"\$file\"); tmp=\$(mktemp -- \"\$directory/.termux-jobs-edit.XXXXXXXX\"); trap 'rm -f -- \"\$tmp\"' EXIT; cat > \"\$tmp\"; test \"\$(stat -c %s -- \"\$tmp\")\" -le " + MAX_BYTES + "; if [ ! -f \"\$file\" ] || [ -L \"\$file\" ] || [ \"\$(stat -c %h -- \"\$file\")\" != 1 ]; then printf CONFLICT; exit 0; fi; " + revisionFunction() + "expected=" + SessionShell.quote(original.revision) + "; if [ \"\$(revision)\" != \"\$expected\" ]; then printf CONFLICT; exit 0; fi; metadata=\$(stat -c '%u:%g:%a' -- \"\$file\"); meta=\$(mktemp -- \"\$directory/.termux-jobs-edit.XXXXXXXX\"); trap 'rm -f -- \"\$tmp\" \"\$meta\"' EXIT; cp -p -- \"\$file\" \"\$meta\"; cat -- \"\$tmp\" > \"\$meta\"; test \"\$(stat -c '%u:%g:%a' -- \"\$meta\")\" = \"\$metadata\"; if [ -L \"\$file\" ] || [ \"\$(revision)\" != \"\$expected\" ]; then printf CONFLICT; exit 0; fi; mv -fT -- \"\$meta\" \"\$file\"; printf SAVED;"
    }
}
