package dev.prbot.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.prbot.review.ReviewResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** The prompt and output schema shared by every provider. */
final class ReviewPrompt {

    /**
     * Bump when {@code prompts/review-<version>.txt} changes in a way that affects results; the
     * version is stored with every review so quality can be compared across prompt changes.
     */
    static final String VERSION = "v1";

    static final String SYSTEM = load("/prompts/review-" + VERSION + ".txt");

    private static String load(String resource) {
        try (InputStream in = ReviewPrompt.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing prompt resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * JSON schema of {@link ReviewResult}; strict-mode friendly (all fields required, no extras).
     */
    static final Map<String, Object> SCHEMA =
            Map.of(
                    "type",
                    "object",
                    "additionalProperties",
                    false,
                    "required",
                    List.of("summary", "findings"),
                    "properties",
                    Map.of(
                            "summary",
                            Map.of(
                                    "type",
                                    "string",
                                    "description",
                                    "2-3 sentence overview of the change and its risk."),
                            "findings",
                            Map.of(
                                    "type",
                                    "array",
                                    "items",
                                    Map.of(
                                            "type",
                                            "object",
                                            "additionalProperties",
                                            false,
                                            "required",
                                            List.of("path", "line", "severity", "body"),
                                            "properties",
                                            Map.of(
                                                    "path", Map.of("type", "string"),
                                                    "line", Map.of("type", "integer"),
                                                    "severity",
                                                            Map.of(
                                                                    "type",
                                                                    "string",
                                                                    "enum",
                                                                    List.of(
                                                                            "high", "medium",
                                                                            "low")),
                                                    "body", Map.of("type", "string"))))));

    private ReviewPrompt() {}

    static String userMessage(String annotatedDiff) {
        return "<diff>\n" + annotatedDiff + "\n</diff>";
    }

    static ReviewResult parse(ObjectMapper mapper, String json) {
        try {
            ReviewResult result = mapper.readValue(json, ReviewResult.class);
            if (result.summary() == null || result.findings() == null) {
                throw new IllegalStateException("Model returned an incomplete review");
            }
            return result;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Model returned malformed review", e);
        }
    }
}
