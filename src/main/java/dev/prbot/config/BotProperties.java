package dev.prbot.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import org.springframework.beans.factory.annotation.Value;

/** Validated at startup, so a missing secret stops the app instead of failing on the first PR. */
@Validated
@ConfigurationProperties(prefix = "prbot")
public record BotProperties(
        @NotBlank String githubToken,
        @NotBlank String webhookSecret,
        @Min(1) int maxDiffChars,
        @Min(1) int maxComments,
        @Valid @NotNull Llm llm,
        @Valid @NotNull Executor executor) {

    public enum Provider {
        GEMINI,
        GROQ,
        CLAUDE
    }

    public record ProviderConfig(String apiKey, @NotBlank String model) {}

    /** Only the selected provider needs an API key, so Claude can stay configured but unused. */
    public record Llm(
            @NotNull Provider provider,
            @Valid @NotNull ProviderConfig gemini,
            @Valid @NotNull ProviderConfig groq,
            @Valid @NotNull ProviderConfig claude) {

        public ProviderConfig selected() {
            return switch (provider) {
                case GEMINI -> gemini;
                case GROQ -> groq;
                case CLAUDE -> claude;
            };
        }

        @AssertTrue(message = "the API key of the selected prbot.llm.provider must be set")
        public boolean isSelectedProviderKeyPresent() {
            if (provider == null || gemini == null || groq == null || claude == null) {
                return true; // reported by the other constraints
            }
            String key = selected().apiKey();
            return key != null && !key.isBlank();
        }
    }

    public record Executor(@Min(1) int threads, @Min(0) int queueCapacity) {}
}
