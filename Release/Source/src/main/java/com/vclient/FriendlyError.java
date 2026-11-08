package com.vclient;

import java.util.Locale;

/**
 * Turns an exception into a short message a player can act on. The technical text (cause chain, stack trace) is
 * still available for the collapsible "Details" section and the "Copy logs" button; it is just not the headline.
 */
final class FriendlyError {
    /** {@code title} is one short line, {@code message} one or two sentences, {@code details} the raw technical text. */
    record Message(String title, String message, String details) {}

    private FriendlyError() {}

    static Message of(Throwable error) {
        String technical = error == null ? "" : PlatformTls.describeFailure(error);
        String lower = technical.toLowerCase(Locale.ROOT);
        if (error == null) return new Message("Something went wrong", "An unknown problem occurred.", "");
        if (BackendClient.isWaking(error)) {
            return new Message("Server waking up…", "The V-Client server is starting after being idle. This can take up to a minute; "
                    + "V-Client keeps retrying and shows the last known information meanwhile.", technical);
        }
        String interception = PlatformTls.interceptionHint(error);
        if (!interception.isEmpty()) {
            return new Message("Secure connection blocked", interception, technical);
        }
        if (lower.contains("unknownhost") || lower.contains("dns") && lower.contains("fail")) {
            return new Message("Can't find the server", "Your DNS could not look up the address. Check your internet connection, "
                    + "or set your DNS to automatic / 1.1.1.1.", technical);
        }
        if (lower.contains("timed out") || lower.contains("timeout") || lower.contains("connectexception")) {
            return new Message("Connection timed out", "The server did not answer in time. Check your internet connection and try again.", technical);
        }
        if (lower.contains("minecraft exited with code")) {
            return new Message("Minecraft crashed", "The game closed unexpectedly. Open the crash report to see what happened, "
                    + "or copy the logs to share with support.", technical);
        }
        if (lower.contains("outofmemory") || lower.contains("could not reserve enough space")) {
            return new Message("Not enough memory", "Lower the game memory in Settings, or close other programs and try again.", technical);
        }
        if (lower.contains("java") && (lower.contains("no compatible runtime") || lower.contains("requires java"))) {
            return new Message("Java problem", "The Java runtime this version needs is missing. Connect to the internet once so V-Client "
                    + "can download it, or pick a Java version in Settings.", technical);
        }
        if (lower.contains("accessdenied") || lower.contains("being used by another process")) {
            return new Message("File is locked", "Another program (often antivirus or a running game) is using a file V-Client needs. "
                    + "Close it and try again.", technical);
        }
        if (lower.contains("nosuchfile") || lower.contains("filenotfound") || lower.contains("no longer installed")) {
            return new Message("A game file is missing", "Press Repair on the home screen to download the missing files again.", technical);
        }
        String first = error.getMessage() == null || error.getMessage().isBlank() ? error.getClass().getSimpleName() : error.getMessage();
        first = first.lines().findFirst().orElse(first).strip();
        if (first.length() > 140) first = first.substring(0, 137) + "…";
        return new Message("Something went wrong", first, technical);
    }
}
