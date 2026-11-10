package dev.prbot.llm;

/** One implementation per provider, selected with {@code prbot.llm.provider}. */
public interface LlmClient {

    LlmResponse review(String annotatedDiff);

    /** Version of the prompt this client sends, stored with each review. */
    default String promptVersion() {
        return ReviewPrompt.VERSION;
    }
}
