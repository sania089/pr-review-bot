package dev.prbot.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Process-lifetime counters (they reset on restart, which is frequent on a free host). The history
 * that survives restarts comes from the database; these show what this instance did since it
 * started, and feed {@code /api/stats} and the health details without a metrics stack.
 */
@Component
public class ReviewMetrics {

    static final String REVIEWS = "prbot.reviews";
    static final String TOKENS = "prbot.llm.tokens";
    static final String LATENCY = "prbot.llm.latency";
    private static final List<String> STATUSES = List.of("POSTED", "FAILED", "SKIPPED");

    private final MeterRegistry registry;
    private final Timer latency;
    private final Instant since = Instant.now();

    public ReviewMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.latency = Timer.builder(LATENCY).description("LLM time per review").register(registry);
    }

    /** One review reached a final status (POSTED, FAILED or SKIPPED). */
    public void reviewFinished(String status) {
        registry.counter(REVIEWS, "status", status).increment();
    }

    /** Usage of a review that was posted. */
    public void llmUsed(int inputTokens, int outputTokens, long latencyMs) {
        registry.counter(TOKENS, "direction", "input").increment(inputTokens);
        registry.counter(TOKENS, "direction", "output").increment(outputTokens);
        latency.record(Duration.ofMillis(latencyMs));
    }

    public record Snapshot(
            Instant since,
            Map<String, Long> reviewsByStatus,
            long inputTokens,
            long outputTokens,
            long llmReviews,
            double meanLlmLatencyMs,
            double maxLlmLatencyMs) {}

    public Snapshot snapshot() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (String status : STATUSES) {
            byStatus.put(status, (long) registry.counter(REVIEWS, "status", status).count());
        }
        return new Snapshot(
                since,
                byStatus,
                (long) registry.counter(TOKENS, "direction", "input").count(),
                (long) registry.counter(TOKENS, "direction", "output").count(),
                latency.count(),
                latency.mean(TimeUnit.MILLISECONDS),
                latency.max(TimeUnit.MILLISECONDS));
    }
}
