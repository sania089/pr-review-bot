package dev.prbot.http;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Retries a call on HTTP 429, HTTP 5xx and network errors (connect failures, timeouts). Waits for
 * the server's Retry-After when it sends one, otherwise backs off exponentially. Any other error is
 * rethrown at once: a 400 or 404 will not get better by asking again.
 */
public final class Retrier {

    private static final Logger log = LoggerFactory.getLogger(Retrier.class);

    /** Replaceable so tests do not really sleep. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final Sleeper sleeper;

    public Retrier(int maxAttempts, Duration baseDelay, Duration maxDelay) {
        this(maxAttempts, baseDelay, maxDelay, d -> Thread.sleep(d.toMillis()));
    }

    public Retrier(int maxAttempts, Duration baseDelay, Duration maxDelay, Sleeper sleeper) {
        this.maxAttempts = maxAttempts;
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
        this.sleeper = sleeper;
    }

    public <T> T call(Supplier<T> action) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                if (attempt >= maxAttempts || !isRetryable(e)) {
                    throw e;
                }
                Duration delay = delayFor(e, attempt);
                // Only the status or exception type is logged: bodies may echo request content.
                log.warn(
                        "Attempt {}/{} failed ({}), retrying in {} ms",
                        attempt,
                        maxAttempts,
                        describe(e),
                        delay.toMillis());
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    public void run(Runnable action) {
        call(() -> {
            action.run();
            return null;
        });
    }

    static boolean isRetryable(RuntimeException e) {
        if (e instanceof RestClientResponseException r) {
            int status = r.getStatusCode().value();
            return status == 429 || status >= 500;
        }
        return e instanceof ResourceAccessException;
    }

    private Duration delayFor(RuntimeException e, int attempt) {
        Duration delay = null;
        if (e instanceof RestClientResponseException r && r.getResponseHeaders() != null) {
            delay = parseRetryAfter(r.getResponseHeaders().getFirst("Retry-After"));
        }
        if (delay == null) {
            delay = baseDelay.multipliedBy(1L << Math.min(attempt - 1, 20));
        }
        return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
    }

    /** Retry-After is either a number of seconds or an HTTP date. Returns null if unusable. */
    static Duration parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(v)));
        } catch (NumberFormatException notSeconds) {
            try {
                ZonedDateTime when = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME);
                Duration wait = Duration.between(ZonedDateTime.now(when.getZone()), when);
                return wait.isNegative() ? Duration.ZERO : wait;
            } catch (RuntimeException notDate) {
                return null;
            }
        }
    }

    private static String describe(RuntimeException e) {
        if (e instanceof RestClientResponseException r) {
            return "HTTP " + r.getStatusCode().value();
        }
        return e.getClass().getSimpleName();
    }
}
