package dev.prbot.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class BotPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(BotProperties.class)
    static class Config {}

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(Config.class);

    private static String[] props(String... overrides) {
        List<String> all =
                new ArrayList<>(
                        List.of(
                                "prbot.github.auth=pat",
                                "prbot.github.token=t",
                                "prbot.webhook-secret=s",
                                "prbot.max-diff-chars=1000",
                                "prbot.max-comments=5",
                                "prbot.llm.provider=gemini",
                                "prbot.llm.gemini.api-key=gk",
                                "prbot.llm.gemini.model=m",
                                "prbot.llm.groq.api-key=",
                                "prbot.llm.groq.model=m",
                                "prbot.llm.claude.api-key=",
                                "prbot.llm.claude.model=m",
                                "prbot.executor.threads=2",
                                "prbot.executor.queue-capacity=5"));
        all.addAll(List.of(overrides)); // later entries win
        return all.toArray(String[]::new);
    }

    @Test
    void startsWithoutAnthropicKeyWhenAnotherProviderIsSelected() {
        runner.withPropertyValues(props()).run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void httpSettingsHaveSafeDefaultsAndCanBeOverridden() {
        runner.withPropertyValues(props("prbot.http.max-attempts=5"))
                .run(
                        ctx -> {
                            BotProperties.Http http = ctx.getBean(BotProperties.class).http();
                            assertThat(http.maxAttempts()).isEqualTo(5);
                            assertThat(http.connectTimeout()).hasSeconds(10);
                            assertThat(http.readTimeout()).hasSeconds(60);
                            assertThat(http.retryMaxDelay()).hasSeconds(30);
                        });
    }

    @Test
    void failsWithoutWebhookSecret() {
        runner.withPropertyValues(props("prbot.webhook-secret="))
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void failsWithoutGithubToken() {
        runner.withPropertyValues(props("prbot.github.token="))
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void appAuthNeedsAppIdAndPrivateKey() {
        runner.withPropertyValues(props("prbot.github.auth=app", "prbot.github.app-id=1"))
                .run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues(
                        props(
                                "prbot.github.auth=app",
                                "prbot.github.token=",
                                "prbot.github.app-id=1",
                                "prbot.github.app-private-key=pem"))
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void failsWhenSelectedProviderHasNoKey() {
        runner.withPropertyValues(props("prbot.llm.provider=groq"))
                .run(
                        ctx -> {
                            assertThat(ctx).hasFailed();
                            assertThat(ctx.getStartupFailure())
                                    .hasStackTraceContaining(
                                            "API key of the selected prbot.llm.provider");
                        });
    }

    @Test
    void claudeIsValidOnceItsKeyIsSet() {
        runner.withPropertyValues(props("prbot.llm.provider=claude", "prbot.llm.claude.api-key=ck"))
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void failureMessageDoesNotContainSecrets() {
        runner.withPropertyValues(
                        props("prbot.webhook-secret=", "prbot.github.token=super-secret-token"))
                .run(
                        ctx ->
                                assertThat(ctx.getStartupFailure().toString())
                                        .doesNotContain("super-secret-token"));
    }
}
