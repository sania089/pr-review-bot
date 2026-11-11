package dev.prbot.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

class ReviewMetricsTest {

    private final ReviewMetrics metrics = new ReviewMetrics(new SimpleMeterRegistry());

    @Test
    void startsAtZero() {
        ReviewMetrics.Snapshot s = metrics.snapshot();

        assertThat(s.reviewsByStatus())
                .containsEntry("POSTED", 0L)
                .containsEntry("FAILED", 0L)
                .containsEntry("SKIPPED", 0L);
        assertThat(s.llmReviews()).isZero();
        assertThat(s.meanLlmLatencyMs()).isZero();
    }

    @Test
    void countsReviewsTokensAndLatency() {
        metrics.reviewFinished("POSTED");
        metrics.reviewFinished("POSTED");
        metrics.reviewFinished("FAILED");
        metrics.llmUsed(100, 10, 1000);
        metrics.llmUsed(300, 30, 3000);

        ReviewMetrics.Snapshot s = metrics.snapshot();

        assertThat(s.reviewsByStatus())
                .containsEntry("POSTED", 2L)
                .containsEntry("FAILED", 1L)
                .containsEntry("SKIPPED", 0L);
        assertThat(s.inputTokens()).isEqualTo(400);
        assertThat(s.outputTokens()).isEqualTo(40);
        assertThat(s.llmReviews()).isEqualTo(2);
        assertThat(s.meanLlmLatencyMs()).isEqualTo(2000.0);
        assertThat(s.maxLlmLatencyMs()).isEqualTo(3000.0);
    }

    @Test
    void healthIsUpAndCarriesTheCounters() {
        metrics.reviewFinished("SKIPPED");

        Health health = new ReviewHealthIndicator(metrics).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsKeys("since", "reviewsByStatus", "inputTokens");
    }
}
