package dev.prbot.llm;

import dev.prbot.review.ReviewResult;

/** A parsed review plus the token counts the provider reported (0 if it reported none). */
public record LlmResponse(ReviewResult review, int inputTokens, int outputTokens) {}
