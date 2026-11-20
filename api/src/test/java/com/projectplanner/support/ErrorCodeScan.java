package com.projectplanner.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the error codes out of the sources, for the test that holds the API reference to
 * them. It looks for the three ways the code makes an error with a code of its own:
 * {@code ApiException.badRequest(code, ...)}, {@code ApiException.conflict(code, ...)} and
 * {@code new ApiException(status, code, ...)}. Comments are skipped, a call may run over
 * several lines, and a code may be a string literal or a string constant. Anything else
 * (a variable, a method call, a status that is not a number) is reported as unreadable
 * with its place, so that the test fails instead of guessing.
 */
public final class ErrorCodeScan {

    /** One place where the code answers with an error code. */
    public record Thrown(String code, int status, String file, int line) {
        public String place() {
            return file + ":" + line;
        }
    }

    /** A place that makes an error and that the scan cannot read. */
    public record Unreadable(String file, int line, String why) {
    }

    public record Result(List<Thrown> thrown, List<Unreadable> unreadable) {
    }

    private static final Pattern FACTORY = Pattern.compile("\\bApiException\\s*\\.\\s*(badRequest|conflict)\\s*\\(");
    private static final Pattern STATIC_FACTORY = Pattern.compile("(?<![.\\w])(badRequest|conflict)\\s*\\(");
    private static final Pattern STATIC_IMPORT = Pattern.compile("import\\s+static\\s+[\\w.]*\\bApiException\\s*\\.\\s*(\\*|badRequest|conflict)\\s*;");
    private static final Pattern CONSTRUCTOR = Pattern.compile("\\bnew\\s+ApiException\\s*\\(");
    private static final Pattern LITERAL = Pattern.compile("\"([^\"\\\\]*)\"");
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][\\w.]*");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_]\\w*");

    private ErrorCodeScan() {
    }

    /** @param sources file name to content, for every source file that can make an error */
    public static Result scan(Map<String, String> sources) {
        Map<String, String> code = new TreeMap<>();
        sources.forEach((file, text) -> code.put(file, withoutComments(text)));

        List<Thrown> thrown = new ArrayList<>();
        List<Unreadable> unreadable = new ArrayList<>();
        code.forEach((file, text) -> {
            scanCalls(FACTORY, file, text, code, thrown, unreadable);
            if (STATIC_IMPORT.matcher(text).find()) {
                scanCalls(STATIC_FACTORY, file, text, code, thrown, unreadable);
            }
            Matcher constructor = CONSTRUCTOR.matcher(text);
            while (constructor.find()) {
                int line = lineOf(text, constructor.start());
                List<String> arguments = arguments(text, constructor.end());
                if (arguments.size() < 2) {
                    unreadable.add(new Unreadable(file, line, "new ApiException(...) without a status and a code"));
                    continue;
                }
                String status = arguments.get(0);
                if (!status.matches("\\d{3}")) {
                    unreadable.add(new Unreadable(file, line, "the status is `" + status + "`, not a number"));
                    continue;
                }
                read(arguments.get(1), code, file, line, unreadable)
                        .ifPresent(value -> thrown.add(new Thrown(value, Integer.parseInt(status), file, line)));
            }
        });
        return new Result(thrown, unreadable);
    }

    private static void scanCalls(Pattern call, String file, String text, Map<String, String> code,
                                  List<Thrown> thrown, List<Unreadable> unreadable) {
        Matcher matcher = call.matcher(text);
        while (matcher.find()) {
            int line = lineOf(text, matcher.start());
            int status = matcher.group(1).equals("badRequest") ? 400 : 409;
            List<String> arguments = arguments(text, matcher.end());
            if (arguments.isEmpty()) {
                unreadable.add(new Unreadable(file, line, matcher.group(1) + "(...) without a code"));
                continue;
            }
            read(arguments.get(0), code, file, line, unreadable).ifPresent(value -> thrown.add(new Thrown(value, status, file, line)));
        }
    }

    /** The code an argument stands for: a string literal, or a constant that is one. */
    private static Optional<String> read(String argument, Map<String, String> code, String file, int line,
                                                   List<Unreadable> unreadable) {
        Matcher literal = LITERAL.matcher(argument);
        if (literal.matches()) {
            return Optional.of(literal.group(1));
        }
        if (NAME.matcher(argument).matches()) {
            String constant = argument.substring(argument.lastIndexOf('.') + 1);
            Pattern definition = Pattern.compile("\\bString\\s+" + Pattern.quote(constant) + "\\s*=\\s*\"([^\"\\\\]*)\"\\s*;");
            Set<String> values = new TreeSet<>();
            code.values().forEach(text -> {
                Matcher defined = definition.matcher(text);
                while (defined.find()) {
                    values.add(defined.group(1));
                }
            });
            if (values.size() == 1) {
                return Optional.of(values.iterator().next());
            }
            unreadable.add(new Unreadable(file, line, "the code is `" + argument + "`, and "
                    + (values.isEmpty() ? "no string constant of that name is in the sources" : "that name stands for " + values)));
            return Optional.empty();
        }
        unreadable.add(new Unreadable(file, line, "the code is `" + argument.replaceAll("\\s+", " ") + "`, not a string literal or a constant"));
        return Optional.empty();
    }

    /**
     * Which classes each class can reach: the ones its source mentions, and the ones those
     * mention in turn. A class is a file here, and it is known by its name without ".java".
     */
    public static Map<String, Set<String>> reach(Map<String, String> sources) {
        Map<String, String> classOf = new HashMap<>();
        sources.keySet().forEach(file -> classOf.put(file, className(file)));
        Set<String> classes = new HashSet<>(classOf.values());

        Map<String, Set<String>> mentions = new HashMap<>();
        sources.forEach((file, text) -> {
            Set<String> mentioned = new HashSet<>();
            Matcher identifier = IDENTIFIER.matcher(withoutStrings(withoutComments(text)));
            while (identifier.find()) {
                if (classes.contains(identifier.group())) {
                    mentioned.add(identifier.group());
                }
            }
            mentions.put(classOf.get(file), mentioned);
        });

        Map<String, Set<String>> reach = new TreeMap<>();
        for (String start : classes) {
            Set<String> seen = new TreeSet<>();
            List<String> todo = new ArrayList<>(List.of(start));
            while (!todo.isEmpty()) {
                String next = todo.removeLast();
                if (seen.add(next)) {
                    todo.addAll(mentions.getOrDefault(next, Set.of()));
                }
            }
            reach.put(start, seen);
        }
        return reach;
    }

    public static String className(String file) {
        String name = file.substring(file.replace('\\', '/').lastIndexOf('/') + 1);
        return name.endsWith(".java") ? name.substring(0, name.length() - ".java".length()) : name;
    }

    /** The arguments of a call, given the place right after its opening bracket. */
    static List<String> arguments(String text, int from) {
        List<String> arguments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                int end = endOfLiteral(text, i);
                current.append(text, i, end);
                i = end - 1;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
                current.append(c);
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) {
                    break;
                }
                depth--;
                current.append(c);
            } else if (c == ',' && depth == 0) {
                arguments.add(current.toString().strip());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.toString().isBlank()) {
            arguments.add(current.toString().strip());
        }
        return arguments;
    }

    /** The source with every comment replaced by spaces. Line breaks and string literals stay where they are. */
    public static String withoutComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                int end = endOfLiteral(source, i);
                out.append(source, i, end);
                i = end - 1;
            } else if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                end = end < 0 ? source.length() : end;
                out.append(" ".repeat(end - i));
                i = end - 1;
            } else if (source.startsWith("/*", i)) {
                int close = source.indexOf("*/", i + 2);
                int end = close < 0 ? source.length() : close + 2;
                for (int j = i; j < end; j++) {
                    out.append(source.charAt(j) == '\n' ? '\n' : ' ');
                }
                i = end - 1;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String withoutStrings(String code) {
        StringBuilder out = new StringBuilder(code.length());
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '"' || c == '\'') {
                int end = endOfLiteral(code, i);
                out.append(' ');
                i = end - 1;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** The place right after the string, text block or character literal that starts at {@code from}. */
    private static int endOfLiteral(String text, int from) {
        char quote = text.charAt(from);
        if (quote == '"' && text.startsWith("\"\"\"", from)) {
            int close = text.indexOf("\"\"\"", from + 3);
            return close < 0 ? text.length() : close + 3;
        }
        for (int i = from + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == quote || c == '\n') {
                return i + 1;
            }
        }
        return text.length();
    }

    private static int lineOf(String text, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
