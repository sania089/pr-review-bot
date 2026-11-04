package dev.prbot.review;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps whole files only, so the model never sees a hunk or line cut in half. Files are taken in
 * diff order; a file that does not fit is skipped and later, smaller files may still be kept.
 */
public final class DiffTruncator {

    public record Result(List<FileDiff> kept, List<String> skippedPaths) {

        public String joinedText() {
            StringBuilder sb = new StringBuilder();
            kept.forEach(f -> sb.append(f.text()));
            return sb.toString();
        }
    }

    private DiffTruncator() {}

    public static Result fit(List<FileDiff> files, int maxChars) {
        List<FileDiff> kept = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        long used = 0;
        for (FileDiff file : files) {
            if (used + file.text().length() <= maxChars) {
                kept.add(file);
                used += file.text().length();
            } else {
                skipped.add(file.path());
            }
        }
        return new Result(kept, skipped);
    }
}
