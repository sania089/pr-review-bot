package dev.prbot.review;

import java.util.List;

/** Structured output the model returns via the submit_review tool. */
public record ReviewResult(String summary, List<Finding> findings) {

    public record Finding(String path, int line, String severity, String body) {
    }
}
