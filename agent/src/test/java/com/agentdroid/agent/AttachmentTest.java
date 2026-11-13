package com.agentdroid.agent;

import static org.junit.Assert.*;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AttachmentTest {
    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test public void plainTextIsAccepted() {
        Attachment a = Attachment.of("notes.txt", utf8("привет\nмир"));
        assertNotNull(a);
        assertEquals("notes.txt", a.name);
        assertEquals("привет\nмир", a.text);
    }

    @Test public void byteOrderMarkIsDropped() {
        Attachment a = Attachment.of("a.txt", utf8("\uFEFFabc"));
        assertNotNull(a);
        assertEquals("abc", a.text);
    }

    @Test public void tooBigIsRejected() {
        assertNotNull(Attachment.of("ok.txt", fill(Attachment.MAX_BYTES)));
        assertNull(Attachment.of("big.txt", fill(Attachment.MAX_BYTES + 1)));
    }

    private static byte[] fill(int n) {
        byte[] b = new byte[n];
        Arrays.fill(b, (byte) 'a');
        return b;
    }

    @Test public void binaryIsRejected() {
        assertNull(Attachment.of("x.bin", new byte[]{'a', 0, 'b'}));
        assertNull(Attachment.of("x.bin", new byte[]{(byte) 0xC3, (byte) 0x28}));
        assertNull(Attachment.of("x.txt", null));
    }

    @Test public void emptyFileIsAnEmptyAttachment() {
        Attachment a = Attachment.of("empty.txt", new byte[0]);
        assertNotNull(a);
        assertEquals("", a.text);
    }

    @Test public void namesAreCleaned() {
        assertEquals("passwd", Attachment.cleanName("../../etc/passwd"));
        assertEquals("a.txt", Attachment.cleanName("C:\\dir\\a.txt"));
        assertEquals("evil.txt", Attachment.cleanName("ev\"il<>.txt"));
        assertEquals("file", Attachment.cleanName("   "));
        assertEquals("file", Attachment.cleanName(null));
        assertEquals(80, Attachment.cleanName(new String(new char[200]).replace('\0', 'n')).length());
    }

    @Test public void composeWithoutFilesKeepsTheMessage() {
        assertEquals("hello", Attachment.compose("  hello ", new ArrayList<Attachment>()));
        assertEquals("", Attachment.compose(null, new ArrayList<Attachment>()));
    }

    @Test public void composeWrapsEveryFile() {
        List<Attachment> files = Arrays.asList(Attachment.of("a.txt", utf8("one")), Attachment.of("b.md", utf8("two\n")));
        String m = Attachment.compose("look", files);
        assertEquals("look\n\n<file name=\"a.txt\">\none\n</file>\n\n<file name=\"b.md\">\ntwo\n</file>", m);
    }

    @Test public void composeWithOnlyFilesHasNoLeadingBlankLines() {
        List<Attachment> files = Arrays.asList(Attachment.of("a.txt", utf8("x")));
        assertEquals("<file name=\"a.txt\">\nx\n</file>", Attachment.compose("", files));
    }

    @Test public void totalSizeIsBounded() {
        List<Attachment> cur = new ArrayList<>();
        Attachment big = Attachment.of("a.txt", fill(Attachment.MAX_BYTES));
        assertTrue(Attachment.fits(cur, big));
        cur.add(big);
        assertTrue(Attachment.fits(cur, Attachment.of("b.txt", fill(Attachment.MAX_BYTES))));
        cur.add(Attachment.of("b.txt", fill(Attachment.MAX_BYTES)));
        assertFalse(Attachment.fits(cur, Attachment.of("c.txt", fill(1))));
    }
}
