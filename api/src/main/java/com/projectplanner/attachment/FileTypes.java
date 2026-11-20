package com.projectplanner.attachment;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

/**
 * What an uploaded file is, worked out from its first bytes and checked against its
 * name. Only the kinds on the list are taken. The browser's word for the type is never
 * used: a page named "photo.png" is not a PNG, and a file that is not what its name
 * says is refused.
 *
 * <p>Nothing that a browser would run is on the list: no SVG, no HTML, no XML, no
 * scripts. Plain text that starts like markup is refused too. Only the four raster
 * image formats may be shown in the page; everything else is a download.
 */
public final class FileTypes {

    /** How many bytes at the start of a file are enough to tell. */
    public static final int HEAD_BYTES = 8192;

    /**
     * @param contentType what the file is served as
     * @param previewable whether it may be shown in the page (raster images only)
     */
    public record FileType(String contentType, boolean previewable) {
    }

    private enum Signature { PNG, JPEG, GIF, WEBP, PDF, ZIP, OLE, TEXT }

    private record Kind(Signature signature, String contentType, boolean previewable) {
    }

    private static final String TEXT = "text/plain; charset=utf-8";

    /** Every extension that is taken, with what its first bytes have to be. */
    private static final Map<String, Kind> KINDS = Map.ofEntries(
            Map.entry("png", new Kind(Signature.PNG, "image/png", true)),
            Map.entry("jpg", new Kind(Signature.JPEG, "image/jpeg", true)),
            Map.entry("jpeg", new Kind(Signature.JPEG, "image/jpeg", true)),
            Map.entry("gif", new Kind(Signature.GIF, "image/gif", true)),
            Map.entry("webp", new Kind(Signature.WEBP, "image/webp", true)),
            Map.entry("pdf", new Kind(Signature.PDF, "application/pdf", false)),
            Map.entry("txt", new Kind(Signature.TEXT, TEXT, false)),
            Map.entry("csv", new Kind(Signature.TEXT, TEXT, false)),
            Map.entry("md", new Kind(Signature.TEXT, TEXT, false)),
            Map.entry("log", new Kind(Signature.TEXT, TEXT, false)),
            Map.entry("docx", new Kind(Signature.ZIP, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", false)),
            Map.entry("xlsx", new Kind(Signature.ZIP, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", false)),
            Map.entry("pptx", new Kind(Signature.ZIP, "application/vnd.openxmlformats-officedocument.presentationml.presentation", false)),
            Map.entry("odt", new Kind(Signature.ZIP, "application/vnd.oasis.opendocument.text", false)),
            Map.entry("ods", new Kind(Signature.ZIP, "application/vnd.oasis.opendocument.spreadsheet", false)),
            Map.entry("odp", new Kind(Signature.ZIP, "application/vnd.oasis.opendocument.presentation", false)),
            Map.entry("doc", new Kind(Signature.OLE, "application/msword", false)),
            Map.entry("xls", new Kind(Signature.OLE, "application/vnd.ms-excel", false)),
            Map.entry("ppt", new Kind(Signature.OLE, "application/vnd.ms-powerpoint", false)));

    private FileTypes() {
    }

    /** The extensions that are taken, for the message that refuses the others. */
    public static String allowed() {
        return String.join(", ", KINDS.keySet().stream().sorted().toList());
    }

    /**
     * What the file is, or null when it is not on the list or its first bytes do not
     * match its name.
     *
     * @param head the first bytes of the file, up to {@link #HEAD_BYTES}
     * @param complete whether {@code head} is the whole file
     */
    public static FileType detect(String fileName, byte[] head, boolean complete) {
        Kind kind = KINDS.get(extension(fileName));
        if (kind == null || !matches(kind.signature(), head, complete)) {
            return null;
        }
        return new FileType(kind.contentType(), kind.previewable());
    }

    static String extension(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean matches(Signature signature, byte[] head, boolean complete) {
        return switch (signature) {
            case PNG -> startsWith(head, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
            case JPEG -> startsWith(head, 0xFF, 0xD8, 0xFF);
            case GIF -> startsWith(head, 'G', 'I', 'F', '8', '7', 'a') || startsWith(head, 'G', 'I', 'F', '8', '9', 'a');
            case WEBP -> startsWith(head, 'R', 'I', 'F', 'F') && head.length >= 12
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
            case PDF -> startsWith(head, '%', 'P', 'D', 'F', '-');
            case ZIP -> startsWith(head, 'P', 'K', 0x03, 0x04);
            case OLE -> startsWith(head, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1);
            case TEXT -> isPlainText(head, complete);
        };
    }

    private static boolean startsWith(byte[] head, int... expected) {
        if (head.length < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((head[i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * UTF-8 without NUL bytes, and not starting like markup. The end of {@code head}
     * may cut a character in two when it is not the whole file.
     */
    static boolean isPlainText(byte[] head, boolean complete) {
        for (byte b : head) {
            if (b == 0) {
                return false;
            }
        }
        byte[] bytes = head;
        if (!complete) {
            // Leave out a character that the cut at HEAD_BYTES split: at most three bytes of it.
            int end = bytes.length;
            for (int i = 1; i <= 3 && end - i >= 0; i++) {
                int b = bytes[end - i] & 0xFF;
                if ((b & 0xC0) == 0xC0) {
                    end -= i;
                    break;
                }
                if ((b & 0x80) == 0) {
                    break;
                }
            }
            bytes = Arrays.copyOf(bytes, end);
        }
        String text;
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return false;
        }
        String start = text.startsWith("﻿") ? text.substring(1) : text;
        return !start.stripLeading().startsWith("<");
    }
}
