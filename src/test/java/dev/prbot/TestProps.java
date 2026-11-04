package dev.prbot;

import dev.prbot.config.BotProperties;
import dev.prbot.config.BotProperties.Executor;
import dev.prbot.config.BotProperties.Llm;
import dev.prbot.config.BotProperties.Provider;
import dev.prbot.config.BotProperties.ProviderConfig;

/** Dummy configuration for tests; none of these values is a real secret. */
public final class TestProps {

    public static final String WEBHOOK_SECRET = "test-webhook-secret";

    private TestProps() {}

    public static BotProperties with(Provider provider) {
        return new BotProperties(
                "test-github-token",
                WEBHOOK_SECRET,
                60000,
                15,
                new Llm(
                        provider,
                        new ProviderConfig("test-gemini-key", "gemini-test"),
                        new ProviderConfig("test-groq-key", "groq-test"),
                        new ProviderConfig("test-claude-key", "claude-test")),
                new Executor(1, 1));
    }
}
