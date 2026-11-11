package dev.prbot.persistence;

/**
 * Aggregates over the last {@code days} days. {@code failureRate} is FAILED / (POSTED + FAILED), so
 * skipped and still-queued reviews do not dilute it. Latency percentiles cover POSTED reviews only
 * and are null when there are none.
 */
public record StatsWindow(
        int days,
        long reviews,
        long posted,
        long failed,
        long skipped,
        long commentsPosted,
        double failureRate,
        Double p50LatencyMs,
        Double p95LatencyMs,
        long inputTokens,
        long outputTokens) {}
