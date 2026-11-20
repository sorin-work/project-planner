package com.projectplanner.attachment;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class FileTypesTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};
    private static final byte[] PDF = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ZIP = {'P', 'K', 3, 4, 20, 0};

    private static byte[] text(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void takesWhatItsFirstBytesSayAndItsNameAgreesWith() {
        assertThat(FileTypes.detect("Mock-up.PNG", PNG, true)).isEqualTo(new FileTypes.FileType("image/png", true));
        assertThat(FileTypes.detect("brief.pdf", PDF, true)).isEqualTo(new FileTypes.FileType("application/pdf", false));
        assertThat(FileTypes.detect("plan.xlsx", ZIP, true).contentType()).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(FileTypes.detect("notes.txt", text("Kick-off on Monday.\nBring the brief."), true))
                .isEqualTo(new FileTypes.FileType("text/plain; charset=utf-8", false));
        assertThat(FileTypes.detect("photo.jpg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}, true).previewable()).isTrue();
        assertThat(FileTypes.detect("anim.gif", text("GIF89a...."), true).contentType()).isEqualTo("image/gif");
        assertThat(FileTypes.detect("pic.webp", text("RIFF\u0001\u0002\u0003\u0004WEBPVP8 "), true).contentType()).isEqualTo("image/webp");
    }

    @Test
    void refusesWhatABrowserWouldRunWhateverItIsCalled() {
        assertThat(FileTypes.detect("logo.svg", text("<svg xmlns=\"http://www.w3.org/2000/svg\"/>"), true)).isNull();
        assertThat(FileTypes.detect("page.html", text("<html><script>alert(1)</script></html>"), true)).isNull();
        // A page that calls itself a picture, or a text file.
        assertThat(FileTypes.detect("photo.png", text("<html><script>alert(1)</script></html>"), true)).isNull();
        assertThat(FileTypes.detect("readme.txt", text("  \n<script>alert(1)</script>"), true)).isNull();
        assertThat(FileTypes.detect("readme.txt", text("﻿<svg onload=alert(1)>"), true)).isNull();
        // Whatever a browser skips before markup does not hide it.
        assertThat(FileTypes.detect("readme.txt", text("\uFEFF\uFEFF<svg onload=alert(1)>"), true)).isNull();
        assertThat(FileTypes.detect("readme.txt", text("\u00A0\u200B <html>"), true)).isNull();
        assertThat(FileTypes.detect("readme.txt", text("\u00A0Plain words."), true)).isNotNull();
        assertThat(FileTypes.detect("tool.exe", text("MZ"), true)).isNull();
        assertThat(FileTypes.detect("no-extension", PNG, true)).isNull();
    }

    @Test
    void refusesAFileWhoseBytesAreNotWhatItsNameSays() {
        assertThat(FileTypes.detect("brief.pdf", PNG, true)).isNull();
        assertThat(FileTypes.detect("photo.png", PDF, true)).isNull();
        assertThat(FileTypes.detect("plan.docx", PDF, true)).isNull();
        assertThat(FileTypes.detect("notes.txt", new byte[] {'a', 0, 'b'}, true)).isNull();
        assertThat(FileTypes.detect("notes.txt", new byte[] {'a', (byte) 0xC3, 'b'}, true)).isNull();
        assertThat(FileTypes.detect("empty.png", new byte[0], true)).isNull();
    }

    @Test
    void aCharacterCutInTwoAtTheEndOfTheHeadIsNotATextError() {
        byte[] full = text("Ünïcödé ".repeat(1200));
        byte[] head = Arrays.copyOf(full, FileTypes.HEAD_BYTES);
        // Cut in the middle of a two-byte character.
        int cut = FileTypes.HEAD_BYTES;
        while ((head[cut - 1] & 0xC0) != 0xC0) {
            cut--;
        }
        byte[] split = Arrays.copyOf(full, cut);
        assertThat(FileTypes.isPlainText(split, false)).isTrue();
        assertThat(FileTypes.isPlainText(split, true)).isFalse();
    }
}
