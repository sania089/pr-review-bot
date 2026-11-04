package dev.prbot.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

class RetrierTest {

    private final List<Duration> sleeps = new ArrayList<>();
    private final Retrier retrier =
            new Retrier(3, Duration.ofSeconds(1), Duration.ofSeconds(30), sleeps::add);

    private static HttpServerErrorException serverError(HttpStatus status, String retryAfter) {
        HttpHeaders headers = new HttpHeaders();
        if (retryAfter != null) {
            headers.set("Retry-After", retryAfter);
        }
        return new HttpServerErrorException(
                status, "x", headers, new byte[0], StandardCharsets.UTF_8);
    }

    @Test
    void returnsImmediatelyOnSuccess() {
        assertThat(retrier.call(() -> "ok")).isEqualTo("ok");
        assertThat(sleeps).isEmpty();
    }

    @Test
    void retriesServerErrorsWithExponentialBackoffThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        String result =
                retrier.call(
                        () -> {
                            if (calls.incrementAndGet() < 3) {
                                throw serverError(HttpStatus.BAD_GATEWAY, null);
                            }
                            return "ok";
                        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(3);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void retriesNetworkErrors() {
        AtomicInteger calls = new AtomicInteger();
        retrier.run(
                () -> {
                    if (calls.incrementAndGet() == 1) {
                        throw new ResourceAccessException("timeout", new SocketTimeoutException());
                    }
                });
        assertThat(calls).hasValue(2);
    }

    @Test
    void doesNotRetryClientErrorsOtherThan429() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(
                        () ->
                                retrier.call(
                                        () -> {
                                            calls.incrementAndGet();
                                            throw HttpClientErrorException.create(
                                                    HttpStatus.UNPROCESSABLE_ENTITY,
                                                    "bad",
                                                    new HttpHeaders(),
                                                    new byte[0],
                                                    StandardCharsets.UTF_8);
                                        }))
                .isInstanceOf(HttpClientErrorException.class);
        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void retries429() {
        AtomicInteger calls = new AtomicInteger();
        retrier.run(
                () -> {
                    if (calls.incrementAndGet() == 1) {
                        throw HttpClientErrorException.create(
                                HttpStatus.TOO_MANY_REQUESTS,
                                "slow down",
                                new HttpHeaders(),
                                new byte[0],
                                StandardCharsets.UTF_8);
                    }
                });
        assertThat(calls).hasValue(2);
    }

    @Test
    void stopsAfterMaxAttemptsAndRethrowsTheLastError() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(
                        () ->
                                retrier.call(
                                        () -> {
                                            calls.incrementAndGet();
                                            throw serverError(HttpStatus.SERVICE_UNAVAILABLE, null);
                                        }))
                .isInstanceOf(HttpServerErrorException.class);
        assertThat(calls).hasValue(3);
        assertThat(sleeps).hasSize(2);
    }

    @Test
    void honoursRetryAfterSeconds() {
        AtomicInteger calls = new AtomicInteger();
        retrier.run(
                () -> {
                    if (calls.incrementAndGet() == 1) {
                        throw serverError(HttpStatus.SERVICE_UNAVAILABLE, "7");
                    }
                });
        assertThat(sleeps).containsExactly(Duration.ofSeconds(7));
    }

    @Test
    void capsRetryAfterAtTheMaximumDelay() {
        AtomicInteger calls = new AtomicInteger();
        retrier.run(
                () -> {
                    if (calls.incrementAndGet() == 1) {
                        throw serverError(HttpStatus.SERVICE_UNAVAILABLE, "3600");
                    }
                });
        assertThat(sleeps).containsExactly(Duration.ofSeconds(30));
    }

    @Test
    void parsesRetryAfterHttpDate() {
        String date =
                DateTimeFormatter.RFC_1123_DATE_TIME.format(
                        ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(20));
        assertThat(Retrier.parseRetryAfter(date))
                .isBetween(Duration.ofSeconds(15), Duration.ofSeconds(21));
    }

    @Test
    void ignoresUnusableRetryAfter() {
        assertThat(Retrier.parseRetryAfter(null)).isNull();
        assertThat(Retrier.parseRetryAfter("soon")).isNull();
    }

    @Test
    void doesNotRetryProgrammingErrors() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(
                        () ->
                                retrier.call(
                                        () -> {
                                            calls.incrementAndGet();
                                            throw new IllegalStateException("bug");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(1);
    }
}
