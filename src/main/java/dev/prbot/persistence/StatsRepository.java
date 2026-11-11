package dev.prbot.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Dashboard aggregates, computed in SQL so the app never loads review rows to count them. */
@Repository
public class StatsRepository {

    private final JdbcClient jdbc;

    public StatsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public StatsWindow window(int days) {
        return jdbc.sql(
                        """
                        SELECT count(*) AS reviews,
                               count(*) FILTER (WHERE status = 'POSTED') AS posted,
                               count(*) FILTER (WHERE status = 'FAILED') AS failed,
                               count(*) FILTER (WHERE status = 'SKIPPED') AS skipped,
                               COALESCE(sum(comments_posted), 0) AS comments_posted,
                               COALESCE(sum(input_tokens), 0) AS input_tokens,
                               COALESCE(sum(output_tokens), 0) AS output_tokens,
                               percentile_cont(0.5) WITHIN GROUP (ORDER BY latency_ms)
                                   FILTER (WHERE status = 'POSTED') AS p50,
                               percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms)
                                   FILTER (WHERE status = 'POSTED') AS p95
                          FROM reviews
                         WHERE created_at >= now() - make_interval(days => :days)
                        """)
                .param("days", days)
                .query((rs, i) -> {
                    long posted = rs.getLong("posted");
                    long failed = rs.getLong("failed");
                    long finished = posted + failed;
                    return new StatsWindow(
                            days,
                            rs.getLong("reviews"),
                            posted,
                            failed,
                            rs.getLong("skipped"),
                            rs.getLong("comments_posted"),
                            finished == 0 ? 0.0 : (double) failed / finished,
                            nullableDouble(rs.getObject("p50")),
                            nullableDouble(rs.getObject("p95")),
                            rs.getLong("input_tokens"),
                            rs.getLong("output_tokens"));
                })
                .single();
    }

    private static Double nullableDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }
}
