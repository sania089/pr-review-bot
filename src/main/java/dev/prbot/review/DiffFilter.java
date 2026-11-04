package dev.prbot.review;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/** Decides which files are not worth sending to the model: noise costs tokens and quota. */
public final class DiffFilter {

    private static final Set<String> LOCKFILES =
            Set.of(
                    "package-lock.json",
                    "npm-shrinkwrap.json",
                    "yarn.lock",
                    "pnpm-lock.yaml",
                    "bun.lockb",
                    "cargo.lock",
                    "gemfile.lock",
                    "poetry.lock",
                    "pipfile.lock",
                    "composer.lock",
                    "go.sum",
                    "gradle.lockfile",
                    "packages.lock.json",
                    "pubspec.lock",
                    "podfile.lock",
                    "mix.lock");

    private static final List<String> BINARY_EXTENSIONS =
            List.of(
                    ".png", ".jpg", ".jpeg", ".gif", ".ico", ".webp", ".svg", ".pdf", ".zip", ".gz",
                    ".tar", ".jar", ".war", ".class", ".exe", ".dll", ".so", ".woff", ".woff2",
                    ".ttf", ".eot", ".mp3", ".mp4", ".mov");

    private static final List<String> GENERATED_SUFFIXES =
            List.of(
                    ".min.js",
                    ".min.css",
                    ".map",
                    ".pb.go",
                    "_pb2.py",
                    ".g.dart",
                    ".designer.cs",
                    ".generated.ts",
                    ".generated.js",
                    ".snap");

    private static final Set<String> VENDORED_DIRS =
            Set.of("node_modules", "vendor", "third_party", "bower_components", "pods", "dist");

    private static final List<String> GENERATED_MARKERS =
            List.of("@generated", "Code generated", "DO NOT EDIT");

    /** How far into a file diff a "generated" banner is looked for. */
    private static final int MARKER_WINDOW = 800;

    private static final List<Predicate<FileDiff>> IGNORE_RULES =
            List.of(
                    f -> !f.hasHunks(), // pure renames, mode changes, binary patches
                    FileDiff::isBinary,
                    FileDiff::isDeleted, // nothing left to comment on
                    f -> hasSuffix(f.path(), BINARY_EXTENSIONS),
                    f -> isLockfile(f.path()),
                    f -> hasSuffix(f.path(), GENERATED_SUFFIXES),
                    f -> isVendored(f.path()),
                    DiffFilter::hasGeneratedBanner);

    private DiffFilter() {}

    public static boolean isIgnored(FileDiff file) {
        return IGNORE_RULES.stream().anyMatch(rule -> rule.test(file));
    }

    public static List<FileDiff> relevant(List<FileDiff> files) {
        return files.stream().filter(f -> !isIgnored(f)).toList();
    }

    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean isLockfile(String path) {
        String name = fileName(path);
        return LOCKFILES.contains(name) || name.endsWith(".lock");
    }

    private static boolean hasSuffix(String path, List<String> suffixes) {
        String lower = path.toLowerCase(Locale.ROOT);
        return suffixes.stream().anyMatch(lower::endsWith);
    }

    private static boolean isVendored(String path) {
        String[] parts = path.toLowerCase(Locale.ROOT).split("/");
        for (int i = 0; i < parts.length - 1; i++) { // directories only, not the file name
            if (VENDORED_DIRS.contains(parts[i])) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasGeneratedBanner(FileDiff file) {
        String head = file.text().substring(0, Math.min(file.text().length(), MARKER_WINDOW));
        return GENERATED_MARKERS.stream().anyMatch(head::contains);
    }
}
