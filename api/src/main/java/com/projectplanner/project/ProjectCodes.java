package com.projectplanner.project;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Project codes: short references like "ACME-WEB", unique within an organisation. */
public final class ProjectCodes {

    public static final int MAX_LENGTH = 16;

    private static final Pattern NUMBERED = Pattern.compile("^(.*)-(\\d{1,4})$");

    private ProjectCodes() {
    }

    /** A code as it is stored: trimmed and in upper case. */
    public static String normalise(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * A free code for a copy of the project with {@code code}: the same code with the
     * next number that is not in use, "ACME-WEB-2" and then "ACME-WEB-3". A code that
     * already ends in a number counts on from it, and a long code is cut so that the
     * result still fits. It is only a proposal: the person making the copy may type
     * another, and the API checks whichever arrives.
     */
    public static String next(String code, Collection<String> taken) {
        Set<String> used = new HashSet<>();
        taken.forEach(c -> used.add(normalise(c)));
        String base = normalise(code);
        int number = 2;
        Matcher numbered = NUMBERED.matcher(base);
        if (numbered.matches() && !numbered.group(1).isEmpty()) {
            base = numbered.group(1);
            number = Integer.parseInt(numbered.group(2)) + 1;
        }
        while (true) {
            String suffix = "-" + number;
            String stem = base.length() + suffix.length() > MAX_LENGTH ? base.substring(0, MAX_LENGTH - suffix.length()) : base;
            // Cutting must not leave "ACME--2".
            while (stem.endsWith("-") && stem.length() > 1) {
                stem = stem.substring(0, stem.length() - 1);
            }
            String candidate = stem + suffix;
            if (!used.contains(candidate)) {
                return candidate;
            }
            number++;
        }
    }
}
