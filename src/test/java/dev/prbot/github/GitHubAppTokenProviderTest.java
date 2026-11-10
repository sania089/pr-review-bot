package dev.prbot.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.prbot.TestProps;
import dev.prbot.config.BotProperties;
import dev.prbot.config.BotProperties.Auth;
import dev.prbot.config.BotProperties.GitHub;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GitHubAppTokenProviderTest {

    private static final String URL = "https://api.github.com/app/installations/%d/access_tokens";
    private static final Instant T0 = Instant.parse("2026-11-06T10:00:00Z");

    private MockRestServiceServer server;
    private GitHubAppTokenProvider provider;
    private Instant now;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        String pem =
                "-----BEGIN PRIVATE KEY-----\n"
                        + Base64.getMimeEncoder()
                                .encodeToString(gen.generateKeyPair().getPrivate().getEncoded())
                        + "\n-----END PRIVATE KEY-----";
        BotProperties base = TestProps.with(BotProperties.Provider.GEMINI);
        BotProperties props =
                new BotProperties(
                        new GitHub(Auth.APP, null, "12345", pem),
                        base.webhookSecret(),
                        base.maxDiffChars(),
                        base.maxComments(),
                        base.llm(),
                        base.executor(),
                        base.http());
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        now = T0;
        Clock clock =
                new Clock() {
                    public java.time.ZoneId getZone() {
                        return ZoneOffset.UTC;
                    }

                    public Clock withZone(java.time.ZoneId zone) {
                        return this;
                    }

                    public Instant instant() {
                        return now;
                    }
                };
        provider = new GitHubAppTokenProvider(props, builder, TestProps.retrier(), clock);
    }

    private void expectToken(long installation, String token, Instant expiresAt) {
        server.expect(ExpectedCount.once(), requestTo(URL.formatted(installation)))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", org.hamcrest.Matchers.startsWith("Bearer ey")))
                .andRespond(
                        withSuccess(
                                "{\"token\":\"%s\",\"expires_at\":\"%s\"}"
                                        .formatted(token, expiresAt),
                                MediaType.APPLICATION_JSON));
    }

    @Test
    void cachesTheTokenPerInstallationUntilFiveMinutesBeforeExpiry() {
        expectToken(1, "tok-1", T0.plus(Duration.ofHours(1)));
        expectToken(2, "tok-2", T0.plus(Duration.ofHours(1)));

        assertThat(provider.tokenFor(1)).isEqualTo("tok-1");
        assertThat(provider.tokenFor(2)).isEqualTo("tok-2");
        now = T0.plus(Duration.ofMinutes(54));
        assertThat(provider.tokenFor(1)).isEqualTo("tok-1"); // still cached, no new request
        server.verify();
    }

    @Test
    void refreshesOnceTheTokenIsInsideTheExpiryMargin() {
        expectToken(1, "old", T0.plus(Duration.ofHours(1)));
        expectToken(1, "new", T0.plus(Duration.ofHours(2)));

        assertThat(provider.tokenFor(1)).isEqualTo("old");
        now = T0.plus(Duration.ofMinutes(56));
        assertThat(provider.tokenFor(1)).isEqualTo("new");
        server.verify();
    }

    @Test
    void retriesATransientGitHubError() {
        server.expect(ExpectedCount.once(), requestTo(URL.formatted(1)))
                .andRespond(withServerError());
        expectToken(1, "tok", T0.plus(Duration.ofHours(1)));

        assertThat(provider.tokenFor(1)).isEqualTo("tok");
        server.verify();
    }

    @Test
    void failsClearlyWithoutAnInstallationId() {
        assertThatThrownBy(() -> provider.tokenFor(0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("installation id");
    }
}
