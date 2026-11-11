package dev.prbot.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Validated at startup, so a missing secret stops the app instead of failing on the first PR. */
@Validated
@ConfigurationProperties(prefix = "prbot")
public record BotProperties(
        @Valid @NotNull GitHub github,
        @NotBlank String webhookSecret,
        @Min(1) int maxDiffChars,
        @Min(1) int maxComments,
        @Min(1) @DefaultValue("24000") int maxBatchChars,
        @Valid @NotNull Llm llm,
        @Valid @NotNull Executor executor,
        @Valid @DefaultValue Http http,
        @Valid @DefaultValue Quota quota,
        @Valid @NotNull Admin admin) {

    public enum Auth {
        PAT,
        APP
    }

    /** PAT is for local development; APP (JWT, then installation token) is for real use. */
    public record GitHub(@NotNull Auth auth, String token, String appId, String appPrivateKey) {

        @AssertTrue(message = "the credentials of the selected prbot.github.auth must be set")
        public boolean isCredentialsPresent() {
            if (auth == null) {
                return true; // reported by @NotNull
            }
            return switch (auth) {
                case PAT -> hasText(token);
                case APP -> hasText(appId) && hasText(appPrivateKey);
            };
        }

        private static boolean hasText(String s) {
            return s != null && !s.isBlank();
        }
    }

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

    /**
     * Defaults sit under the Gemini free tier of gemini-3.5-flash-lite (15 requests per minute, 500
     * per day, reset at midnight Pacific time).
     */
    public record Quota(
            @Min(1) @DefaultValue("12") int requestsPerMinute,
            @Min(1) @DefaultValue("450") int dailyLimit,
            @NotBlank @DefaultValue("America/Los_Angeles") String resetZone) {}

    /**
     * The single dashboard login. The password has no default and must be long enough to resist
     * guessing.
     */
    public record Admin(
            @NotBlank @DefaultValue("admin") String username,
            @NotBlank @Size(min = 12, message = "must be at least 12 characters") String password) {

        @Override
        public String toString() {
            return "Admin[username=" + username + ", password=***]";
        }
    }

    /** Timeouts and retry policy shared by the GitHub and LLM clients. */
    public record Http(
            @NotNull @DefaultValue("10s") Duration connectTimeout,
            @NotNull @DefaultValue("60s") Duration readTimeout,
            @Min(1) @DefaultValue("3") int maxAttempts,
            @NotNull @DefaultValue("1s") Duration retryBaseDelay,
            @NotNull @DefaultValue("30s") Duration retryMaxDelay) {}
}
