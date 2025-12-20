package com.projectplanner.comment;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds @mentions in comment text. A mention is "@" followed by a handle (the
 * part of someone's email before the @), e.g. "@ana.popescu".
 */
public final class MentionParser {

    private static final Pattern MENTION = Pattern.compile("@([\\p{L}\\p{N}][\\p{L}\\p{N}._+-]*)");

    private MentionParser() {
    }

    /** Lower-cased handles in the order they first appear. */
    public static Set<String> handles(String text) {
        Set<String> handles = new LinkedHashSet<>();
        if (text == null) {
            return handles;
        }
        Matcher matcher = MENTION.matcher(text);
        while (matcher.find()) {
            String handle = matcher.group(1);
            // "@ana." at the end of a sentence: the trailing dot is punctuation.
            while (handle.endsWith(".") || handle.endsWith("-")) {
                handle = handle.substring(0, handle.length() - 1);
            }
            if (!handle.isEmpty()) {
                handles.add(handle.toLowerCase(Locale.ROOT));
            }
        }
        return handles;
    }
}
