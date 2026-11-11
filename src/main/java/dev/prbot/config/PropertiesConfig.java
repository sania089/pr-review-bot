package dev.prbot.config;

import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link BotProperties}. It lives here and not on the application class so that web-layer
 * test slices (which skip {@code @Configuration} scanning) do not need every secret to be set.
 */
@Configuration
@ConfigurationPropertiesScan("dev.prbot")
class PropertiesConfig {}
