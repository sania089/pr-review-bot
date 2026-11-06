package dev.prbot.github;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.prbot.config.BotProperties;
import dev.prbot.http.Retrier;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Exchanges the App's signed JWT for a short-lived installation token and caches it per
 * installation until shortly before it expires (GitHub issues them for one hour).
 */
@Component
@ConditionalOnProperty(prefix = "prbot.github", name = "auth", havingValue = "app")
class GitHubAppTokenProvider implements TokenProvider {

    /** Refresh this long before expiry so a token never dies in the middle of a review. */
    static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

    private record CachedToken(String token, Instant expiresAt) {}

    private record TokenResponse(String token, @JsonProperty("expires_at") String expiresAt) {}

    private final RestClient http;
    private final Retrier retrier;
    private final String appId;
    private final PrivateKey key;
    private final Clock clock;
    private final Map<Long, CachedToken> cache = new ConcurrentHashMap<>();

    @Autowired
    GitHubAppTokenProvider(BotProperties props, RestClient.Builder builder, Retrier retrier) {
        this(props, builder, retrier, Clock.systemUTC());
    }

    GitHubAppTokenProvider(
            BotProperties props, RestClient.Builder builder, Retrier retrier, Clock clock) {
        this.http = GitHubHttp.client(builder);
        this.retrier = retrier;
        this.appId = props.github().appId();
        this.key = AppJwt.parsePrivateKey(props.github().appPrivateKey()); // fail fast at startup
        this.clock = clock;
    }

    @Override
    public String tokenFor(long installationId) {
        if (installationId <= 0) {
            throw new IllegalStateException(
                    "The webhook payload has no installation id; is the GitHub App installed?");
        }
        // compute() is atomic per key, so concurrent reviews never mint duplicate tokens.
        return cache.compute(installationId, (id, cached) -> isFresh(cached) ? cached : mint(id))
                .token();
    }

    private boolean isFresh(CachedToken cached) {
        return cached != null && clock.instant().isBefore(cached.expiresAt().minus(REFRESH_MARGIN));
    }

    private CachedToken mint(long installationId) {
        String jwt = AppJwt.create(appId, key, clock.instant());
        TokenResponse response =
                retrier.call(
                        () ->
                                http.post()
                                        .uri(
                                                "/app/installations/{id}/access_tokens",
                                                installationId)
                                        .headers(h -> h.setBearerAuth(jwt))
                                        .retrieve()
                                        .body(TokenResponse.class));
        return new CachedToken(response.token(), Instant.parse(response.expiresAt()));
    }
}
