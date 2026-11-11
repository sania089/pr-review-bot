package dev.prbot.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** All SQL for the {@code reviews} table. Status moves QUEUED to POSTED, FAILED or SKIPPED. */
@Repository
public class ReviewRepository {

    private static final int MAX_ERROR_CHARS = 500;

    private final JdbcClient jdbc;

    public ReviewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims (repo, PR, head SHA) for review and returns the row id, or empty if it is already
     * queued or posted. A FAILED or SKIPPED row is re-queued, so redelivering the webhook retries
     * it, while a delivered review can never be posted twice.
     */
    public Optional<Long> enqueue(
            String repo, int prNumber, String headSha, String provider, String model) {
        return jdbc.sql(
                        """
                        INSERT INTO reviews (repo, pr_number, head_sha, status, provider, model)
                        VALUES (:repo, :pr, :sha, 'QUEUED', :provider, :model)
                        ON CONFLICT (repo, pr_number, head_sha) DO UPDATE
                           SET status = 'QUEUED', provider = :provider, model = :model,
                               error = NULL, completed_at = NULL, created_at = now()
                         WHERE reviews.status IN ('FAILED', 'SKIPPED')
                        RETURNING id
                        """)
                .param("repo", repo)
                .param("pr", prNumber)
                .param("sha", headSha)
                .param("provider", provider)
                .param("model", model)
                .query(Long.class)
                .optional();
    }

    public void markPosted(
            long id,
            int comments,
            int inputTokens,
            int outputTokens,
            long latencyMs,
            String promptVersion) {
        jdbc.sql(
                        """
                        UPDATE reviews
                           SET status = 'POSTED', comments_posted = :comments,
                               input_tokens = :in, output_tokens = :out, latency_ms = :latency,
                               prompt_version = :prompt, completed_at = now()
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("comments", comments)
                .param("in", inputTokens)
                .param("out", outputTokens)
                .param("latency", latencyMs)
                .param("prompt", promptVersion)
                .update();
    }

    public void markFailed(long id, String error) {
        finish(id, "FAILED", error);
    }

    public void markSkipped(long id, String reason) {
        finish(id, "SKIPPED", reason);
    }

    /** Newest first. A null {@code repo} or {@code status} means no filter on that column. */
    public List<ReviewRecord> findPage(String repo, String status, int limit, int offset) {
        return jdbc.sql(
                        """
                        SELECT * FROM reviews
                         WHERE (CAST(:repo AS TEXT) IS NULL OR repo = :repo)
                           AND (CAST(:status AS TEXT) IS NULL OR status = :status)
                         ORDER BY created_at DESC, id DESC
                         LIMIT :limit OFFSET :offset
                        """)
                .param("repo", repo)
                .param("status", status)
                .param("limit", limit)
                .param("offset", offset)
                .query(ReviewRecord.class)
                .list();
    }

    public long count(String repo, String status) {
        return jdbc.sql(
                        """
                        SELECT count(*) FROM reviews
                         WHERE (CAST(:repo AS TEXT) IS NULL OR repo = :repo)
                           AND (CAST(:status AS TEXT) IS NULL OR status = :status)
                        """)
                .param("repo", repo)
                .param("status", status)
                .query(Long.class)
                .single();
    }

    public Optional<ReviewRecord> findById(long id) {
        return jdbc.sql("SELECT * FROM reviews WHERE id = :id")
                .param("id", id)
                .query(ReviewRecord.class)
                .optional();
    }

    private void finish(long id, String status, String message) {
        String shortMessage =
                message.length() > MAX_ERROR_CHARS
                        ? message.substring(0, MAX_ERROR_CHARS)
                        : message;
        jdbc.sql(
                        "UPDATE reviews SET status = :status, error = :error, completed_at = now() WHERE id = :id")
                .param("id", id)
                .param("status", status)
                .param("error", shortMessage)
                .update();
    }
}
