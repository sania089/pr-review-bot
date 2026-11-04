package dev.prbot.llm;

import dev.prbot.review.ReviewResult;

/** One implementation per provider, selected with {@code prbot.llm.provider}. */
public interface LlmClient {

    ReviewResult review(String annotatedDiff);
}
