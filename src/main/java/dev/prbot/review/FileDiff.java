package dev.prbot.review;

import java.util.ArrayList;
import java.util.List;

/**
 * The part of a unified diff that belongs to one file, kept as the exact original text (including
 * the trailing newline) so that joining the pieces again gives back a valid diff.
 */
public record FileDiff(String path, String text) {

    private static final String HEADER = "diff --git ";

    /** Splits a full pull request diff at each "diff --git" line. */
    public static List<FileDiff> split(String diff) {
        List<FileDiff> files = new ArrayList<>();
        if (diff == null || diff.isEmpty()) {
            return files;
        }
        for (String chunk : diff.split("(?m)^(?=diff --git )")) {
            if (chunk.startsWith(HEADER)) {
                files.add(new FileDiff(pathOf(chunk), chunk));
            }
        }
        return files;
    }

    /** True if the file has at least one hunk, i.e. real changed lines to look at. */
    public boolean hasHunks() {
        return text.contains("\n@@");
    }

    // The markers below are git header lines. Code lines always start with "+", "-" or " ",
    // so a line starting with these words cannot be file content.

    public boolean isBinary() {
        return text.contains("\nBinary files ") || text.contains("\nGIT binary patch");
    }

    public boolean isDeleted() {
        return text.contains("\ndeleted file mode");
    }

    private static String pathOf(String chunk) {
        String[] lines = chunk.split("\n", 8); // headers only, never scans the whole file
        for (String line : lines) {
            if (line.startsWith("+++ b/")) {
                return line.substring(6);
            }
        }
        for (String line : lines) {
            if (line.startsWith("--- a/")) { // deleted file: there is no "+++ b/" line
                return line.substring(6);
            }
        }
        String firstLine = lines[0];
        int b = firstLine.lastIndexOf(" b/");
        return b >= 0 ? firstLine.substring(b + 3) : firstLine.substring(HEADER.length());
    }
}
