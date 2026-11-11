package dev.prbot.review;

import java.util.ArrayList;
import java.util.List;

/**
 * Groups files into batches that each fit a model request. Free models have small context windows
 * and per-minute token limits, so a big PR is reviewed in several calls instead of one. A file is
 * never split; a file larger than the limit gets a batch of its own.
 */
public final class DiffBatcher {

    private DiffBatcher() {}

    public static List<List<FileDiff>> batches(List<FileDiff> files, int maxChars) {
        List<List<FileDiff>> batches = new ArrayList<>();
        List<FileDiff> current = new ArrayList<>();
        long size = 0;
        for (FileDiff file : files) {
            int length = file.text().length();
            if (!current.isEmpty() && size + length > maxChars) {
                batches.add(current);
                current = new ArrayList<>();
                size = 0;
            }
            current.add(file);
            size += length;
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    public static String join(List<FileDiff> batch) {
        StringBuilder sb = new StringBuilder();
        batch.forEach(f -> sb.append(f.text()));
        return sb.toString();
    }
}
