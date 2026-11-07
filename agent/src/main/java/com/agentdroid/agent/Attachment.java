package com.agentdroid.agent;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A text file the user attaches to a chat message. Only readable text is accepted (UTF-8, no binary bytes, bounded size);
 * the content travels inside the message, wrapped in a tag that names the file.
 */
public final class Attachment {
    /** One file may not be bigger than this. */
    public static final int MAX_BYTES = 200 * 1024;
    /** All files of one message together. */
    public static final int MAX_TOTAL_BYTES = 400 * 1024;

    public final String name;
    public final String text;

    private Attachment(String name, String text) {
        this.name = name;
        this.text = text;
    }

    /** The file as an attachment, or null when it is too big, binary or not UTF-8 text. */
    public static Attachment of(String name, byte[] data) {
        if (data == null || data.length > MAX_BYTES) return null;
        for (byte b : data) if (b == 0) return null;
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
        if (text.startsWith("﻿")) text = text.substring(1);
        return new Attachment(cleanName(name), text);
    }

    /** True when {@code next} still fits next to the files already attached. */
    public static boolean fits(List<Attachment> current, Attachment next) {
        long total = next.size();
        for (Attachment a : current) total += a.size();
        return total <= MAX_TOTAL_BYTES;
    }

    /** Size of the text in bytes (what the message grows by). */
    public int size() { return text.getBytes(StandardCharsets.UTF_8).length; }

    /** The message to send: the user's words, then every file in a {@code <file name="...">} block. */
    public static String compose(String message, List<Attachment> files) {
        StringBuilder out = new StringBuilder(message == null ? "" : message.trim());
        for (Attachment a : files) {
            if (out.length() > 0) out.append("\n\n");
            out.append("<file name=\"").append(a.name).append("\">\n").append(a.text);
            if (!a.text.endsWith("\n")) out.append('\n');
            out.append("</file>");
        }
        return out.toString();
    }

    /** A bare file name: no folders, no control characters or quotes, at most 80 characters. */
    static String cleanName(String name) {
        String n = name == null ? "" : name;
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '"' || c == '<' || c == '>') continue;
            b.append(c);
        }
        String s = b.toString().trim();
        if (s.length() > 80) s = s.substring(s.length() - 80);
        return s.isEmpty() ? "file" : s;
    }
}
