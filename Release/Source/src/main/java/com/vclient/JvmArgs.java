package com.vclient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses the "extra JVM arguments" setting. Only memory/GC/system-property style flags are accepted: anything that
 * could load code (-javaagent, -agentlib, -Xbootclasspath, -XX:OnError ...) or flip V-Client's own permission
 * switches (-Dvclient.*) is refused, which also keeps an imported profile from smuggling those in.
 */
final class JvmArgs {
    private static final List<String> FORBIDDEN_FRAGMENTS = List.of(
            "onerror", "onoutofmemory", "vmoptionsfile", "flags=", "bootclasspath", "-xrun", "-xdebug", "jdwp",
            "javaagent", "agentlib", "agentpath", "unlockdiagnostic", "-dvclient.", "-djava.security", "-dcom.sun.management");

    private JvmArgs() {}

    /** Splits on whitespace (double quotes group words) and drops arguments that are not allowed. */
    static List<String> parse(String text) {
        List<String> accepted = new ArrayList<>();
        for (String token : split(text)) {
            if (problem(token) == null) accepted.add(token);
        }
        return List.copyOf(accepted);
    }

    /** A message naming the first refused argument, or null when every argument is fine. */
    static String validate(String text) {
        for (String token : split(text)) {
            String problem = problem(token);
            if (problem != null) return problem;
        }
        return null;
    }

    private static String problem(String token) {
        if (!(token.startsWith("-X") || token.startsWith("-D") || token.startsWith("-XX:"))) {
            return "\"" + token + "\" is not allowed: only -X..., -XX:... and -D... arguments can be added.";
        }
        String lower = token.toLowerCase(Locale.ROOT);
        for (String fragment : FORBIDDEN_FRAGMENTS) {
            if (lower.contains(fragment)) return "\"" + token + "\" is not allowed for safety.";
        }
        if (token.length() > 200) return "An argument is too long.";
        return null;
    }

    static List<String> split(String text) {
        List<String> parts = new ArrayList<>();
        if (text == null) return parts;
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (char c : text.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (!current.isEmpty()) parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) parts.add(current.toString());
        return parts;
    }
}
