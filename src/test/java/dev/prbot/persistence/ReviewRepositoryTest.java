package dev.prbot.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.prbot.llm.LlmResponse;
import dev.prbot.review.ReviewResult;
import java.util.List;
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

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource ds =
                new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = JdbcClient.create(ds);
        repository = new ReviewRepository(jdbc);
    }

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE reviews").update();
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

        repository.markPosted(
                id, 3, new LlmResponse(new ReviewResult("s", List.of()), 100, 20), 1500);

        assertThat(row(id))
                .containsEntry("status", "POSTED")
                .containsEntry("comments_posted", 3)
                .containsEntry("input_tokens", 100)
                .containsEntry("output_tokens", 20)
                .containsEntry("latency_ms", 1500L);
        assertThat(row(id).get("completed_at")).isNotNull();
        assertThat(enqueue("sha1")).isNull();
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
}
