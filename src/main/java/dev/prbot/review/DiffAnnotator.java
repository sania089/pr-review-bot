package dev.prbot.review;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prefixes every added/context line of a unified diff with its line number in the new file, and
 * records which lines GitHub will accept inline comments on. GitHub rejects the whole review with
 * a 422 if a single comment points outside the diff, so the model's output is checked against this.
 */
public final class DiffAnnotator {

    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");

    public record Annotated(String text, Map<String, Set<Integer>> commentableLines) {
        public boolean canComment(String path, int line) {
            return commentableLines.getOrDefault(path, Set.of()).contains(line);
        }
    }

    private DiffAnnotator() {
    }

    public static Annotated annotate(String diff) {
        StringBuilder out = new StringBuilder();
        Map<String, Set<Integer>> valid = new HashMap<>();
        String path = null;
        boolean inHunk = false;
        int newLine = 0;

        for (String line : diff.split("\n", -1)) {
            if (line.startsWith("diff --git ")) {
                inHunk = false;
                path = null;
                out.append(line).append('\n');
                continue;
            }
            if (!inHunk) {
                if (line.startsWith("+++ b/")) {
                    path = line.substring(6);
                } else {
                    Matcher m = HUNK.matcher(line);
                    if (m.matches()) {
                        newLine = Integer.parseInt(m.group(1));
                        inHunk = true;
                    }
                }
                out.append(line).append('\n');
                continue;
            }

            Matcher m = HUNK.matcher(line);
            if (m.matches()) {
                newLine = Integer.parseInt(m.group(1));
                out.append(line).append('\n');
            } else if (line.startsWith("+") || line.startsWith(" ")) {
                if (path != null) {
                    valid.computeIfAbsent(path, k -> new HashSet<>()).add(newLine);
                }
                out.append("L").append(newLine).append(": ").append(line).append('\n');
                newLine++;
            } else {
                out.append(line).append('\n');
            }
        }
        return new Annotated(out.toString(), valid);
    }
}
