package dev.prbot.config;

import dev.prbot.http.Retrier;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit connect/read timeouts for every RestClient, and the shared retry policy. */
@Configuration
class HttpClientConfig {

    @Bean
    RestClientCustomizer timeoutCustomizer(BotProperties props) {
        BotProperties.Http http = props.http();
        ClientHttpRequestFactorySettings settings =
                ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(http.connectTimeout())
                        .withReadTimeout(http.readTimeout());
        return builder -> builder.requestFactory(ClientHttpRequestFactories.get(settings));
    }

    @Bean
    Retrier retrier(BotProperties props) {
        BotProperties.Http http = props.http();
        return new Retrier(http.maxAttempts(), http.retryBaseDelay(), http.retryMaxDelay());
    }
}
