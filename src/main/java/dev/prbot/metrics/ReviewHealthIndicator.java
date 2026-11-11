package dev.prbot.metrics;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Adds this instance's review counters to the health details. It never touches the database (that
 * would keep Neon awake) and the details are shown only to a logged-in admin, not to the pinger.
 */
@Component("reviews")
class ReviewHealthIndicator implements HealthIndicator {

    private final ReviewMetrics metrics;

    ReviewHealthIndicator(ReviewMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public Health health() {
        ReviewMetrics.Snapshot s = metrics.snapshot();
        return Health.up()
                .withDetail("since", s.since().toString())
                .withDetail("reviewsByStatus", s.reviewsByStatus())
                .withDetail("inputTokens", s.inputTokens())
                .withDetail("outputTokens", s.outputTokens())
                .withDetail("meanLlmLatencyMs", s.meanLlmLatencyMs())
                .build();
    }
}
