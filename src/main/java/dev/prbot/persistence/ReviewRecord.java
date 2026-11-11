package dev.prbot.persistence;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

/** One row of {@code reviews}, as returned by the dashboard API. Metrics are null until set. */
public record ReviewRecord(
        long id,
        String repo,
        int prNumber,
        String headSha,
        String status,
        String provider,
        String model,
        String promptVersion,
        Integer inputTokens,
        Integer outputTokens,
        Long latencyMs,
        Integer commentsPosted,
        String error,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt) {

    @JsonProperty("prUrl")
    public String prUrl() {
        return "https://github.com/" + repo + "/pull/" + prNumber;
    }
}
