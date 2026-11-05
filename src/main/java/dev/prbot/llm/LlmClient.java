package dev.prbot.llm;

/** One implementation per provider, selected with {@code prbot.llm.provider}. */
public interface LlmClient {

    LlmResponse review(String annotatedDiff);
}
