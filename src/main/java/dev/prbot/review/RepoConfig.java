package dev.prbot.review;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Per-repository settings from {@code .prbot.yml} on the PR's base branch. The file is untrusted
 * input: it is parsed with the safe YAML constructor, unknown keys are an error (so a typo is
 * reported instead of silently ignored), and any problem makes the whole file fall back to
 * defaults.
 */
public record RepoConfig(List<String> ignore, Strictness strictness) {

    public static final String FILE_NAME = ".prbot.yml";
    public static final RepoConfig DEFAULTS = new RepoConfig(List.of(), Strictness.NORMAL);

    static final int MAX_FILE_CHARS = 16_000;
    static final int MAX_PATTERNS = 50;
    static final int MAX_PATTERN_CHARS = 200;

    private static final Set<String> KNOWN_KEYS = Set.of("ignore", "strictness");

    /** Which findings are posted: the lowest severity that is kept and the comment cap. */
    public enum Strictness {
        LOW(3, 5),
        NORMAL(2, 10),
        HIGH(1, 15);

        private final int minRank;
        private final int maxComments;

        Strictness(int minRank, int maxComments) {
            this.minRank = minRank;
            this.maxComments = maxComments;
        }

        public boolean allows(String severity) {
            return rank(severity) >= minRank;
        }

        public int maxComments() {
            return maxComments;
        }
    }

    /** The config to use, and a note for the review summary if the file had to be ignored. */
    public record Loaded(RepoConfig config, String warning) {}

    /** Higher is more severe; an unknown severity counts as the lowest. */
    public static int rank(String severity) {
        if (severity == null) {
            return 1;
        }
        return switch (severity.toLowerCase(Locale.ROOT)) {
            case "high" -> 3;
            case "medium" -> 2;
            default -> 1;
        };
    }

    /** True if the path matches an {@code ignore} pattern. */
    public boolean ignores(String path) {
        return ignore.stream().anyMatch(p -> glob(p).matcher(path).matches());
    }

    /** A null or blank file means "no config" and is not a problem. */
    public static Loaded parse(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return new Loaded(DEFAULTS, null);
        }
        try {
            return new Loaded(read(yaml), null);
        } catch (InvalidConfig e) {
            return new Loaded(
                    DEFAULTS,
                    "`"
                            + FILE_NAME
                            + "` was ignored ("
                            + e.getMessage()
                            + "); defaults were used.");
        }
    }

    private static RepoConfig read(String yaml) {
        if (yaml.length() > MAX_FILE_CHARS) {
            throw new InvalidConfig("file is larger than " + MAX_FILE_CHARS + " characters");
        }
        Object root;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(10);
            root = new Yaml(new SafeConstructor(options)).load(yaml);
        } catch (YAMLException | ClassCastException e) {
            // The parser message quotes the file content, so it is not repeated in the review.
            throw new InvalidConfig("not valid YAML");
        }
        if (root == null) {
            return DEFAULTS;
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new InvalidConfig("top level must be a mapping");
        }
        for (Object key : map.keySet()) {
            if (!KNOWN_KEYS.contains(String.valueOf(key))) {
                throw new InvalidConfig("unknown key `" + abbreviate(String.valueOf(key)) + "`");
            }
        }
        return new RepoConfig(readIgnore(map.get("ignore")), readStrictness(map.get("strictness")));
    }

    private static List<String> readIgnore(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new InvalidConfig("`ignore` must be a list of paths");
        }
        if (list.size() > MAX_PATTERNS) {
            throw new InvalidConfig("`ignore` has more than " + MAX_PATTERNS + " entries");
        }
        List<String> patterns = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String s) || s.isBlank() || s.length() > MAX_PATTERN_CHARS) {
                throw new InvalidConfig(
                        "each `ignore` entry must be a non-empty string of at most "
                                + MAX_PATTERN_CHARS
                                + " characters");
            }
            patterns.add(s.trim());
        }
        return List.copyOf(patterns);
    }

    private static Strictness readStrictness(Object value) {
        if (value == null) {
            return Strictness.NORMAL;
        }
        if (value instanceof String s) {
            try {
                return Strictness.valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // falls through to the error below
            }
        }
        throw new InvalidConfig("`strictness` must be low, normal or high");
    }

    private static String abbreviate(String s) {
        return s.length() <= 30 ? s : s.substring(0, 30) + "...";
    }

    /**
     * Gitignore-like matching on the repo-relative path: {@code *} stays inside one directory,
     * {@code **} crosses directories, {@code ?} is one character. A pattern without a slash matches
     * at any depth ({@code *.md}, {@code docs/}) unless it starts with {@code /}, and a pattern
     * that matches a directory covers everything below it ({@code docs} or {@code docs/}).
     */
    static Pattern glob(String pattern) {
        boolean anchored = pattern.startsWith("/");
        String p = anchored ? pattern.substring(1) : pattern;
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        StringBuilder re = new StringBuilder(anchored || p.contains("/") ? "" : "(?:.*/)?");
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '*') {
                if (i + 1 < p.length() && p.charAt(i + 1) == '*') {
                    i++;
                    if (i + 1 < p.length() && p.charAt(i + 1) == '/') {
                        i++;
                        re.append("(?:.*/)?");
                    } else {
                        re.append(".*");
                    }
                } else {
                    re.append("[^/]*");
                }
            } else if (c == '?') {
                re.append("[^/]");
            } else if (Character.isLetterOrDigit(c) || c == '/' || c == '_') {
                re.append(c);
            } else {
                re.append('\\').append(c);
            }
        }
        re.append("(?:/.*)?");
        return Pattern.compile(re.toString());
    }

    private static final class InvalidConfig extends RuntimeException {
        InvalidConfig(String message) {
            super(message, null, false, false);
        }
    }
}
