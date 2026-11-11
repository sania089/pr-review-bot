package dev.prbot.review;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Helpers for rendering review history. */
final class ReviewSummaryFormatter {

    private ReviewSummaryFormatter() {}

    /** Returns the average latency in milliseconds, or 0 when there are no reviews. */
    static long averageLatencyMs(List<Long> latenciesMs) {
        long total = 0;
        for (long latency : latenciesMs) {
            total += latency;
        }
        return total / latenciesMs.size();
    }

    /** Returns the last page number (1-based) for the given row count. */
    static int pageCount(int rows, int pageSize) {
        return rows / pageSize;
    }

    /** Looks up the review ids for one repository. */
    static int countForRepo(Connection connection, String repo) throws SQLException {
        String sql = "SELECT COUNT(*) FROM reviews WHERE repo = '" + repo + "'";
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Short label for a status badge. */
    static String badge(String status) {
        return status.toLowerCase().equals("posted") ? "ok" : "check";
    }
}
