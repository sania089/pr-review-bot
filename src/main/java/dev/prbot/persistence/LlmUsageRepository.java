package dev.prbot.persistence;

import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Daily count of model requests, used by the quota guard. */
@Repository
public class LlmUsageRepository {

    private final JdbcClient jdbc;

    public LlmUsageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Adds {@code requests} to the day's count if the total stays within {@code limit}. One atomic
     * statement, so concurrent reviews (or instances) cannot overshoot the cap.
     */
    public boolean tryReserve(LocalDate day, int requests, int limit) {
        if (requests > limit) {
            return false;
        }
        return jdbc.sql(
                        """
                        INSERT INTO llm_usage (day, requests) VALUES (:day, :n)
                        ON CONFLICT (day) DO UPDATE SET requests = llm_usage.requests + :n
                         WHERE llm_usage.requests + :n <= :limit
                        RETURNING requests
                        """)
                .param("day", day)
                .param("n", requests)
                .param("limit", limit)
                .query(Integer.class)
                .optional()
                .isPresent();
    }
}
