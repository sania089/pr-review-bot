package dev.prbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "prbot")
public record BotProperties(
        String githubToken,
        String webhookSecret,
        String anthropicApiKey,
        String model,
        int maxDiffChars,
        int maxComments) {
}
