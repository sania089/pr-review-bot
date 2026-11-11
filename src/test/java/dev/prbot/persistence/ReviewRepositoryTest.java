package dev.prbot.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.prbot.metrics.ReviewMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Runs the real Flyway migration against Postgres; skipped automatically when Docker is absent. */
@Testcontainers(disabledWithoutDocker = true)
class ReviewRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcClient jdbc;
    private static ReviewRepository repository;
    private static ReviewMetrics metrics;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource ds =
                new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = JdbcClient.create(ds);
        metrics = new ReviewMetrics(new SimpleMeterRegistry());
        repository = new ReviewRepository(jdbc, metrics);
    }

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE reviews, llm_usage").update();
    }

    private Long enqueue(String sha) {
        return repository.enqueue("acme/w", 1, sha, "GEMINI", "m").orElse(null);
    }

    private Map<String, Object> row(long id) {
        return jdbc.sql("SELECT * FROM reviews WHERE id = :id").param("id", id).query().singleRow();
    }

    @Test
    void insertsQueuedRowAndIgnoresDuplicateWhileQueued() {
        Long id = enqueue("sha1");

        assertThat(row(id)).containsEntry("status", "QUEUED").containsEntry("provider", "GEMINI");
        assertThat(enqueue("sha1")).isNull();
        assertThat(enqueue("sha2")).isNotNull(); // a new head SHA is a new review
    }

    @Test
    void postedReviewStoresUsageAndIsNeverRequeued() {
        long id = enqueue("sha1");

        repository.markPosted(id, 3, 100, 20, 1500, "v1");

        assertThat(row(id))
                .containsEntry("status", "POSTED")
                .containsEntry("comments_posted", 3)
                .containsEntry("input_tokens", 100)
                .containsEntry("output_tokens", 20)
                .containsEntry("latency_ms", 1500L)
                .containsEntry("prompt_version", "v1");
        assertThat(row(id).get("completed_at")).isNotNull();
        assertThat(enqueue("sha1")).isNull();
        assertThat(metrics.snapshot().reviewsByStatus()).containsEntry("POSTED", 1L);
        assertThat(metrics.snapshot().inputTokens()).isEqualTo(100);
    }

    @Test
    void failedAndSkippedReviewsAreRequeuedOnRedelivery() {
        long failed = enqueue("sha1");
        repository.markFailed(failed, "x".repeat(2000));

        assertThat((String) row(failed).get("error")).hasSize(500);
        assertThat(enqueue("sha1")).isEqualTo(failed);
        assertThat(row(failed)).containsEntry("status", "QUEUED").containsEntry("error", null);

        repository.markSkipped(failed, "queue full");
        assertThat(row(failed))
                .containsEntry("status", "SKIPPED")
                .containsEntry("error", "queue full");
        assertThat(enqueue("sha1")).isEqualTo(failed);
    }

    @Test
    void pagesAndFiltersReviewsNewestFirst() {
        long a = repository.enqueue("acme/a", 1, "s1", "GEMINI", "m").orElseThrow();
        long b = repository.enqueue("acme/a", 2, "s2", "GEMINI", "m").orElseThrow();
        long c = repository.enqueue("acme/b", 1, "s3", "GEMINI", "m").orElseThrow();
        repository.markPosted(a, 2, 10, 5, 1000, "v1");
        repository.markFailed(c, "boom");

        assertThat(repository.count(null, null)).isEqualTo(3);
        assertThat(repository.findPage(null, null, 2, 0)).extracting("id").containsExactly(c, b);
        assertThat(repository.findPage(null, null, 2, 2)).extracting("id").containsExactly(a);
        assertThat(repository.count("acme/a", null)).isEqualTo(2);
        assertThat(repository.findPage(null, "FAILED", 10, 0))
                .singleElement()
                .satisfies(r -> assertThat(r.error()).isEqualTo("boom"));
        assertThat(repository.count("acme/a", "POSTED")).isEqualTo(1);

        assertThat(repository.findById(a))
                .hasValueSatisfying(
                        r -> {
                            assertThat(r.commentsPosted()).isEqualTo(2);
                            assertThat(r.latencyMs()).isEqualTo(1000L);
                            assertThat(r.createdAt()).isNotNull();
                        });
        assertThat(repository.findById(-1)).isEmpty();
    }

    @Test
    void statsAggregateOverTheWindow() {
        StatsRepository stats = new StatsRepository(jdbc);
        assertThat(stats.window(7).reviews()).isZero();
        assertThat(stats.window(7).p50LatencyMs()).isNull();

        long a = repository.enqueue("acme/a", 1, "s1", "GEMINI", "m").orElseThrow();
        long b = repository.enqueue("acme/a", 2, "s2", "GEMINI", "m").orElseThrow();
        long c = repository.enqueue("acme/a", 3, "s3", "GEMINI", "m").orElseThrow();
        long d = repository.enqueue("acme/a", 4, "s4", "GEMINI", "m").orElseThrow();
        long old = repository.enqueue("acme/a", 5, "s5", "GEMINI", "m").orElseThrow();
        repository.markPosted(a, 2, 100, 10, 1000, "v1");
        repository.markPosted(b, 1, 200, 20, 3000, "v1");
        repository.markFailed(c, "boom");
        repository.markSkipped(d, "empty");
        repository.markPosted(old, 5, 1000, 100, 9000, "v1");
        jdbc.sql("UPDATE reviews SET created_at = now() - interval '10 days' WHERE id = :id")
                .param("id", old)
                .update();

        StatsWindow week = stats.window(7);
        assertThat(week.reviews()).isEqualTo(4);
        assertThat(week.posted()).isEqualTo(2);
        assertThat(week.failed()).isEqualTo(1);
        assertThat(week.skipped()).isEqualTo(1);
        assertThat(week.commentsPosted()).isEqualTo(3);
        assertThat(week.inputTokens()).isEqualTo(300);
        assertThat(week.outputTokens()).isEqualTo(30);
        assertThat(week.failureRate()).isEqualTo(1.0 / 3);
        assertThat(week.p50LatencyMs()).isEqualTo(2000.0);
        assertThat(week.p95LatencyMs()).isEqualTo(2900.0);

        StatsWindow month = stats.window(30);
        assertThat(month.reviews()).isEqualTo(5);
        assertThat(month.commentsPosted()).isEqualTo(8);
    }

    @Test
    void dailyQuotaReservationStopsAtTheLimit() {
        LlmUsageRepository usage = new LlmUsageRepository(jdbc);
        LocalDate today = LocalDate.of(2026, 11, 10);

        assertThat(usage.tryReserve(today, 3, 5)).isTrue();
        assertThat(usage.tryReserve(today, 3, 5)).isFalse(); // 6 > 5, nothing is added
        assertThat(usage.tryReserve(today, 2, 5)).isTrue(); // exactly at the limit
        assertThat(usage.tryReserve(today, 1, 5)).isFalse();
        assertThat(usage.tryReserve(today.plusDays(1), 1, 5)).isTrue(); // a new day starts at 0
        assertThat(usage.tryReserve(today.plusDays(2), 6, 5)).isFalse(); // can never fit
    }
}
